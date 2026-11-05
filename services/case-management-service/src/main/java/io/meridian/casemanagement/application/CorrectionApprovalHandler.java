package io.meridian.casemanagement.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * WE-OPS-1: applies workflow-service decisions on wallet transaction corrections (G11 maker-checker), mirroring
 * {@link DispositionApprovalHandler}. Approval runs the same four-eyes check as the direct endpoint (the checker is
 * the principal) and executes the reversal; rejection records it with no money movement. A decision for a correction
 * that was decided directly meanwhile, or a re-delivered one, is ignored.
 */
@Component
public class CorrectionApprovalHandler implements ApprovalOutcomeHandler {
    private final CorrectionService corrections;

    public CorrectionApprovalHandler(CorrectionService corrections) {
        this.corrections = corrections;
    }

    @Override
    public String resourceType() {
        return CorrectionService.CORRECTION_APPROVAL;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || !corrections.awaitsApproval(d.resourceId())) {
            return; // decided directly or already applied: nothing to do
        }
        switch (d.outcome()) {
            case APPROVED -> corrections.approve(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> corrections.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays pending; can still be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
