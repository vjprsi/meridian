package io.meridian.biller.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on calculated biller settlements (G11). Approval runs the same four-eyes check as
 * the direct endpoint (approver differs from the settlement's maker); a rejection (or changes requested) rejects it
 * like {@code POST /biller-settlements/{id}/reject} (REJECTED, its payments released for a new settlement). A decision
 * for a settlement no longer awaiting approval, or whose net amount changed, is ignored.
 */
@Component
public class SettlementApprovalHandler implements ApprovalOutcomeHandler {
    private final SettlementService settlements;

    public SettlementApprovalHandler(SettlementService settlements) {
        this.settlements = settlements;
    }

    @Override
    public String resourceType() {
        return SettlementService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String net = d.payload().path("net_amount").asString(null);
        if (d.resourceId() == null || !settlements.awaitsApproval(d.resourceId(), net)) {
            return; // approved directly or already paid: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> settlements.approve(d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> settlements.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays CALCULATED; can be approved directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
