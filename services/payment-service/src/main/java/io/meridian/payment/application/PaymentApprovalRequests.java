package io.meridian.payment.application;

import static io.meridian.payment.infrastructure.Rows.dec;
import static io.meridian.payment.infrastructure.Rows.str;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.payment.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Central maker-checker for payments and bulk payments (G11). While a payment / bulk payment is PENDING_APPROVAL it
 * has one open approval request in workflow-service (routed by amount) for the next approval it needs. The request
 * carries the approval progress (approvals received over all levels) as fingerprint: every decision, direct or from
 * workflow-service, makes it stale, so the open request is cancelled and the next one opened until all levels are
 * approved. Card numbers and account identifiers other than internal ids are never sent.
 */
@Component
public class PaymentApprovalRequests {
    public static final String PAYMENT = "payment";
    public static final String BULK = "bulk_payment";
    static final String ACTION = "approve";

    private final Approvals approvals;
    private final Rows rows;

    public PaymentApprovalRequests(Approvals approvals, Rows rows) {
        this.approvals = approvals;
        this.rows = rows;
    }

    /** Opens the approval request for the payment's next approval (payment PENDING_APPROVAL with its levels). */
    public void requestPayment(ObjectNode payment) {
        if (!approvals.centralised()) {
            return;
        }
        UUID id = UUID.fromString(str(payment, "id"));
        int progress = progress("payment_id", id);
        Map<String, Object> payload = base(id, "payment_id", progress);
        payload.put("payment_type", str(payment, "payment_type"));
        payload.put("direction", str(payment, "direction"));
        payload.put("amount", dec(payment, "amount").toPlainString());
        payload.put("currency", str(payment, "currency"));
        payload.put("creditor_name", str(payment, "creditor_name"));
        payload.put("end_to_end_id", str(payment, "end_to_end_id"));
        payload.put("requested_execution_date", str(payment, "requested_execution_date"));
        payload.put("initiated_by", str(payment, "initiated_by"));
        String title = "Payment " + dec(payment, "amount").toPlainString() + " " + str(payment, "currency")
                + (str(payment, "creditor_name") == null ? "" : " to " + str(payment, "creditor_name"));
        approvals.request(ApprovalSpec.of(PAYMENT, id, ACTION, title, payload)
                        .withAmount(dec(payment, "amount"), str(payment, "currency")),
                "payment:" + id + ":" + progress);
    }

    /** Opens the approval request for the bulk payment's next approval (bulk PENDING_APPROVAL with its levels). */
    public void requestBulk(ObjectNode bulk) {
        if (!approvals.centralised()) {
            return;
        }
        UUID id = UUID.fromString(str(bulk, "id"));
        int progress = progress("bulk_payment_id", id);
        BigDecimal total = validTotal(id);
        Map<String, Object> payload = base(id, "bulk_payment_id", progress);
        payload.put("name", str(bulk, "name"));
        payload.put("amount", total.toPlainString());
        payload.put("currency", str(bulk, "currency"));
        payload.put("valid_count", bulk.path("valid_count").asInt());
        payload.put("submitted_by", str(bulk, "submitted_by"));
        String title = "Bulk payment " + (str(bulk, "name") == null ? id.toString() : str(bulk, "name")) + " ("
                + bulk.path("valid_count").asInt() + " items, " + total.toPlainString() + " " + str(bulk, "currency") + ")";
        approvals.request(ApprovalSpec.of(BULK, id, ACTION, title, payload).withAmount(total, str(bulk, "currency")),
                "bulk:" + id + ":" + progress);
    }

    /**
     * After an approval level decision (direct or from workflow-service), outside a transaction: the open request
     * is stale, so it is cancelled; when approvals are still needed the next request is opened.
     */
    public void afterDecision(String resource, UUID id, boolean finished) {
        if (!approvals.centralised()) {
            return;
        }
        approvals.cancelOpen(resource, id, null, finished ? "decided" : "approval progressed");
        if (finished) {
            return;
        }
        Optional<ObjectNode> subject = rows.find(PAYMENT.equals(resource) ? PaymentRecords.TABLE : BulkService.TABLE, id)
                .filter(s -> "PENDING_APPROVAL".equals(str(s, "status")));
        if (subject.isPresent()) {
            if (PAYMENT.equals(resource)) {
                requestPayment(subject.get());
            } else {
                requestBulk(subject.get());
            }
        }
    }

    /** After a direct cancel / reject: closes open requests (after commit inside a transaction). */
    public void closed(String resource, UUID id, String reason) {
        approvals.cancelOpen(resource, id, null, reason);
    }

    /**
     * True while the payment / bulk payment is PENDING_APPROVAL with the amount and approval progress of the
     * request's payload (otherwise the decision is stale: decided directly, progressed or redelivered).
     */
    public boolean awaits(String resource, UUID id, JsonNode payload) {
        String table = PAYMENT.equals(resource) ? PaymentRecords.TABLE : BulkService.TABLE;
        Optional<ObjectNode> subject = rows.find(table, id).filter(s -> "PENDING_APPROVAL".equals(str(s, "status")));
        if (subject.isEmpty()) {
            return false;
        }
        String column = PAYMENT.equals(resource) ? "payment_id" : "bulk_payment_id";
        if (payload.hasNonNull("approvals_received")
                && payload.get("approvals_received").asInt() != progress(column, id)) {
            return false;
        }
        if (payload.hasNonNull("amount")) {
            BigDecimal amount = PAYMENT.equals(resource) ? dec(subject.get(), "amount") : validTotal(id);
            return amount != null && amount.compareTo(new BigDecimal(payload.get("amount").asString())) == 0;
        }
        return true;
    }

    private Map<String, Object> base(UUID id, String column, int progress) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(column, id.toString());
        rows.queryOne("select row_to_json(t)::text from payment_approval t where " + column + " = ? and status = 'PENDING' "
                + "order by approver_level limit 1", id).ifPresent(l -> {
                    payload.put("approver_level", l.path("approver_level").asInt());
                    payload.put("required_approvals", l.path("required_approvals").asInt());
                    payload.put("level_approvals_received", l.path("approvals_received").asInt());
                    payload.put("expires_at", str(l, "expires_at"));
                });
        payload.put("levels", rows.scalar("select count(*) from payment_approval where " + column + " = ?", Long.class, id)
                .orElse(0L));
        payload.put("approvals_received", progress);
        return payload;
    }

    private int progress(String column, UUID id) {
        return rows.scalar("select coalesce(sum(approvals_received), 0)::int from payment_approval where " + column + " = ?",
                Integer.class, id).orElse(0);
    }

    private BigDecimal validTotal(UUID bulkId) {
        return rows.scalar("select coalesce(sum(amount), 0) from bulk_payment_item where bulk_payment_id = ? and status = 'VALID'",
                BigDecimal.class, bulkId).orElse(BigDecimal.ZERO);
    }
}
