package io.meridian.archive.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on purge requests (G11). Approval runs the same four-eyes and lifecycle checks
 * as the direct endpoint, with the deciding approver as principal. A decision for a purge that changed since the
 * submission (row_version) or is no longer PENDING_APPROVAL is ignored.
 */
@Component
public class PurgeApprovalHandler implements ApprovalOutcomeHandler {
    private final PurgeService purges;

    public PurgeApprovalHandler(PurgeService purges) {
        this.purges = purges;
    }

    @Override
    public String resourceType() {
        return PurgeApprovals.RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        Integer rowVersion = d.payload().path("row_version").isNumber() ? d.payload().get("row_version").asInt() : null;
        if (d.resourceId() == null || !purges.awaitsApproval(d.resourceId(), rowVersion)) {
            return; // decided directly, cancelled, blocked by a hold or resubmitted meanwhile
        }
        switch (d.outcome()) {
            case APPROVED -> purges.approve(d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> purges.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
