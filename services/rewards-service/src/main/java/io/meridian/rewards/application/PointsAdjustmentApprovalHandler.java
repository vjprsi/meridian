package io.meridian.rewards.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Applies workflow-service decisions on manual points adjustments (G11). The adjustment's own rules still apply:
 * approval runs the same four-eyes and account checks as the direct endpoint, with the deciding approver as
 * principal. A decision for an adjustment no longer pending (or with different points) is ignored.
 */
@Component
public class PointsAdjustmentApprovalHandler implements ApprovalOutcomeHandler {
    private final AccountService accounts;

    public PointsAdjustmentApprovalHandler(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public String resourceType() {
        return AccountService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        JsonNode p = d.payload().path("points");
        Long points = p.isNumber() ? p.asLong() : null;
        if (d.resourceId() == null || !accounts.awaitsApproval(d.resourceId(), points)) {
            return; // decided directly or already applied: nothing to do
        }
        switch (d.outcome()) {
            case APPROVED -> accounts.approveAdjustment(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> accounts.rejectAdjustment(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
