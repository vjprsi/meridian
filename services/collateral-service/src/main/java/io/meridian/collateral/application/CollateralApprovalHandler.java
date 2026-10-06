package io.meridian.collateral.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on collateral verification (G11). Approval runs the same checks as the direct
 * verify endpoint (four-eyes against the registering user, complete attributes, a valuation) with the deciding
 * approver as principal. A decision for a version that changed since submission (row_version) is ignored.
 * CHANGES_REQUESTED leaves the collateral PROPOSED (rejection is terminal for collateral); the next valuation or
 * PATCH resubmits it.
 */
@Component
public class CollateralApprovalHandler implements ApprovalOutcomeHandler {
    private final CollateralService collaterals;

    public CollateralApprovalHandler(CollateralService collaterals) {
        this.collaterals = collaterals;
    }

    @Override
    public String resourceType() {
        return CollateralService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        Integer rowVersion = d.payload().hasNonNull("row_version") ? d.payload().get("row_version").asInt() : null;
        if (d.resourceId() == null || !collaterals.awaitsVerification(d.resourceId(), rowVersion)) {
            return; // decided directly, changed or resubmitted meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> collaterals.verify(d.resourceId());
            case REJECTED -> collaterals.reject(d.resourceId(), "rejected in workflow request " + d.approvalRequestId());
            case CHANGES_REQUESTED, EXPIRED, CANCELLED -> { } // stays PROPOSED; rework resubmits, or decide directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
