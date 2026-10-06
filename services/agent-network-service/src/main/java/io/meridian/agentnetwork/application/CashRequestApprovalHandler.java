package io.meridian.agentnetwork.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on cash-in-transit requests (G11). Approval runs the same four-eyes check as the
 * direct endpoint with the deciding approver as principal; a rejection cancels the request (the request API has no
 * separate reject action). A decision for content changed since submission (row_version) is ignored.
 */
@Component
public class CashRequestApprovalHandler implements ApprovalOutcomeHandler {
    private final ComplianceService compliance;

    public CashRequestApprovalHandler(ComplianceService compliance) {
        this.compliance = compliance;
    }

    @Override
    public String resourceType() {
        return ComplianceService.CASH_APPROVAL;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        Integer version = d.payload().hasNonNull("row_version") ? d.payload().get("row_version").asInt() : null;
        if (d.resourceId() == null || !compliance.cashAwaitsApproval(d.resourceId(), version)) {
            return; // decided directly, cancelled or changed meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> compliance.approveCash(d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> compliance.cancelCash(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays REQUESTED
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
