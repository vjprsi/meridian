package io.meridian.gl.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on manual GL journals (G11). Approval runs the same four-eyes and posting
 * rules as {@code POST /gl-journals/{id}/approve} with the deciding approver as principal. A rejection (or changes
 * requested; journals are append-only, so rework is a new journal) records a gl_journal_rejection like
 * {@code POST /gl-journals/{id}/reject}: the journal never posts and no longer blocks the period close. A decision for
 * a journal already decided, or whose lines no longer match the submitted checksum, is ignored.
 */
@Component
public class GlJournalApprovalHandler implements ApprovalOutcomeHandler {
    private final JournalService journals;

    public GlJournalApprovalHandler(JournalService journals) {
        this.journals = journals;
    }

    @Override
    public String resourceType() {
        return JournalService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String checksum = d.payload().path("checksum").asString(null);
        if (d.resourceId() == null || !journals.awaitsApproval(d.resourceId(), checksum)) {
            return; // approved directly or redelivered: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> journals.approve(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> journals.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays unapproved; can still be approved directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
