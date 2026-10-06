package io.meridian.disbursement.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on submitted disbursement batches (G11).
 *
 * <p>Multi-level approval maps to <b>one workflow request per level</b> (action {@code approve-level-<n>}): submit
 * opens level 1, and each non-final approval (from the inbox or the direct endpoint) opens the next level. A decision
 * calls the same {@link BatchService#approve} / {@link BatchService#reject} as the endpoints with the deciding
 * approver as principal, so the existing semantics are unchanged: the submitter can never decide, every level needs
 * an approver who has not decided on the batch before, each level is recorded in {@code disbursement_batch_approval},
 * and the final level checks the funding and executes. A single request whose levels were run inside workflow-service
 * would only report the final decision, losing the per-level distinct-approver check and audit rows here.
 *
 * <p>A decision is applied only while the batch still waits for exactly that level of the same submission
 * ({@code submitted_at}); a redelivered or stale decision (level already decided, batch rejected/cancelled) is
 * ignored. A final approval refused by the service (e.g. insufficient funding) leaves the batch pending at that level
 * for a direct decision.
 */
@Component
public class BatchApprovalHandler implements ApprovalOutcomeHandler {
    private final BatchService batches;

    public BatchApprovalHandler(BatchService batches) {
        this.batches = batches;
    }

    @Override
    public String resourceType() {
        return BatchService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        int level = d.payload().path("level").asInt(0);
        String submittedAt = d.payload().path("submitted_at").asString(null);
        if (d.resourceId() == null || level < 1 || !BatchService.levelAction(level).equals(d.action())
                || !batches.awaitsLevel(d.resourceId(), submittedAt, level)) {
            return; // level already decided, batch decided directly or cancelled: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> batches.approve(d.resourceId(),
                    "level " + level + " approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> batches.reject(d.resourceId(),
                    "rejected at level " + level + " in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL at this level; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
