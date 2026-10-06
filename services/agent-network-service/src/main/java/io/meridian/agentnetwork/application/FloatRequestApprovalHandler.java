package io.meridian.agentnetwork.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on float requests (G11). Approval runs the same four-eyes check and wallet-service
 * execution as the direct endpoint, with the deciding approver as principal; a decision for a request that is no
 * longer REQUESTED (decided directly, cancelled) or whose amount differs from the submission is ignored.
 */
@Component
public class FloatRequestApprovalHandler implements ApprovalOutcomeHandler {
    private final FloatService floats;

    public FloatRequestApprovalHandler(FloatService floats) {
        this.floats = floats;
    }

    @Override
    public String resourceType() {
        return FloatService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String amount = d.payload().path("amount").asString(null);
        if (d.resourceId() == null || !floats.awaitsApproval(d.resourceId(), amount == null ? null : new BigDecimal(amount))) {
            return; // decided directly, cancelled or already applied: nothing to do
        }
        switch (d.outcome()) {
            case APPROVED -> {
                try {
                    floats.approve(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
                } catch (ApiException e) {
                    if (e.status() >= 500) {
                        // wallet-service outcome unknown: let the listener retry (same transfer key, so no double move)
                        throw new IllegalStateException("Float transfer outcome unknown: " + e.getMessage(), e);
                    }
                    throw e;
                }
            }
            case REJECTED, CHANGES_REQUESTED -> floats.close(d.resourceId(), "REJECTED",
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays REQUESTED; can be decided directly or cancelled by the maker
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
