package io.meridian.crm.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on complaint redress proposals (G11). Approval runs the same four-eyes check
 * as the direct endpoint, with the deciding checker as principal (payment stays a separate step). A decision for a
 * proposal already decided directly, or redelivered, is ignored.
 */
@Component
public class RedressApprovalHandler implements ApprovalOutcomeHandler {
    private final RedressService redress;

    public RedressApprovalHandler(RedressService redress) {
        this.redress = redress;
    }

    @Override
    public String resourceType() {
        return RedressService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || !redress.awaitsApproval(d.resourceId())) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> redress.approve(d.resourceId(), "Approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> redress.reject(d.resourceId(),
                    "Rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PROPOSED; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
