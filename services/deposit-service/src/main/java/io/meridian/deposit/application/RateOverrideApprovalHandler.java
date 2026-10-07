package io.meridian.deposit.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on account rate overrides (SEC-20) through the same approve / reject methods as
 * the direct endpoints (the deciding checker is the principal, so four-eyes still applies). Only a decision on the
 * request stored on the override counts; an edited, decided or deleted override ignores it.
 */
@Component
public class RateOverrideApprovalHandler implements ApprovalOutcomeHandler {
    private final RateOverrideService overrides;

    public RateOverrideApprovalHandler(RateOverrideService overrides) {
        this.overrides = overrides;
    }

    @Override
    public String resourceType() {
        return RateOverrideService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || !overrides.awaitsApproval(d.resourceId(), d.approvalRequestId())) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> overrides.approve(d.resourceId(), "workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> overrides.reject(d.resourceId(),
                    "Rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
