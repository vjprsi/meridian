package io.meridian.config.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import org.springframework.stereotype.Component;

/**
 * Activates config versions approved in workflow-service (G11). Activation runs the same four-eyes, lifecycle and
 * stale-version checks as the direct endpoint, with the deciding approver as principal. Decisions for an approval
 * request that is no longer the version's current one, or for a version no longer PENDING_APPROVAL, are ignored.
 * A refused request changes nothing: the version stays PENDING_APPROVAL and cannot be activated
 * ({@code approval_not_granted}); the maker creates a new version.
 */
@Component
public class ConfigEntryApprovalHandler implements ApprovalOutcomeHandler {
    private final ConfigEntryService entries;

    public ConfigEntryApprovalHandler(ConfigEntryService entries) {
        this.entries = entries;
    }

    @Override
    public String resourceType() {
        return ConfigEntryService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.approved() && d.resourceId() != null && entries.awaitsApproval(d.resourceId(), d.approvalRequestId())) {
            entries.activate(d.resourceId(), d.request());
        }
    }
}
