package io.meridian.payment.application;

import static io.meridian.payment.infrastructure.Rows.integer;
import static io.meridian.payment.infrastructure.Rows.str;
import static io.meridian.payment.infrastructure.Rows.ts;
import static io.meridian.payment.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.payment.domain.PaymentLifecycle.Reason;
import io.meridian.payment.infrastructure.Rows;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Multi-level business approvals (maker-checker) for payments and bulk payments. Levels are decided in order; each
 * needs its number of distinct approvers, none of whom may be the initiator / submitter. A rejection at any level
 * rejects the payment. Expired approval windows cannot be decided.
 */
@Service
public class ApprovalService {
    private static final String TABLE = "payment_approval";

    private record Decision(ObjectNode approval, boolean levelDone, boolean allDone, boolean rejected, String error,
                            String errorDetail) {}

    private final Rows rows;
    private final Events events;
    private final PaymentProcessor processor;
    private final PaymentRecords records;
    private final BulkService bulk;
    private final TransactionTemplate tx;

    public ApprovalService(Rows rows, Events events, PaymentProcessor processor, PaymentRecords records,
                           BulkService bulk, TransactionTemplate tx) {
        this.rows = rows;
        this.events = events;
        this.processor = processor;
        this.records = records;
        this.bulk = bulk;
        this.tx = tx;
    }

    /** Approves the current level of a payment; returns the payment. */
    public ObjectNode approvePayment(UUID paymentId, String comment) {
        ObjectNode p = records.get(paymentId);
        if (!"PENDING_APPROVAL".equals(str(p, "status"))) {
            throw ApiException.conflict("invalid_transition", "Payment is not pending approval (status " + str(p, "status") + ")");
        }
        UUID level = currentLevel("payment_id", paymentId);
        decide(level, true, comment);
        return records.get(paymentId);
    }

    public ObjectNode rejectPayment(UUID paymentId, String reason) {
        ObjectNode p = records.get(paymentId);
        if (!"PENDING_APPROVAL".equals(str(p, "status"))) {
            throw ApiException.conflict("invalid_transition", "Payment is not pending approval (status " + str(p, "status") + ")");
        }
        decide(currentLevel("payment_id", paymentId), false, reason);
        return records.get(paymentId);
    }

    public ObjectNode approveBulk(UUID bulkId, String comment) {
        ObjectNode b = rows.get("bulk_payment", bulkId);
        if (!"PENDING_APPROVAL".equals(str(b, "status"))) {
            throw ApiException.conflict("invalid_transition", "Bulk payment is not pending approval (status " + str(b, "status") + ")");
        }
        decide(currentLevel("bulk_payment_id", bulkId), true, comment);
        return rows.get("bulk_payment", bulkId);
    }

    private UUID currentLevel(String column, UUID id) {
        return rows.scalar("select id from payment_approval where " + column + " = ? and status = 'PENDING' "
                        + "order by approver_level limit 1", UUID.class, id)
                .orElseThrow(() -> ApiException.conflict("no_pending_approval", "There is no pending approval level"));
    }

    /** Approves or rejects one approval level; returns the approval row. */
    public ObjectNode decide(UUID approvalId, boolean approve, String comment) {
        Decision d = tx.execute(s -> decideInTx(approvalId, approve, comment));
        if (d.error() != null) {
            throw "approval_expired".equals(d.error()) ? ApiException.conflict(d.error(), d.errorDetail())
                    : ApiException.unprocessable(d.error(), d.errorDetail());
        }
        UUID paymentId = uuid(d.approval(), "payment_id");
        UUID bulkId = uuid(d.approval(), "bulk_payment_id");
        if (d.allDone()) {
            if (paymentId != null) {
                processor.approvalsComplete(paymentId);
            } else if (bulkId != null) {
                bulk.approvalsComplete(bulkId);
            }
        }
        return rows.get(TABLE, approvalId);
    }

    private Decision decideInTx(UUID approvalId, boolean approve, String comment) {
        ObjectNode a = rows.lock(TABLE, approvalId);
        if (!"PENDING".equals(str(a, "status"))) {
            throw ApiException.conflict("approval_already_decided", "This approval level is already " + str(a, "status"));
        }
        UUID paymentId = uuid(a, "payment_id");
        UUID bulkId = uuid(a, "bulk_payment_id");
        String column = paymentId != null ? "payment_id" : "bulk_payment_id";
        UUID target = paymentId != null ? paymentId : bulkId;
        if (ts(a, "expires_at") != null && ts(a, "expires_at").isBefore(rows.now())) {
            ObjectNode expired = rows.update(TABLE, approvalId, rows.object().put("status", "EXPIRED"));
            return new Decision(expired, false, false, false, "approval_expired", "The approval window has expired");
        }
        Optional<Integer> lower = rows.scalar("select approver_level from payment_approval where " + column
                + " = ? and status = 'PENDING' and approver_level < ? limit 1", Integer.class, target,
                integer(a, "approver_level"));
        if (lower.isPresent()) {
            throw ApiException.conflict("approval_out_of_order", "Level " + lower.get() + " must be decided first");
        }
        String principal = TenantContext.principal();
        ObjectNode subject = paymentId != null ? records.get(paymentId) : rows.get("bulk_payment", bulkId);
        String initiator = paymentId != null ? str(subject, "initiated_by") : str(subject, "submitted_by");
        if (principal.equals(initiator) || principal.equals(str(subject, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The initiator or submitter cannot approve their own " + (paymentId != null ? "payment" : "bulk payment"));
        }
        if (approve) {
            Optional<Integer> already = rows.scalar("select 1 from payment_approval where " + column
                    + " = ? and approved_by @> to_jsonb(?::text) limit 1", Integer.class, target, principal);
            if (already.isPresent()) {
                throw ApiException.unprocessable("duplicate_approver", principal + " has already approved this payment");
            }
        }
        ObjectNode c = rows.object();
        approverUser(principal).ifPresent(u -> c.put("approver_user_id", u));
        if (comment != null) {
            c.put("comment", comment.length() > 500 ? comment.substring(0, 500) : comment);
        }
        if (!approve) {
            c.put("status", "REJECTED");
            c.put("decided_at", rows.now().toString());
            c.put("decided_by", principal);
            ObjectNode rejected = rows.update(TABLE, approvalId, c);
            events.publish("payments.payment_approval.decided", TABLE, rejected);
            rows.execute("update payment_approval set status = 'REJECTED', decided_at = now(), comment = 'Rejected at level "
                    + integer(a, "approver_level") + "' where " + column + " = ? and status = 'PENDING'", target);
            if (paymentId != null) {
                processor.rejectLocked(records.lock(paymentId), Reason.NOT_AUTHORISED,
                        "Rejected by approver" + (comment == null ? "" : ": " + comment), null);
            } else {
                bulk.rejectLocked(bulkId, comment == null ? "Rejected by approver" : comment);
            }
            return new Decision(rejected, true, false, true, null, null);
        }
        ArrayNode approvedBy = a.path("approved_by").isArray() ? ((ArrayNode) a.get("approved_by")).deepCopy() : rows.array();
        approvedBy.add(principal);
        int received = integer(a, "approvals_received") + 1;
        c.set("approved_by", approvedBy);
        c.put("approvals_received", received);
        boolean levelDone = received >= integer(a, "required_approvals");
        if (levelDone) {
            c.put("status", "APPROVED");
            c.put("decided_at", rows.now().toString());
            c.put("decided_by", principal);
        }
        ObjectNode updated = rows.update(TABLE, approvalId, c);
        if (levelDone) {
            events.publish("payments.payment_approval.decided", TABLE, updated);
        }
        boolean allDone = levelDone && rows.scalar("select count(*) from payment_approval where " + column
                + " = ? and status <> 'APPROVED'", Long.class, target).orElse(0L) == 0;
        return new Decision(updated, levelDone, allDone, false, null, null);
    }

    private static Optional<String> approverUser(String principal) {
        try {
            return Optional.of(UUID.fromString(principal).toString());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Approval rows as JSON for the tracker. */
    public JsonNode levels(UUID paymentId) {
        ArrayNode out = rows.array();
        rows.query("select row_to_json(t)::text from payment_approval t where payment_id = ? order by approver_level", paymentId)
                .forEach(out::add);
        return out;
    }
}
