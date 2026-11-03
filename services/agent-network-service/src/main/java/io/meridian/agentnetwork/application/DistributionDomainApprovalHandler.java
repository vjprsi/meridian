package io.meridian.agentnetwork.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * WE-HIER-1: applies workflow-service decisions on submitted distribution domains. The domain's own four-eyes check
 * still runs (approver as principal); a decision for an earlier submission (decided directly, or rejected and
 * resubmitted) is ignored.
 */
@Component
public class DistributionDomainApprovalHandler implements ApprovalOutcomeHandler {
    private final HierarchyConfigService hierarchy;

    public DistributionDomainApprovalHandler(HierarchyConfigService hierarchy) {
        this.hierarchy = hierarchy;
    }

    @Override
    public String resourceType() {
        return HierarchyConfigService.DOMAIN_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String submittedAt = d.payload().path("submitted_at").asString(null);
        if (d.resourceId() == null || !hierarchy.awaitsApproval(HierarchyConfigService.DOMAIN, d.resourceId(), submittedAt)) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> hierarchy.approve(HierarchyConfigService.DOMAIN, d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> hierarchy.reject(HierarchyConfigService.DOMAIN, d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { }
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
