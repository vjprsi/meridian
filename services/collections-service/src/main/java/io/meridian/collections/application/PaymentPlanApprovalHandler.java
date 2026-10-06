package io.meridian.collections.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on payment plans outside policy (G11). Approval activates the plan through the
 * same method as the direct endpoint, so the four-eyes rule still applies with the deciding approver as principal;
 * a rejection cancels the proposed plan (the plan has no separate rejected state). A decision for a plan no longer
 * proposed, or whose terms differ from the submitted ones, is ignored.
 */
@Component
public class PaymentPlanApprovalHandler implements ApprovalOutcomeHandler {
    private final ArrangementService arrangements;

    public PaymentPlanApprovalHandler(ArrangementService arrangements) {
        this.arrangements = arrangements;
    }

    @Override
    public String resourceType() {
        return ArrangementService.PLAN_APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || !arrangements.planAwaitsApproval(d.resourceId(), d.payload())) {
            return; // activated, cancelled or already applied: nothing to do
        }
        switch (d.outcome()) {
            case APPROVED -> arrangements.activatePlan(d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> arrangements.cancelPlan(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PROPOSED; can be activated directly by a second user
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
