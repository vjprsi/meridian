package io.meridian.commission.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on submitted commission plans (G11). The plan's own lifecycle rules still apply:
 * approval runs the same four-eyes check as the direct endpoint, with the deciding approver as principal. A decision
 * for an earlier submission (the plan was decided directly, or rejected and resubmitted meanwhile) is ignored.
 */
@Component
public class PlanApprovalHandler implements ApprovalOutcomeHandler {
    private final PlanService plans;

    public PlanApprovalHandler(PlanService plans) {
        this.plans = plans;
    }

    @Override
    public String resourceType() {
        return PlanService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String submittedAt = d.payload().path("submitted_at").asString(null);
        if (d.resourceId() == null || !plans.awaitsApproval(d.resourceId(), submittedAt)) {
            return; // decided directly or resubmitted meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> plans.approve(d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> plans.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
