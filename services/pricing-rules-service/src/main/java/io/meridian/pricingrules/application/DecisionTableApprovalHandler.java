package io.meridian.pricingrules.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on submitted decision table versions (G11). The version's own lifecycle
 * rules still apply: approval runs the same four-eyes and checksum checks as the direct endpoint, with the
 * deciding approver as principal. A decision for content that changed since submission is ignored.
 */
@Component
public class DecisionTableApprovalHandler implements ApprovalOutcomeHandler {
    private final DecisionTableService tables;

    public DecisionTableApprovalHandler(DecisionTableService tables) {
        this.tables = tables;
    }

    @Override
    public String resourceType() {
        return DecisionTableService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String checksum = d.payload().path("checksum").asString(null);
        if (d.resourceId() == null || !tables.awaitsApproval(d.resourceId(), checksum)) {
            return; // decided directly, resubmitted or reworked meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> tables.approve(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> tables.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can be decided directly or resubmitted
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
