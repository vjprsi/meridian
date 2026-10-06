package io.meridian.productcatalog.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on submitted product versions (G11). Approval runs the same four-eyes and
 * integrity checks as the direct endpoint, with the deciding approver as principal. A decision for a submission
 * that was decided directly, resubmitted or changed meanwhile is ignored.
 */
@Component
public class ProductVersionApprovalHandler implements ApprovalOutcomeHandler {
    private final VersionService versions;

    public ProductVersionApprovalHandler(VersionService versions) {
        this.versions = versions;
    }

    @Override
    public String resourceType() {
        return VersionService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String checksum = d.payload().path("checksum").asString(null);
        String submittedAt = d.payload().path("submitted_at").asString(null);
        if (d.resourceId() == null || !versions.awaitsApproval(d.resourceId(), checksum, submittedAt)) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> versions.approve(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> versions.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
