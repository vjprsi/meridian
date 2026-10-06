package io.meridian.aiplatform.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on submitted model versions (G11). Approval runs the same lifecycle and
 * four-eyes checks as the direct endpoint, with the deciding approver as principal. A decision for an earlier
 * submission (different {@code submitted_at}) or a version already decided is ignored.
 */
@Component
public class ModelVersionApprovalHandler implements ApprovalOutcomeHandler {
    private final ModelService models;

    public ModelVersionApprovalHandler(ModelService models) {
        this.models = models;
    }

    @Override
    public String resourceType() {
        return ModelService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String submittedAt = d.payload().path("submitted_at").asString(null);
        if (d.resourceId() == null || !models.awaitsApproval(d.resourceId(), submittedAt)) {
            return; // decided directly, resubmitted or reworked meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> models.approve(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> models.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can be decided directly or resubmitted
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
