package io.meridian.agentnetwork.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * WE-FLOAT-1: applies workflow-service decisions on unclaimed-settlement runs. Approval runs the four-eyes check and
 * posts the aging journals with the deciding approver as principal; an unknown (5xx) outcome is surfaced so the
 * listener retries (already-posted stages are skipped).
 */
@Component
public class UnclaimedSettlementApprovalHandler implements ApprovalOutcomeHandler {
    private final UnclaimedSettlementService settlements;

    public UnclaimedSettlementApprovalHandler(UnclaimedSettlementService settlements) {
        this.settlements = settlements;
    }

    @Override
    public String resourceType() {
        return UnclaimedSettlementService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || !settlements.awaitsApproval(d.resourceId())) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> {
                try {
                    settlements.approve(d.resourceId());
                } catch (ApiException e) {
                    if (e.status() >= 500) {
                        throw new IllegalStateException("Unclaimed settlement outcome unknown: " + e.getMessage(), e);
                    }
                    throw e;
                }
            }
            case REJECTED, CHANGES_REQUESTED -> settlements.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { }
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
