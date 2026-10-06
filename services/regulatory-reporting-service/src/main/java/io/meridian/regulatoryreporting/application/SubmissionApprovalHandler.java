package io.meridian.regulatoryreporting.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service sign-off decisions on regulator submissions (G11). Approval runs the same sign-off as the
 * direct endpoint (four eyes against the preparer; auto-submit for auto_submit schedules) with the deciding approver as
 * principal; a rejection closes the submission as REJECTED so the run can be corrected and resubmitted. Decisions for
 * a submission no longer awaiting this request (or for another file) are ignored.
 */
@Component
public class SubmissionApprovalHandler implements ApprovalOutcomeHandler {
    private final SubmissionService submissions;

    public SubmissionApprovalHandler(SubmissionService submissions) {
        this.submissions = submissions;
    }

    @Override
    public String resourceType() {
        return SubmissionService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String sha256 = d.payload().path("sha256").asString(null);
        if (d.resourceId() == null || !submissions.awaitsSignOff(d.resourceId(), d.approvalRequestId(), sha256)) {
            return; // signed off directly or decided meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> submissions.approve(d.resourceId(), "signed off in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> submissions.declineSignOff(d.resourceId(),
                    "sign-off rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can be signed off directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
