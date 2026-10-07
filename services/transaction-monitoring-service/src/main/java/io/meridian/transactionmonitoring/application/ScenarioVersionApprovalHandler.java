package io.meridian.transactionmonitoring.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on scenario version activation submissions (G11). Approval activates the version
 * on its scenario with the same four-eyes rule as {@code POST /scenarios/{id}/activate} (the approver is neither the
 * author nor the submitter); a rejection (or changes requested) rejects the submission and the version keeps its
 * status. Decisions for a replaced or already decided submission (approval_request_id no longer matches, or no
 * submission pending) are ignored.
 */
@Component
public class ScenarioVersionApprovalHandler implements ApprovalOutcomeHandler {
    private final ScenarioService scenarios;

    public ScenarioVersionApprovalHandler(ScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    @Override
    public String resourceType() {
        return ScenarioService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || !scenarios.awaitsApproval(d.resourceId(), d.approvalRequestId())) {
            return; // decided directly, resubmitted or lapsed meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> scenarios.activateVersion(d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> scenarios.rejectVersion(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays submitted; can be decided directly or resubmitted
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
