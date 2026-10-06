package io.meridian.transactionmonitoring.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on requested customer risk rating overrides (G11). Approval runs the same
 * four-eyes check as the direct endpoint, with the deciding approver as principal. A decision for an override that
 * was decided directly or replaced by a different override meanwhile is ignored.
 */
@Component
public class RatingOverrideApprovalHandler implements ApprovalOutcomeHandler {
    private final RatingService ratings;

    public RatingOverrideApprovalHandler(RatingService ratings) {
        this.ratings = ratings;
    }

    @Override
    public String resourceType() {
        return RatingService.OVERRIDE_APPROVAL;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || (d.action() != null && !RatingService.OVERRIDE_ACTION.equals(d.action()))) {
            return;
        }
        String fingerprint = d.payload().path("fingerprint").asString(null);
        if (!ratings.overrideAwaitsApproval(d.resourceId(), fingerprint)) {
            return; // decided directly, already applied (redelivery) or a different override is pending now
        }
        switch (d.outcome()) {
            case APPROVED -> ratings.approveOverride(d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> ratings.rejectOverride(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can still be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
