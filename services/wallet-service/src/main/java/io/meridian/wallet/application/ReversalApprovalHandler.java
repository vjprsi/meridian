package io.meridian.wallet.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on requested wallet transaction reversals (G11). Approval runs the same
 * {@link TransactionEngine#reverse} as the direct endpoint with the deciding approver as principal, so the four-eyes
 * rule (checker differs from the reversal maker) still applies. A decision for a request that was decided directly,
 * declined or replaced by a later request (different requested_at / amount fingerprint) is ignored.
 */
@Component
public class ReversalApprovalHandler implements ApprovalOutcomeHandler {
    private final TransactionEngine engine;

    public ReversalApprovalHandler(TransactionEngine engine) {
        this.engine = engine;
    }

    @Override
    public String resourceType() {
        return TransactionEngine.REVERSAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String requestedAt = d.payload().path("requested_at").asString(null);
        String amount = d.payload().path("reversal_amount").asString(null);
        if (d.resourceId() == null || !"reverse".equals(d.action())
                || !engine.awaitsReversal(d.resourceId(), requestedAt, amount == null ? null : new BigDecimal(amount))) {
            return; // decided directly, declined or re-requested meanwhile: nothing to apply
        }
        String reason = d.payload().path("reason").asString(null);
        switch (d.outcome()) {
            case APPROVED -> engine.reverse(d.resourceId(),
                    reason != null ? reason : "approved in workflow request " + d.approvalRequestId(), null);
            case REJECTED, CHANGES_REQUESTED -> engine.declineReversal(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // the request stays open; it can still be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
