package io.meridian.fraud.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import io.meridian.fraud.infrastructure.Db;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on fraud rule activation submissions (G11). Approval runs the same four-eyes and
 * expression checks as {@code POST /rules/{id}/activate} in the submitted mode, with the deciding approver as
 * principal; a rejection (or changes requested) rejects the submission and the rule keeps its status. A decision for
 * a submission that was replaced, withdrawn by an edit or decided directly (approval_request_id no longer matches) is
 * ignored.
 */
@Component
public class FraudRuleApprovalHandler implements ApprovalOutcomeHandler {
    private final FraudRuleService rules;

    public FraudRuleApprovalHandler(FraudRuleService rules) {
        this.rules = rules;
    }

    @Override
    public String resourceType() {
        return FraudRuleService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || !rules.awaitsApproval(d.resourceId(), d.approvalRequestId())) {
            return; // decided directly, resubmitted or edited meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> rules.activate(d.resourceId(),
                    rules.pending(d.resourceId()).map(r -> Db.str(r, "submitted_mode")).orElseThrow());
            case REJECTED, CHANGES_REQUESTED -> rules.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays submitted; can be decided directly or resubmitted
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
