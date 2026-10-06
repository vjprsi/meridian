package io.meridian.casemanagement.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service MLRO decisions on SAR / STR reports submitted for approval (G11). Approval runs the same
 * four-eyes check as the direct endpoint (the MLRO is the principal). The report lifecycle has no MLRO rejection
 * (SarStatus REJECTED is the FIU's rejection), so a rejection in the inbox leaves the report PENDING_APPROVAL for the
 * MLRO to follow up in case management. Decisions for an earlier submission (row_version changed) are ignored.
 */
@Component
public class SarApprovalHandler implements ApprovalOutcomeHandler {
    private static final Logger log = LoggerFactory.getLogger(SarApprovalHandler.class);

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
            case REJECTED, CHANGES_REQUESTED -> log.warn(
                    "SAR {} was {} in workflow request {}; the report stays PENDING_APPROVAL (no MLRO rejection in its lifecycle)",
                    d.resourceId(), d.outcome(), d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can still be approved directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
