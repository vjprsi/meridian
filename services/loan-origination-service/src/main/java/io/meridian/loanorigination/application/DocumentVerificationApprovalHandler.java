package io.meridian.loanorigination.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on uploaded loan application documents (G11). Approval verifies through the
 * same method as the direct endpoint (four-eyes against the document's creator, with the deciding approver as
 * principal); rejection rejects the document. A decision for a different file (replaced meanwhile), a document no
 * longer UPLOADED or a closed application is ignored.
 */
@Component
public class DocumentVerificationApprovalHandler implements ApprovalOutcomeHandler {
    private final ApplicationService applications;

    public DocumentVerificationApprovalHandler(ApplicationService applications) {
        this.applications = applications;
    }

    @Override
    public String resourceType() {
        return ApplicationService.DOCUMENT_APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String app = d.payload().path("loan_application_id").asString(null);
        String documentId = d.payload().path("document_id").asString(null);
        if (d.resourceId() == null || app == null) {
            return;
        }
        UUID appId = UUID.fromString(app);
        if (!applications.awaitsVerification(appId, d.resourceId(), documentId)) {
            return; // verified/rejected directly, replaced or application closed meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> applications.verifyDocument(appId, d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> applications.rejectDocument(appId, d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays UPLOADED; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
