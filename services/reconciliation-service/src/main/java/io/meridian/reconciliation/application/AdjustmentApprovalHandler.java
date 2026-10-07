package io.meridian.reconciliation.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on adjustment / write-off requests (G11): approval posts and closes the break
 * through the same four-eyes check as POST /approve (the deciding approver is the principal); rejection or a change
 * request rejects it. Decisions for requests no longer pending are ignored.
 */
@Component
public class AdjustmentApprovalHandler implements ApprovalOutcomeHandler {
    private final AdjustmentService adjustments;

    public AdjustmentApprovalHandler(AdjustmentService adjustments) {
        this.adjustments = adjustments;
    }

    @Override
    public String resourceType() {
        return AdjustmentService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || !adjustments.awaitsApproval(d.resourceId())) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> adjustments.approve(d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> adjustments.reject(d.resourceId(),
                    "Rejected in workflow request " + d.approvalRequestId());
            default -> { } // EXPIRED / CANCELLED: stays pending
        }
    }
}
