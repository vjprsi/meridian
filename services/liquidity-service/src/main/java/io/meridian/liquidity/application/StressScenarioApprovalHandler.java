package io.meridian.liquidity.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on liquidity stress scenarios (G11). Approval runs the same four-eyes check as
 * the direct endpoint, with the deciding approver as principal. A decision for content that changed since
 * submission (row_version) or for an already approved scenario is ignored. Scenarios have no local reject: a
 * rejection leaves the scenario unapproved (it cannot be run) until it is reworked and approved.
 */
@Component
public class StressScenarioApprovalHandler implements ApprovalOutcomeHandler {
    private final StressService stress;

    public StressScenarioApprovalHandler(StressService stress) {
        this.stress = stress;
    }

    @Override
    public String resourceType() {
        return StressService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        Integer version = d.payload().hasNonNull("row_version") ? d.payload().get("row_version").asInt() : null;
        if (d.resourceId() == null || !stress.awaitsApproval(d.resourceId(), version)) {
            return; // approved directly or reworked meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> stress.approve(d.resourceId());
            case REJECTED, CHANGES_REQUESTED, EXPIRED, CANCELLED -> { } // stays unapproved
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
