package io.meridian.merchantchannel.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import org.springframework.stereotype.Component;

/**
 * The bank's review of payout account changes approved inside a merchant ({@code merchant_payout_change}, central only:
 * bank checkers decide in workflow-service; the merchant channel has no bank-side endpoint). APPROVED applies the new
 * account to merchant-service as the deciding checker; REJECTED / CHANGES_REQUESTED reject the change; EXPIRED /
 * CANCELLED leave it waiting. Only a change waiting for this very request is touched (redeliveries are harmless).
 */
@Component
public class PayoutChangeApprovalHandler implements ApprovalOutcomeHandler {
    private final PayoutService payouts;

    public PayoutChangeApprovalHandler(PayoutService payouts) {
        this.payouts = payouts;
    }

    @Override
    public String resourceType() {
        return PayoutService.APPROVAL_RESOURCE;
    }

    @Override
    public boolean centralOnly() {
        return true;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> payouts.bankApproved(d.resourceId(), d.approvalRequestId(), d.decidedBy());
            case REJECTED, CHANGES_REQUESTED -> payouts.bankRejected(d.resourceId(), d.approvalRequestId(), d.decidedBy());
            default -> { } // EXPIRED / CANCELLED: stays PENDING_BANK_APPROVAL
        }
    }
}
