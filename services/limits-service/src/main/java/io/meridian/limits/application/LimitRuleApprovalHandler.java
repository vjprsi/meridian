package io.meridian.limits.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on limit rule activation submissions (G11). Approval runs the same four-eyes and
 * validation checks as {@code POST /rules/{id}/activate}, with the deciding approver as principal; a rejection (or
 * changes requested) rejects the submission and the rule keeps its status. A decision for a submission that was
 * replaced, withdrawn by an edit or decided directly (approval_request_id no longer matches) is ignored.
 */
@Component
public class LimitRuleApprovalHandler implements ApprovalOutcomeHandler {
    private final LimitAdminService admin;

    public LimitRuleApprovalHandler(LimitAdminService admin) {
        this.admin = admin;
    }

    @Override
    public String resourceType() {
        return LimitAdminService.RULE_APPROVAL;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || !admin.awaitsRuleApproval(d.resourceId(), d.approvalRequestId())) {
            return; // decided directly, resubmitted or edited meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> admin.activate(d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> admin.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays submitted; can be decided directly or resubmitted
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
