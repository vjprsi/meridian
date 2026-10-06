package io.meridian.deposit.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service credit decisions on overdraft facilities (G11). Approval runs the same four-eyes and
 * account checks as the direct endpoint, with the deciding credit officer as principal; a rejection cancels the
 * request (the facility lifecycle has no REJECTED state). A decision for a facility no longer pending, or for a
 * limit changed since the request, is ignored.
 */
@Component
public class OverdraftApprovalHandler implements ApprovalOutcomeHandler {
    private final OverdraftService overdrafts;

    public OverdraftApprovalHandler(OverdraftService overdrafts) {
        this.overdrafts = overdrafts;
    }

    @Override
    public String resourceType() {
        return OverdraftService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String limit = d.payload().path("limit_amount").asString(null);
        if (d.resourceId() == null
                || !overdrafts.awaitsApproval(d.resourceId(), limit == null ? null : new BigDecimal(limit))) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> overdrafts.approve(d.resourceId(), "Approved in workflow request " + d.approvalRequestId(),
                    null);
            case REJECTED, CHANGES_REQUESTED -> overdrafts.cancel(d.resourceId(),
                    "Rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
