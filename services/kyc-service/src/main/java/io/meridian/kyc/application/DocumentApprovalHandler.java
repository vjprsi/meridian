package io.meridian.kyc.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on uploaded KYC documents (G11). Acceptance runs the same four-eyes and expiry
 * checks as the direct endpoint, with the deciding reviewer as principal. A decision for a document that was
 * reviewed directly meanwhile (or redelivered) is ignored.
 */
@Component
public class DocumentApprovalHandler implements ApprovalOutcomeHandler {
    private final DocumentService documents;

    public DocumentApprovalHandler(DocumentService documents) {
        this.documents = documents;
    }

    @Override
    public String resourceType() {
        return DocumentService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String documentId = d.payload().path("document_id").asString(null);
        if (d.resourceId() == null || !documents.awaitsReview(d.resourceId(), documentId)) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> documents.accept(d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> documents.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays awaiting review; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
