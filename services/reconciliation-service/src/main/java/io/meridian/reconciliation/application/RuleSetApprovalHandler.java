package io.meridian.reconciliation.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on submitted rule set versions (G11): approval publishes through the same
 * four-eyes check as POST /approve (the deciding approver is the principal); rejection or a change request returns
 * the version to the maker. A decision for content that changed since submission (checksum) is ignored.
 */
@Component
public class RuleSetApprovalHandler implements ApprovalOutcomeHandler {
    private final RuleSetService ruleSets;

    public RuleSetApprovalHandler(RuleSetService ruleSets) {
        this.ruleSets = ruleSets;
    }

    @Override
    public String resourceType() {
        return RuleSetService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String checksum = d.payload() == null ? null : d.payload().path("checksum").asString(null);
        if (d.resourceId() == null || !ruleSets.awaitsApproval(d.resourceId(), checksum)) {
            return; // decided directly, resubmitted or edited meanwhile
        }
        switch (d.outcome()) {
            case APPROVED -> ruleSets.approve(d.resourceId(), null);
            case REJECTED, CHANGES_REQUESTED -> ruleSets.reject(d.resourceId(),
                    "Rejected in workflow request " + d.approvalRequestId());
            default -> { } // EXPIRED / CANCELLED: stays pending, can be decided directly or resubmitted
        }
    }
}
