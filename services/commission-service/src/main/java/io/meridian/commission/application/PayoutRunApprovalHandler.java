package io.meridian.commission.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on commission payout runs (G11). Approval runs the same four-eyes check as the
 * direct endpoint (approver differs from the run's maker) and then executes the payouts; a rejection cancels the run
 * (its earnings stay unpaid for a later run). A decision for a run no longer awaiting approval, or whose previewed
 * total changed, is ignored.
 */
@Component
public class PayoutRunApprovalHandler implements ApprovalOutcomeHandler {
    private final PayoutService payouts;

    public PayoutRunApprovalHandler(PayoutService payouts) {
        this.payouts = payouts;
    }

    @Override
    public String resourceType() {
        return PayoutService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String total = d.payload().path("total_amount").asString(null);
        if (d.resourceId() == null || !payouts.awaitsApproval(d.resourceId(), total)) {
            return; // approved, cancelled or already executed meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> payouts.approve(d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> payouts.cancel(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
