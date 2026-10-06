package io.meridian.sanctions.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on the confirmation of a screening result's true matches (G11). Approval runs
 * the same review as the direct endpoint (CONFIRMED_MATCH, four-eyes against the analysts who dispositioned the
 * matches, SANCTIONS case opened) with the deciding approver as principal; rejection returns the true matches to
 * PENDING for re-review (same four-eyes rule as clearing a true match). A decision for a set of true matches that
 * changed since the request, or for a result reviewed meanwhile, is ignored.
 */
@Component
public class ConfirmationApprovalHandler implements ApprovalOutcomeHandler {
    private final ScreeningService screening;

    public ConfirmationApprovalHandler(ScreeningService screening) {
        this.screening = screening;
    }

    @Override
    public String resourceType() {
        return ScreeningService.CONFIRM_APPROVAL;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || (d.action() != null && !ScreeningService.CONFIRM_ACTION.equals(d.action()))) {
            return;
        }
        String fingerprint = d.payload().path("fingerprint").asString(null);
        if (!screening.awaitsConfirmation(d.resourceId(), fingerprint)) {
            return; // reviewed directly, already applied, or the true matches changed: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> screening.review(d.resourceId(), "CONFIRMED_MATCH",
                    "Confirmed in workflow request " + d.approvalRequestId(), false);
            case REJECTED, CHANGES_REQUESTED -> screening.returnTrueMatches(d.resourceId(), fingerprint,
                    "Returned for review in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays POTENTIAL_MATCH; can still be reviewed directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
