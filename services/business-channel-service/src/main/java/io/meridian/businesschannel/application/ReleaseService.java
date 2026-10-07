package io.meridian.businesschannel.application;

import static io.meridian.businesschannel.application.CurrentUser.business;

import io.meridian.businesschannel.domain.BatchCsv;
import io.meridian.common.error.ApiException;
import io.meridian.common.time.Clocks;
import io.meridian.events.OutboxWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Release of a fully approved business payment to payment-service, with the channel's service identity.
 *
 * <ul>
 *   <li>Only APPROVED or RELEASE_FAILED payments are released, under a row lock (two concurrent releases never both
 *       call downstream); a RELEASED payment is returned as it is.</li>
 *   <li>The downstream body is rebuilt from the stored row only (never from a request) and the Idempotency-Key is
 *       derived from the business payment ({@code bzp-release:<id>}); the call is made as the initiator
 *       ({@code X-User: business-user:<initiator>}), so a retry by anyone replays the same request and never creates a
 *       second payment.</li>
 *   <li>When payment-service applies its own approval levels (PENDING_APPROVAL), the business approvers' decisions are
 *       relayed as its approvals, each as that approver ({@code bzp-approve:<id>:<user>}), so its four-eyes rule
 *       compares the real initiator and approvers. Levels the business's approvers cannot satisfy stay with the bank.</li>
 *   <li>Bulk and payroll batches are uploaded as CSV of the validated lines, submitted as the initiator and approved
 *       the same way.</li>
 *   <li>A refusal or outage marks the payment RELEASE_FAILED with the problem; {@code /release} retries.</li>
 * </ul>
 */
@Service
public class ReleaseService {
    static final String PAY = "payment-service";
    static final String TABLE = PaymentService.TABLE;
    static final Set<String> RELEASABLE = Set.of("APPROVED", "RELEASE_FAILED");
    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

    private final Rows rows;
    private final Downstream down;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final ActionAudit audit;

    public ReleaseService(Rows rows, Downstream down, OutboxWriter outbox, TransactionTemplate tx, ActionAudit audit) {
        this.rows = rows;
        this.down = down;
        this.outbox = outbox;
        this.tx = tx;
        this.audit = audit;
    }

    public ObjectNode release(UUID id) {
        return tx.execute(s -> {
            ObjectNode p = rows.lock(TABLE, business(), id).orElseThrow(() -> Access.notFound("PAYMENT", id));
            if (!RELEASABLE.contains(p.path("status").asString(""))) {
                return p;
            }
            if (!PaymentService.checksum(p).equals(p.path("checksum").asString(null))) {
                return failed(id, "content_changed", "The payment content changed after approval");
            }
            UUID initiator = PaymentService.initiator(p);
            List<UUID> approvers = approvers(id);
            try {
                ObjectNode c = PaymentService.BATCH_KINDS.contains(p.path("kind").asString())
                        ? releaseBatch(id, p, initiator, approvers) : releaseSingle(id, p, initiator, approvers);
                c.put("status", "RELEASED").put("released_at", Clocks.now().toString()).putNull("release_error");
                ObjectNode r = rows.update(TABLE, id, c);
                outbox.publish("channels.business_payment.released", TABLE, id, r);
                audit.succeeded("businessPaymentRelease", "PAYMENT", id,
                        r.hasNonNull("payment_id") ? r.path("payment_id").asString() : r.path("bulk_payment_id").asString(null));
                return r;
            } catch (ApiException e) {
                log.warn("release of business payment {} failed: {} {}", id, e.code(), e.getMessage());
                return failed(id, e.code(), e.getMessage());
            }
        });
    }

    private ObjectNode releaseSingle(UUID id, ObjectNode p, UUID initiator, List<UUID> approvers) {
        ObjectNode body = paymentBody(id, p, initiator, approvers);
        JsonNode created = down.postAs(initiator, PAY, "/v1/payments", body, "bzp-release:" + id);
        String paymentId = created.path("id").asString();
        String status = created.path("status").asString(null);
        if ("PENDING_APPROVAL".equals(status)) {
            status = relayApprovals(id, "/v1/payments/" + paymentId, approvers, status);
        }
        return rows.object().put("payment_id", paymentId).put("downstream_status", status);
    }

    private ObjectNode releaseBatch(UUID id, ObjectNode p, UUID initiator, List<UUID> approvers) {
        String currency = p.path("currency").asString();
        ObjectNode body = rows.object()
                .put("content", BatchCsv.render(PaymentService.lines(p.get("items")), currency))
                .put("file_format", "CSV")
                .put("customer_id", business().toString())
                .put("debtor_account_id", p.path("debtor_account_id").asString())
                .put("name", p.path("name").asString(p.path("kind").asString() + " " + id))
                .put("currency", currency)
                .put("batch_booking", true);
        if (p.hasNonNull("requested_execution_date")) {
            body.put("requested_execution_date", p.path("requested_execution_date").asString());
        }
        JsonNode bulk = down.postAs(initiator, PAY, "/v1/bulk-payments/upload", body, "bzp-release:" + id);
        String bulkId = bulk.path("id").asString();
        String status = bulk.path("status").asString(null);
        ObjectNode c = rows.object().put("bulk_payment_id", bulkId);
        if ("VALIDATED".equals(status)) {
            if (bulk.path("failed_count").asInt(0) > 0) {
                rows.update(TABLE, id, rows.object().put("bulk_payment_id", bulkId).put("downstream_status", status));
                throw ApiException.unprocessable("bulk_lines_refused", bulk.path("failed_count").asInt()
                        + " line(s) were refused by the bank; cancel this batch and upload a corrected one");
            }
            JsonNode submitted = down.postAs(initiator, PAY, "/v1/bulk-payments/" + bulkId + "/submit", null,
                    "bzp-submit:" + id);
            status = submitted.path("status").asString(status);
        }
        if ("PENDING_APPROVAL".equals(status)) {
            status = relayApprovals(id, "/v1/bulk-payments/" + bulkId, approvers, status);
        }
        return c.put("downstream_status", status);
    }

    /** Relays the business approvers' decisions as payment-service approvals, one per approver, until done. */
    private String relayApprovals(UUID id, String path, List<UUID> approvers, String status) {
        String current = status;
        for (UUID approver : approvers) {
            if (!"PENDING_APPROVAL".equals(current)) {
                break;
            }
            try {
                JsonNode r = down.postAs(approver, PAY, path + "/approve",
                        rows.object().put("comment", "Approved in the business portal (business payment " + id + ")"),
                        "bzp-approve:" + id + ":" + approver);
                current = r.path("status").asString(current);
            } catch (ApiException e) {
                if (!Downstream.rejected(e)) {
                    throw e;
                }
                log.info("payment-service did not take approval of {} for business payment {}: {}", approver, id, e.code());
            }
        }
        if ("PENDING_APPROVAL".equals(current)) {
            try {
                current = down.get(PAY, path).path("status").asString(current);
            } catch (ApiException e) {
                // keep what we know
            }
        }
        return current;
    }

    /** The payment as payment-service gets it, rebuilt from the stored business payment only. */
    ObjectNode paymentBody(UUID id, ObjectNode p, UUID initiator, List<UUID> approvers) {
        String kind = p.path("kind").asString();
        ObjectNode b = rows.object()
                .put("channel", "WEB")
                .put("priority", "NORMAL")
                .put("customer_id", business().toString())
                .put("debtor_account_id", p.path("debtor_account_id").asString())
                .put("amount", plain(Req.rowDecimal(p, "amount")))
                .put("currency", p.path("currency").asString());
        put(b, "reference", Req.text(p, "reference"));
        put(b, "remittance_unstructured", Req.text(p, "remittance"));
        put(b, "purpose_code", Req.text(p, "purpose_code"));
        put(b, "requested_execution_date", Req.text(p, "requested_execution_date"));
        switch (kind) {
            case "OWN_TRANSFER" -> b.put("payment_type", "INTERNAL_TRANSFER").put("direction", "INTERNAL")
                    .put("creditor_account_id", p.path("creditor_account_id").asString());
            case "DOMESTIC" -> b.put("payment_type", "DOMESTIC").put("direction", "OUTBOUND")
                    .put("payee_id", p.path("payee_id").asString());
            default -> b.put("payment_type", "CROSS_BORDER").put("direction", "OUTBOUND")
                    .put("payee_id", p.path("payee_id").asString())
                    .put("fx_quote_id", p.path("fx_quote_id").asString())
                    .put("instructed_currency", p.path("instructed_currency").asString())
                    .put("instructed_amount", plain(Req.rowDecimal(p, "instructed_amount")));
        }
        put(b, "creditor_name", Req.text(p, "creditor_name"));
        ObjectNode meta = b.putObject("metadata");
        meta.put("business_payment_id", id.toString());
        meta.put("initiated_by", initiator.toString());
        tools.jackson.databind.node.ArrayNode approvedBy = meta.putArray("approved_by");
        approvers.forEach(u -> approvedBy.add(u.toString()));
        return b;
    }

    private List<UUID> approvers(UUID id) {
        List<UUID> out = new ArrayList<>();
        for (ObjectNode a : rows.list("select row_to_json(t)::text from business_payment_approval t where "
                + "t.business_payment_id = ? and t.business_customer_id = ? and t.decision = 'APPROVED' order by t.sequence",
                id, business())) {
            out.add(UUID.fromString(a.path("business_user_id").asString()));
        }
        return out;
    }

    private ObjectNode failed(UUID id, String code, String message) {
        String text = (code + ": " + (message == null ? "" : message));
        audit.failed("businessPaymentRelease", "PAYMENT", id, code);
        return rows.update(TABLE, id, rows.object().put("status", "RELEASE_FAILED")
                .put("release_error", text.length() > 255 ? text.substring(0, 255) : text));
    }

    /** Amounts as payment-service expects them (no trailing zeros beyond the stored scale, e.g. 5000.00000000 -> 5000). */
    static String plain(java.math.BigDecimal d) {
        java.math.BigDecimal s = d.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    private static void put(ObjectNode b, String field, String value) {
        if (value != null && !value.isBlank()) {
            b.put(field, value);
        }
    }
}
