package io.meridian.aiplatform.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on actions proposed by a copilot (G11). The decision is recorded exactly as on
 * the direct approve-action / reject-action endpoints, with the deciding approver as principal (the requester still
 * cannot decide). Ignored once the proposal was decided or no longer matches the proposed response.
 */
@Component
public class CopilotActionApprovalHandler implements ApprovalOutcomeHandler {
    private final CopilotService copilots;

    public CopilotActionApprovalHandler(CopilotService copilots) {
        this.copilots = copilots;
    }

    @Override
    public String resourceType() {
        return CopilotService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String fingerprint = d.payload().path("response_sha256").asString(null);
        if (d.resourceId() == null || !copilots.awaitsDecision(d.resourceId(), fingerprint)) {
            return; // decided directly or already applied (redelivery): nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> copilots.decide(d.resourceId(), true, "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> copilots.decide(d.resourceId(), false,
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays proposed; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
