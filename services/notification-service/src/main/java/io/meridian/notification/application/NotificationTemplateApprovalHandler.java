package io.meridian.notification.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on template publishing (G11): approved activates the version (retiring the
 * previously active one); rejected, changes requested, cancelled or expired return it to DRAFT. Decisions for a
 * template no longer PENDING_APPROVAL with the submitted content are ignored (idempotent, at-least-once delivery).
 */
@Component
public class NotificationTemplateApprovalHandler implements ApprovalOutcomeHandler {
    private final TemplateService templates;

    public NotificationTemplateApprovalHandler(TemplateService templates) {
        this.templates = templates;
    }

    @Override
    public String resourceType() {
        return TemplateService.RESOURCE_TYPE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() != null) {
            templates.onWorkflowDecision(d.resourceId(), d.payload().path("checksum").asString(null), d.outcome().name(),
                    d.maker());
        }
    }
}
