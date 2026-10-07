package io.meridian.casemanagement.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service MLRO decisions on SAR / STR reports submitted for approval (G11). Approval runs the same
 * four-eyes check as the direct endpoint (the MLRO is the principal). A rejection (or changes requested) in the inbox
 * returns the report to DRAFT for the preparer to rework, like {@code POST /sar-reports/{id}/return-to-draft}
 * (SarStatus REJECTED stays the FIU's rejection). Decisions for an earlier submission (row_version changed) are ignored.
 */
@Component
public class SarApprovalHandler implements ApprovalOutcomeHandler {
    private final SarService sars;

    public SarApprovalHandler(SarService sars) {
        this.sars = sars;
    }

    @Override
    public String resourceType() {
        return SarService.SAR_APPROVAL;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        Integer rowVersion = d.payload().hasNonNull("row_version") ? d.payload().get("row_version").asInt() : null;
        if (d.resourceId() == null || !sars.awaitsApproval(d.resourceId(), rowVersion)) {
            return; // approved directly, already applied or resubmitted meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> sars.approve(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> sars.returnToDraft(d.resourceId(),
                    (d.outcome() == ApprovalDecision.Outcome.REJECTED ? "rejected" : "changes requested")
                            + " in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can still be approved directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
