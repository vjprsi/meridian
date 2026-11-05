package io.meridian.payment.application;

import static io.meridian.payment.infrastructure.Rows.str;
import static io.meridian.payment.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.payment.domain.Bic;
import io.meridian.payment.domain.Iban;
import io.meridian.payment.infrastructure.Rows;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Repair queue: payments whose enrichment or routing failed wait here for an operator, who assigns, corrects
 * (a whitelist of creditor / remittance fields, re-validated) and resubmits them, or cancels them.
 */
@Service
public class RepairService {
    static final String TABLE = "payment_repair";
    private static final Set<String> CORRECTABLE = Set.of("creditor_name", "creditor_agent_bic",
            "creditor_agent_clearing_id", "creditor_account_number", "creditor_account_id_type", "creditor_country",
            "creditor_address", "purpose_code", "category_purpose", "remittance_unstructured", "requested_execution_date");

    private final Rows rows;
    private final Events events;
    private final PaymentRecords records;
    private final PaymentProcessor processor;
    private final TransactionTemplate tx;

    public RepairService(Rows rows, Events events, PaymentRecords records, PaymentProcessor processor,
                         TransactionTemplate tx) {
        this.rows = rows;
        this.events = events;
        this.records = records;
        this.processor = processor;
        this.tx = tx;
    }

    /**
     * WE-FIN-1 / D-ED-10: opens a repair item for an ETM order whose health alert reached RED (manual intervention
     * required), in addition to the alert. Idempotent - at most one OPEN / IN_REPAIR repair per order's payment leg, so
     * repeated RED transitions or overlapping resolver passes never pile up duplicates. This is an order-level manual
     * -intervention tracker: it does NOT move the payment into IN_REPAIR (the payment itself may be fine; the ambiguity
     * is in the order/fulfilment), so the ambiguous-settlement reversal / recall path stays in control of the money.
     * Returns the repair row, or null when the order has no payment leg or a repair is already open.
     */
    public ObjectNode autoOpenForOrder(ObjectNode order) {
        UUID paymentId = uuid(order, "payment_id");
        if (paymentId == null) {
            return null;
        }
        boolean alreadyOpen = rows.scalar(
                "select count(*) from payment_repair where payment_id = ? and status in ('OPEN', 'IN_REPAIR')",
                Long.class, paymentId).orElse(0L) > 0;
        if (alreadyOpen) {
            return null;
        }
        ObjectNode repair = rows.object();
        repair.put("payment_id", paymentId.toString());
        repair.put("status", "OPEN");
        repair.put("error_code", "etm_manual_intervention");
        repair.put("error_detail", PaymentProcessor.truncate("Order " + str(order, "order_reference")
                + " reached RED (" + str(order, "alert_level") + "); manual intervention required to resolve the "
                + "ambiguous / failed Order & Pay settlement", 255));
        ObjectNode fix = rows.object();
        fix.put("payment_order_id", str(order, "id"));
        repair.set("suggested_fix", fix);
        ObjectNode r = rows.insert(TABLE, repair);
        events.publish("payments.payment_repair.opened", TABLE, r);
        return r;
    }

    public ObjectNode assign(UUID repairId, String operator) {
        return tx.execute(s -> {
            ObjectNode r = open(repairId);
            ObjectNode c = rows.object();
            c.put("assigned_to", operator);
            c.put("status", "IN_REPAIR");
            return rows.update(TABLE, UUID.fromString(str(r, "id")), c);
        });
    }

    /** Applies operator corrections, re-validates and sends the payment through the pipeline again. */
    public ObjectNode resubmit(UUID repairId, JsonNode corrections) {
        if (corrections == null || !corrections.isObject() || corrections.isEmpty()) {
            throw ApiException.unprocessable("corrections_required", "corrections must be a non-empty object");
        }
        ObjectNode fix = rows.object();
        for (Map.Entry<String, JsonNode> e : corrections.properties()) {
            if (!CORRECTABLE.contains(e.getKey())) {
                throw ApiException.unprocessable("field_not_correctable", e.getKey() + " cannot be corrected in repair");
            }
            fix.set(e.getKey(), e.getValue());
        }
        if (fix.hasNonNull("creditor_agent_bic")) {
            String bic = Bic.normalise(str(fix, "creditor_agent_bic"));
            if (!Bic.isValid(bic)) {
                throw ApiException.unprocessable("invalid_bic", "creditor_agent_bic " + bic + " is malformed");
            }
            fix.put("creditor_agent_bic", bic);
        }
        UUID paymentId = tx.execute(s -> {
            ObjectNode r = open(repairId);
            UUID pid = uuid(r, "payment_id");
            ObjectNode p = records.lockIn(pid, Set.of("IN_REPAIR"), "resubmit");
            String type = fix.hasNonNull("creditor_account_id_type") ? str(fix, "creditor_account_id_type")
                    : str(p, "creditor_account_id_type");
            if (fix.hasNonNull("creditor_account_number") && "IBAN".equals(type)) {
                String iban = Iban.normalise(str(fix, "creditor_account_number"));
                Iban.validate(iban).ifPresent(code -> {
                    throw ApiException.unprocessable(code, "creditor_account_number is not a valid IBAN");
                });
                fix.put("creditor_account_number", iban);
            }
            ObjectNode changes = fix.deepCopy();
            changes.putNull("rail_id");
            changes.putNull("rail_code");
            records.transition(p, "INITIATED", null, "Resubmitted from repair", changes, null);
            ObjectNode c = rows.object();
            c.put("status", "RESUBMITTED");
            c.set("corrections", fix);
            c.put("resubmitted_at", rows.now().toString());
            events.publish("payments.payment_repair.resolved", TABLE, rows.update(TABLE, repairId, c));
            return pid;
        });
        processor.process(paymentId, PaymentProcessor.Options.DEFAULT);
        return rows.get(TABLE, repairId);
    }

    public ObjectNode cancel(UUID repairId, String reason) {
        UUID paymentId = tx.execute(s -> uuid(open(repairId), "payment_id"));
        processor.cancel(paymentId, reason);
        return tx.execute(s -> {
            ObjectNode c = rows.object();
            c.put("status", "CANCELLED");
            ObjectNode r = rows.update(TABLE, repairId, c);
            events.publish("payments.payment_repair.resolved", TABLE, r);
            return r;
        });
    }

    private ObjectNode open(UUID repairId) {
        ObjectNode r = rows.lock(TABLE, repairId);
        if (!Set.of("OPEN", "IN_REPAIR").contains(str(r, "status"))) {
            throw ApiException.conflict("invalid_transition", "Repair item is already " + str(r, "status"));
        }
        return r;
    }

}
