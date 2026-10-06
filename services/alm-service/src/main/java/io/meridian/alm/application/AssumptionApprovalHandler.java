package io.meridian.alm.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on submitted behavioural assumptions (G11). Approval runs the same four-eyes
 * check as the direct endpoint, with the deciding approver as principal. A decision for content that changed since
 * submission (row_version) or an assumption already approved is ignored. Assumptions have no rejected state: a
 * rejection leaves the assumption unapproved (never used by gap, FTP or sensitivity runs) for the maker to rework.
 */
@Component
public class AssumptionApprovalHandler implements ApprovalOutcomeHandler {
    private final AssumptionService assumptions;

    public AssumptionApprovalHandler(AssumptionService assumptions) {
        this.assumptions = assumptions;
    }

    @Override
    public String resourceType() {
        return AssumptionService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        Integer rowVersion = d.payload().hasNonNull("row_version") ? d.payload().get("row_version").asInt() : null;
        if (d.resourceId() == null || !assumptions.awaitsApproval(d.resourceId(), rowVersion)) {
            return; // approved directly or reworked meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> assumptions.approve(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED, EXPIRED, CANCELLED -> { } // stays unapproved; rework (PATCH) resubmits
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
