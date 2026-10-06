package io.meridian.merchant.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on requested merchant refunds (G11). Approval runs the same
 * {@link RefundService#approve} as the direct endpoint with the deciding approver as principal, so the requester still
 * cannot approve their own refund. A decision for a refund no longer REQUESTED (decided directly, failed) or whose
 * amount / payment differs from the submission is ignored.
 */
@Component
public class RefundApprovalHandler implements ApprovalOutcomeHandler {
    private final RefundService refunds;

    public RefundApprovalHandler(RefundService refunds) {
        this.refunds = refunds;
    }

    @Override
    public String resourceType() {
        return RefundService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || !refunds.awaitsApproval(d.resourceId(), d.payload().path("amount").asString(null),
                d.payload().path("original_transaction_id").asString(null))) {
            return; // decided directly or changed meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> refunds.approve(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> refunds.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays REQUESTED; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
