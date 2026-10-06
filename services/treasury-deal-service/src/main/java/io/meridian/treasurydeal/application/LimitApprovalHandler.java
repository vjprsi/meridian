package io.meridian.treasurydeal.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on counterparty limits (new limits and limit increases, G11). Approval runs the
 * same transition and four-eyes checks as the direct endpoint, with the deciding approver as principal. A decision
 * for content that changed since submission (row_version) is ignored. Limits have no local reject: a rejection
 * leaves the limit PENDING_APPROVAL (not usable for dealing) for the maker to rework or for a direct decision.
 */
@Component
public class LimitApprovalHandler implements ApprovalOutcomeHandler {
    private final LimitService limits;

    public LimitApprovalHandler(LimitService limits) {
        this.limits = limits;
    }

    @Override
    public String resourceType() {
        return LimitService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        Integer rowVersion = d.payload().hasNonNull("row_version") ? d.payload().get("row_version").asInt() : null;
        if (d.resourceId() == null || !limits.awaitsApproval(d.resourceId(), rowVersion)) {
            return; // decided directly or reworked meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> limits.approve(d.resourceId());
            case REJECTED, CHANGES_REQUESTED, EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL (no local reject)
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
