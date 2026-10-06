package io.meridian.payment.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on payments pending approval (G11). A decision counts as one approval of the
 * payment's current level, through the same rules as {@code POST /payments/{id}/approve} (four-eyes, distinct
 * approvers, level order, expiry) with the deciding approver as principal. A decision for an earlier approval step
 * (progress or amount changed, or the payment was decided directly) is ignored.
 */
@Component
public class PaymentApprovalHandler implements ApprovalOutcomeHandler {
    private final ApprovalService approvals;
    private final PaymentApprovalRequests central;

    public PaymentApprovalHandler(ApprovalService approvals, PaymentApprovalRequests central) {
        this.approvals = approvals;
        this.central = central;
    }

    @Override
    public String resourceType() {
        return PaymentApprovalRequests.PAYMENT;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || !central.awaits(PaymentApprovalRequests.PAYMENT, d.resourceId(), d.payload())) {
            return; // decided directly, progressed or redelivered: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> approvals.approvePayment(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> approvals.rejectPayment(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can still be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
