package io.meridian.productcatalog.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on submitted product scripts (G11). Approval runs the same four-eyes and
 * module-integrity checks as the direct endpoint, with the deciding approver as principal. A decision for a
 * submission that was decided directly, deprecated or resubmitted meanwhile is ignored.
 */
@Component
public class ProductScriptApprovalHandler implements ApprovalOutcomeHandler {
    private final ScriptService scripts;

    public ProductScriptApprovalHandler(ScriptService scripts) {
        this.scripts = scripts;
    }

    @Override
    public String resourceType() {
        return ScriptService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String sha = d.payload().path("wasm_sha256").asString(null);
        String submittedAt = d.payload().path("submitted_at").asString(null);
        if (d.resourceId() == null || !scripts.awaitsApproval(d.resourceId(), sha, submittedAt)) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> scripts.approve(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> scripts.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
