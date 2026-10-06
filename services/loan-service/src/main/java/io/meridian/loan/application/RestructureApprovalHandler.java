package io.meridian.loan.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on submitted loan restructures (G11). Approval runs the same lifecycle and
 * four-eyes checks as the direct endpoint, with the deciding approver as principal; applying the approved
 * restructure stays an explicit step. A decision for a request the restructure no longer waits for is ignored.
 */
@Component
public class RestructureApprovalHandler implements ApprovalOutcomeHandler {
    private final RestructureService restructures;

    public RestructureApprovalHandler(RestructureService restructures) {
        this.restructures = restructures;
    }

    @Override
    public String resourceType() {
        return RestructureService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String submittedBy = d.payload().path("submitted_by").asString(null);
        if (d.resourceId() == null || !restructures.awaitsApproval(d.resourceId(), d.approvalRequestId(), submittedBy)) {
            return; // decided directly or no longer pending: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> restructures.approve(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> restructures.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can still be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
