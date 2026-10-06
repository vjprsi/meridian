package io.meridian.routing.application;

import static io.meridian.routing.infrastructure.Rows.date;
import static io.meridian.routing.infrastructure.Rows.decOr0;
import static io.meridian.routing.infrastructure.Rows.integer;
import static io.meridian.routing.infrastructure.Rows.str;
import static io.meridian.routing.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.routing.domain.BankCodes;
import io.meridian.routing.domain.Pacs008Builder;
import io.meridian.routing.infrastructure.Downstream;
import io.meridian.routing.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * Settlement cycles of deferred-net rails: outbound debits and inbound credits accumulate in the OPEN batch of
 * (rail, currency, settlement date); close generates the settlement file; settle posts the net position between
 * the rail settlement (clearing) account and the nostro that funds the rail.
 */
@Service
public class SettlementService {
    static final String BATCH = "rail_settlement_batch";

    private final Rows rows;
    private final Events events;
    private final Downstream downstream;

    public SettlementService(Rows rows, Events events, Downstream downstream) {
        this.rows = rows;
        this.events = events;
        this.downstream = downstream;
    }

    /**
     * Adds one debit (outbound) or credit (inbound) to the OPEN batch for the rail, currency and date, creating the
     * batch when none is open (a closed cycle for the same date gets a new numbered cycle). Caller's transaction.
     */
    public UUID add(ObjectNode rail, String currency, LocalDate date, BigDecimal amount, boolean debit) {
        UUID railId = uuid(rail, "id");
        ObjectNode batch = openBatch(railId, str(rail, "code"), currency, date);
        UUID id = uuid(batch, "id");
        rows.execute(debit
                ? "update rail_settlement_batch set debit_count = debit_count + 1, debit_total = debit_total + ?, "
                        + "net_position = credit_total - (debit_total + ?) where id = ?"
                : "update rail_settlement_batch set credit_count = credit_count + 1, credit_total = credit_total + ?, "
                        + "net_position = (credit_total + ?) - debit_total where id = ?", amount, amount, id);
        return id;
    }

    private ObjectNode openBatch(UUID railId, String code, String currency, LocalDate date) {
        for (int attempt = 0; attempt < 3; attempt++) {
            var open = rows.queryOne("""
                    select row_to_json(t)::text from rail_settlement_batch t where payment_rail_id = ? and currency = ?
                    and settlement_date = ? and status = 'OPEN' order by created_at limit 1 for update""",
                    railId, currency, date);
            if (open.isPresent()) {
                return open.get();
            }
            long existing = rows.scalar("""
                    select count(*) from rail_settlement_batch where payment_rail_id = ? and currency = ?
                    and settlement_date = ?""", Long.class, railId, currency, date).orElse(0L);
            String cycle = code + "-" + date.toString().replace("-", "") + "-" + currency
                    + (existing == 0 ? "" : "-" + (existing + 1));
            rows.execute("""
                    insert into rail_settlement_batch (tenant_id, payment_rail_id, cycle_id, settlement_date, currency,
                      created_by, updated_by) values (?, ?, ?, ?, ?, ?, ?)
                    on conflict (tenant_id, payment_rail_id, cycle_id) do nothing""",
                    TenantContext.require(), railId, cycle, date, currency, TenantContext.principal(),
                    TenantContext.principal());
        }
        throw ApiException.conflict("batch_unavailable", "Could not open a settlement batch; retry");
    }

    @Transactional
    public ObjectNode close(UUID id) {
        ObjectNode b = rows.lock(BATCH, id);
        if (!"OPEN".equals(str(b, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only OPEN batches can be closed (batch is "
                    + str(b, "status") + ")");
        }
        ObjectNode rail = rows.get(RailService.RAIL, uuid(b, "payment_rail_id"));
        List<Pacs008Builder.BatchLine> lines = new ArrayList<>();
        for (ObjectNode m : rows.query("""
                select row_to_json(t)::text from rail_message t where rail_settlement_batch_id = ? order by created_at""", id)) {
            String dir = "OUTBOUND".equals(str(m, "direction")) ? "DBIT" : "CRDT";
            lines.add(new Pacs008Builder.BatchLine(str(m, "message_id"), str(m, "end_to_end_id"), dir, null));
        }
        OffsetDateTime now = rows.scalar("select now()", OffsetDateTime.class).orElseThrow();
        UUID fileId = Ids.newId();
        String fileMsgId = "STL" + fileId.toString().replace("-", "").substring(0, 29).toUpperCase();
        String file = Pacs008Builder.settlementFile(fileMsgId, str(rail, "code"), str(b, "cycle_id"),
                date(b, "settlement_date"), str(b, "currency"), integer(b, "debit_count"), decOr0(b, "debit_total"),
                integer(b, "credit_count"), decOr0(b, "credit_total"), decOr0(b, "net_position"), now, lines);
        ObjectNode msg = rows.object();
        msg.put("id", fileId.toString());
        msg.put("payment_rail_id", str(rail, "id"));
        msg.put("direction", "OUTBOUND");
        msg.put("message_type", "settlement.batch.v1");
        msg.put("message_id", fileMsgId);
        msg.put("rail_settlement_batch_id", id.toString());
        msg.put("status", "SENT");
        msg.put("payload", file);
        msg.put("payload_hash", BankCodes.sha256(file));
        msg.put("payload_uri", "db://rail_message/" + fileId);
        msg.put("signature_valid", true);
        ObjectNode stored = rows.insert(MessageService.MESSAGE, msg);
        events.publish("payments.rail_message.sent", MessageService.MESSAGE, stored);
        rows.execute("update rail_settlement_batch set status = 'CLOSED', closed_at = now(), settlement_file_id = ? "
                + "where id = ?", fileId, id);
        ObjectNode closed = rows.get(BATCH, id);
        events.publish("payments.rail_settlement_batch.closed", BATCH, closed);
        return closed;
    }

    @Transactional
    public ObjectNode settle(UUID id) {
        ObjectNode b = rows.lock(BATCH, id);
        if (!"CLOSED".equals(str(b, "status")) && !"SUBMITTED".equals(str(b, "status"))) {
            throw ApiException.conflict("invalid_transition", "Only CLOSED batches can be settled (batch is "
                    + str(b, "status") + ")");
        }
        BigDecimal net = decOr0(b, "net_position");
        UUID journal = null;
        if (net.signum() != 0) {
            ObjectNode rail = rows.get(RailService.RAIL, uuid(b, "payment_rail_id"));
            UUID settlementAccount = uuid(rail, "settlement_account_id");
            if (settlementAccount == null) {
                throw ApiException.unprocessable("settlement_account_missing",
                        "Rail " + str(rail, "code") + " has no settlement_account_id");
            }
            ObjectNode nostro = rows.queryOne("""
                    select row_to_json(t)::text from nostro_account t where payment_rail_id = ? and currency = ?
                    and is_active order by created_at limit 1""", uuid(rail, "id"), str(b, "currency"))
                    .orElseThrow(() -> ApiException.unprocessable("nostro_not_found",
                            "No active nostro account funds rail " + str(rail, "code") + " in " + str(b, "currency")));
            UUID nostroLedger = uuid(nostro, "ledger_account_id");
            String desc = "Settlement " + str(b, "cycle_id") + " net " + net.toPlainString();
            // net < 0: we owe the scheme -> clear the settlement account against the nostro (funds leave the nostro)
            journal = net.signum() < 0
                    ? downstream.transfer("rsb-settle:" + id, settlementAccount, nostroLedger, net.abs(),
                            str(b, "currency"), desc, "RAIL_SETTLEMENT_BATCH", id)
                    : downstream.transfer("rsb-settle:" + id, nostroLedger, settlementAccount, net,
                            str(b, "currency"), desc, "RAIL_SETTLEMENT_BATCH", id);
        }
        rows.execute("update rail_settlement_batch set status = 'SETTLED', settled_at = now(), journal_entry_id = ?::uuid "
                + "where id = ?", journal == null ? null : journal.toString(), id);
        ObjectNode settled = rows.get(BATCH, id);
        events.publish("payments.rail_settlement_batch.settled", BATCH, settled);
        return settled;
    }
}
