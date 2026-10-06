package io.meridian.regulatoryreporting.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on report adjustments (G11). Approval and rejection run the same four-eyes and
 * run checks as the direct endpoints, with the deciding approver as principal. A decision for an adjustment that is
 * no longer pending (or whose value changed) is ignored.
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
        String value = d.payload().path("adjusted_value").asString(null);
        Integer rowVersion = d.payload().hasNonNull("row_version") ? d.payload().get("row_version").asInt() : null;
        if (d.resourceId() == null || !adjustments.awaitsApproval(d.resourceId(), value, rowVersion)) {
            return; // decided directly meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> adjustments.approve(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> adjustments.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
