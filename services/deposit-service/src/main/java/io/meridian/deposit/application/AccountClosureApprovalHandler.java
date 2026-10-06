package io.meridian.deposit.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on account closures (G11). Approval runs the same four-eyes check and
 * settlement as the direct endpoint, with the deciding approver as principal; a rejection restores the account.
 * A decision for a closure no longer pending (decided directly, cancelled, redelivered) is ignored.
 */
@Component
public class AccountClosureApprovalHandler implements ApprovalOutcomeHandler {
    private final ClosureService closures;

    public AccountClosureApprovalHandler(ClosureService closures) {
        this.closures = closures;
    }

    @Override
    public String resourceType() {
        return ClosureService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || !closures.awaitsApproval(d.resourceId())) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> closures.approve(d.resourceId(), "Approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> closures.reject(d.resourceId(),
                    "Rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
