package io.meridian.collections.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on requests to litigate (G11). Approval runs the same four-eyes rule as the
 * direct endpoint, with the deciding approver as principal. A decision for a proceeding no longer pending approval,
 * or whose claim changed since the request, is ignored.
 */
@Component
public class LegalProceedingApprovalHandler implements ApprovalOutcomeHandler {
    private final LegalService legal;

    public LegalProceedingApprovalHandler(LegalService legal) {
        this.legal = legal;
    }

    @Override
    public String resourceType() {
        return LegalService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String claim = d.payload().path("claim_amount").asString(null);
        if (d.resourceId() == null || !legal.awaitsApproval(d.resourceId(), claim)) {
            return; // decided directly or already applied: nothing to do
        }
        switch (d.outcome()) {
            case APPROVED -> legal.approve(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> legal.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
