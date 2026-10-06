package io.meridian.payment.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on bulk payments pending approval (G11): approval runs the same rules as
 * {@code POST /bulk-payments/{id}/approve} (four-eyes against the submitter) with the deciding approver as principal
 * and releases the batch once all levels are approved; rejection is {@code POST /bulk-payments/{id}/reject}. Stale
 * or redelivered decisions are ignored.
 */
@Component
public class BulkPaymentApprovalHandler implements ApprovalOutcomeHandler {
    private final ApprovalService approvals;
    private final BulkService bulk;
    private final PaymentApprovalRequests central;

    public BulkPaymentApprovalHandler(ApprovalService approvals, BulkService bulk, PaymentApprovalRequests central) {
        this.approvals = approvals;
        this.bulk = bulk;
        this.central = central;
    }

    @Override
    public String resourceType() {
        return PaymentApprovalRequests.BULK;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || !central.awaits(PaymentApprovalRequests.BULK, d.resourceId(), d.payload())) {
            return; // decided directly, progressed or redelivered: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> approvals.approveBulk(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> bulk.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can still be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
