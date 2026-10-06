package io.meridian.limits.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on requested limit overrides (G11). Approval runs the same four-eyes and
 * validity checks as the direct endpoint, with the deciding approver as principal. A decision for an override that
 * was decided directly meanwhile, or that belongs to another approval request, is ignored.
 */
@Component
public class LimitOverrideApprovalHandler implements ApprovalOutcomeHandler {
    private final LimitAdminService admin;

    public LimitOverrideApprovalHandler(LimitAdminService admin) {
        this.admin = admin;
    }

    @Override
    public String resourceType() {
        return LimitAdminService.OVERRIDE_APPROVAL;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || !admin.overrideAwaitsApproval(d.resourceId(), d.approvalRequestId())) {
            return; // decided directly or already applied (redelivery): nothing to do
        }
        switch (d.outcome()) {
            case APPROVED -> admin.approveOverride(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> admin.rejectOverride(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can still be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
