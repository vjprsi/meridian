package io.meridian.routing.application;

import static io.meridian.routing.infrastructure.Rows.bool;
import static io.meridian.routing.infrastructure.Rows.date;
import static io.meridian.routing.infrastructure.Rows.dec;
import static io.meridian.routing.infrastructure.Rows.str;
import static io.meridian.routing.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.routing.domain.BankCodes;
import io.meridian.routing.domain.Pacs008Builder;
import io.meridian.routing.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Outbound rail messages: builds, stores (append-only) and sends the pacs.008 for a payment; raw payload access. */
@Service
public class MessageService {
    public static final String MESSAGE = "rail_message";

    private final Rows rows;
    private final Events events;
    private final SettlementService settlement;

    public MessageService(Rows rows, Events events, SettlementService settlement) {
        this.rows = rows;
        this.events = events;
        this.settlement = settlement;
    }

    /**
     * Builds and stores the outbound pacs.008 for a payment on a rail. One message per payment and rail: a repeated
     * request returns the stored message. Batch rails accumulate the debit in the open settlement batch.
     */
    @Transactional
    public ObjectNode pacs008(UUID railId, JsonNode payment) {
        if (payment == null || !payment.isObject()) {
            throw ApiException.badRequest("payment_required", "payment must be an object");
        }
        ObjectNode rail = rows.lock(RailService.RAIL, railId);
        String status = str(rail, "status");
        if (!"ACTIVE".equals(status) && !"DEGRADED".equals(status)) {
            throw ApiException.conflict("rail_unavailable", "Rail " + str(rail, "code") + " is " + status);
        }
        UUID paymentId = uuid(payment, "id");
        if (paymentId != null) {
            Optional<ObjectNode> existing = rows.queryOne("""
                    select row_to_json(t)::text from rail_message t where payment_id = ? and payment_rail_id = ?
                    and direction = 'OUTBOUND' and message_type = ? order by created_at limit 1""",
                    paymentId, railId, Pacs008Builder.MESSAGE_TYPE);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        String currency = str(payment, "currency");
        boolean supported = false;
        JsonNode ccys = rail.get("currencies");
        if (ccys != null && ccys.isArray()) {
            for (JsonNode c : ccys) {
                supported |= c.asString().equals(currency);
            }
        }
        if (!supported) {
            throw ApiException.unprocessable("currency_not_supported", "Rail " + str(rail, "code")
                    + " does not support " + currency);
        }
        BigDecimal amount = dec(payment, "amount");
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "payment.amount must be positive");
        }
        BigDecimal max = dec(rail, "max_amount");
        if (max != null && amount.compareTo(max) > 0) {
            throw ApiException.unprocessable("amount_above_scheme_maximum", "Amount exceeds the scheme maximum "
                    + max.toPlainString());
        }
        OffsetDateTime now = rows.scalar("select now()", OffsetDateTime.class).orElseThrow();
        LocalDate settlementDate = date(payment, "value_date");
        if (settlementDate == null) {
            settlementDate = date(payment, "requested_execution_date");
        }
        LocalDate today = rows.today();
        if (settlementDate == null || settlementDate.isBefore(today)) {
            settlementDate = today;
        }
        UUID id = Ids.newId();
        String msgId = "M" + id.toString().replace("-", "").toUpperCase().substring(0, 31);
        String xml;
        try {
            xml = Pacs008Builder.build(new Pacs008Builder.Transfer(msgId, now, str(payment, "instruction_id"),
                    str(payment, "end_to_end_id"), str(payment, "transaction_id"), str(payment, "uetr"), amount, currency,
                    settlementDate, str(payment, "charge_bearer"), str(rail, "participant_bic"),
                    str(payment, "debtor_name"), str(payment, "debtor_account_number"),
                    str(payment, "debtor_account_id_type"), str(payment, "debtor_agent_bic"),
                    str(payment, "creditor_name"), str(payment, "creditor_account_number"),
                    str(payment, "creditor_account_id_type"), str(payment, "creditor_agent_bic"),
                    str(payment, "creditor_agent_clearing_id"), str(payment, "purpose_code"),
                    str(payment, "remittance_unstructured")));
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_payment", e.getMessage());
        }
        UUID batchId = bool(rail, "is_instant") ? null : settlement.add(rail, currency, settlementDate, amount, true);
        ObjectNode msg = rows.object();
        msg.put("id", id.toString());
        msg.put("payment_rail_id", railId.toString());
        msg.put("direction", "OUTBOUND");
        msg.put("message_type", Pacs008Builder.MESSAGE_TYPE);
        msg.put("message_id", msgId);
        msg.put("end_to_end_id", str(payment, "end_to_end_id"));
        msg.put("payment_id", paymentId == null ? null : paymentId.toString());
        msg.put("rail_settlement_batch_id", batchId == null ? null : batchId.toString());
        msg.put("status", "SENT");
        msg.put("payload", xml);
        msg.put("payload_hash", BankCodes.sha256(xml));
        msg.put("payload_uri", "db://rail_message/" + id);
        msg.put("signature_valid", true);
        ObjectNode stored = rows.insert(MESSAGE, msg);
        events.publish("payments.rail_message.sent", MESSAGE, stored);
        return stored;
    }

    @Transactional(readOnly = true)
    public String payload(UUID id) {
        ObjectNode m = rows.get(MESSAGE, id);
        String p = str(m, "payload");
        if (p == null) {
            throw ApiException.notFound("rail message payload", id);
        }
        return p;
    }
}
