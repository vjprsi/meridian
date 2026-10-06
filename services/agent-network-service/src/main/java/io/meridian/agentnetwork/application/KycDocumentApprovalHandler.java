package io.meridian.agentnetwork.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on agent KYB documents (G11). Approval verifies the document under the same
 * four-eyes and expiry rules as the direct endpoint, with the deciding approver as principal. A document has no
 * rejected state (the uploader deletes and re-uploads it), so a rejection leaves it unverified.
 */
@Component
public class KycDocumentApprovalHandler implements ApprovalOutcomeHandler {
    private final ComplianceService compliance;

    public KycDocumentApprovalHandler(ComplianceService compliance) {
        this.compliance = compliance;
    }

    @Override
    public String resourceType() {
        return ComplianceService.DOC_APPROVAL;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String documentId = d.payload().path("document_id").asString(null);
        if (d.resourceId() == null || !compliance.documentAwaitsVerification(d.resourceId(), documentId)) {
            return; // verified directly, deleted or already applied
        }
        switch (d.outcome()) {
            case APPROVED -> compliance.verifyDocument(d.resourceId(), "verified in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED, EXPIRED, CANCELLED -> { } // stays unverified; activation still requires KYB
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
