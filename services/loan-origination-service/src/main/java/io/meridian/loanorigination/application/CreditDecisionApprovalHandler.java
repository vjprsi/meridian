package io.meridian.loanorigination.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on manual credit decision requests (G11). Approval runs the same four-eyes and
 * application-state checks as the direct endpoint, with the deciding approver as principal. A decision for a request
 * that was decided directly, cancelled (withdrawal, expiry) or differs from the submitted outcome is ignored.
 */
@Component
public class CreditDecisionApprovalHandler implements ApprovalOutcomeHandler {
    private final DecisionService decisions;

    public CreditDecisionApprovalHandler(DecisionService decisions) {
        this.decisions = decisions;
    }

    @Override
    public String resourceType() {
        return DecisionService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String outcome = d.payload().path("outcome").asString(null);
        if (d.resourceId() == null || !decisions.awaitsApproval(d.resourceId(), outcome)) {
            return; // decided directly, cancelled or superseded meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> decisions.approve(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> decisions.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
