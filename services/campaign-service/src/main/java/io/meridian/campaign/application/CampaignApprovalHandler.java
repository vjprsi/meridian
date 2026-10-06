package io.meridian.campaign.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on submitted campaigns (G11). The campaign's own lifecycle rules still apply:
 * approval runs the same four-eyes check as the direct endpoint, with the deciding approver as principal. A decision
 * for an earlier submission (rejected and resubmitted meanwhile) or for a campaign already decided is ignored.
 */
@Component
public class CampaignApprovalHandler implements ApprovalOutcomeHandler {
    private final CampaignService campaigns;

    public CampaignApprovalHandler(CampaignService campaigns) {
        this.campaigns = campaigns;
    }

    @Override
    public String resourceType() {
        return CampaignService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String submittedAt = d.payload().path("submitted_at").asString(null);
        if (d.resourceId() == null || !campaigns.awaitsApproval(d.resourceId(), submittedAt)) {
            return; // decided directly, cancelled or resubmitted meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> campaigns.approve(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> campaigns.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can be decided directly or resubmitted
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
