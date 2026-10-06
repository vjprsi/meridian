package io.meridian.document.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on template publishing (G11): approved activates the version (retiring the
 * previously active one); rejected, changes requested, expired or cancelled return it to DRAFT. Decisions for a
 * template no longer PENDING_APPROVAL with the submitted content are ignored (idempotent, at-least-once delivery).
 */
@Component
public class DocumentTemplateApprovalHandler implements ApprovalOutcomeHandler {
    private final TemplateService templates;

    public DocumentTemplateApprovalHandler(TemplateService templates) {
        this.templates = templates;
    }

    @Override
    public String resourceType() {
        return TemplateService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() != null) {
            templates.onWorkflowDecision(d.resourceId(), d.payload().path("checksum").asString(null), d.outcome().name(),
                    d.maker());
        }
    }
}
