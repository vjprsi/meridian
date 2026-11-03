package io.meridian.commission.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * WE-PRICE-1 (PE-11): applies workflow-service decisions on submitted joining-commission configurations (SEC-03). The
 * configuration's own four-eyes check still runs with the deciding approver as principal; a decision for an earlier
 * submission (decided directly, or rejected and resubmitted meanwhile) is ignored.
 */
@Component
public class JoiningCommissionApprovalHandler implements ApprovalOutcomeHandler {
    private final JoiningCommissionService service;

    public JoiningCommissionApprovalHandler(JoiningCommissionService service) {
        this.service = service;
    }

    @Override
    public String resourceType() {
        return JoiningCommissionService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String submittedAt = d.payload().path("submitted_at").asString(null);
        if (d.resourceId() == null || !service.awaitsApproval(d.resourceId(), submittedAt)) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> service.approve(d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> service.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { }
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
