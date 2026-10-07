package io.meridian.merchant.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on calculated merchant settlements (G11). Approval runs the same
 * {@link SettlementService#approve} as the direct endpoint with the approver as principal (the calculating user still
 * cannot approve). A rejection (or changes requested) rejects it like {@code POST /merchant-settlements/{id}/reject}
 * (REJECTED; the period can be settled again). Settlements no longer CALCULATED, or whose net amount differs from the
 * submission, are left alone.
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
        if (d.resourceId() == null
                || !settlements.awaitsApproval(d.resourceId(), d.payload().path("net_amount").asString(null))) {
            return; // approved directly or changed meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> settlements.approve(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> settlements.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays CALCULATED; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
