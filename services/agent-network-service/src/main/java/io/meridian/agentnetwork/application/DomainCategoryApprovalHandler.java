package io.meridian.agentnetwork.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * WE-HIER-1: applies workflow-service decisions on submitted domain categories (clone of the distribution-domain
 * handler); the category's own four-eyes and parent/domain-active checks still run.
 */
@Component
public class DomainCategoryApprovalHandler implements ApprovalOutcomeHandler {
    private final HierarchyConfigService hierarchy;

    public DomainCategoryApprovalHandler(HierarchyConfigService hierarchy) {
        this.hierarchy = hierarchy;
    }

    @Override
    public String resourceType() {
        return HierarchyConfigService.CATEGORY_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String submittedAt = d.payload().path("submitted_at").asString(null);
        if (d.resourceId() == null || !hierarchy.awaitsApproval(HierarchyConfigService.CATEGORY, d.resourceId(), submittedAt)) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> hierarchy.approve(HierarchyConfigService.CATEGORY, d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> hierarchy.reject(HierarchyConfigService.CATEGORY, d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { }
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
