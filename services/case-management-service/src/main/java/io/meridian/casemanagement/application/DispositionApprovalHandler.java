package io.meridian.casemanagement.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on proposed case dispositions (G11). Approval runs the same four-eyes check as
 * the direct endpoint (the MLRO is the principal) and closes the case; rejection returns it to investigation. A
 * decision for a disposition that was decided directly meanwhile, or a redelivered one, is ignored.
 */
@Component
public class DispositionApprovalHandler implements ApprovalOutcomeHandler {
    private final CaseService cases;

    public DispositionApprovalHandler(CaseService cases) {
        this.cases = cases;
    }

    @Override
    public String resourceType() {
        return CaseService.DISPOSITION_APPROVAL;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || !cases.dispositionAwaitsApproval(d.resourceId())) {
            return; // decided directly or already applied: nothing to do
        }
        switch (d.outcome()) {
            case APPROVED -> cases.approveDisposition(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> cases.rejectDisposition(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can still be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
