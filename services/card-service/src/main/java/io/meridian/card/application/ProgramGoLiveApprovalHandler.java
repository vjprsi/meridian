package io.meridian.card.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on card program go-live (G11). Approval runs the same {@code activate} as the
 * direct endpoint with the deciding approver as principal, so the four-eyes rule (not the program's creator) still
 * applies. A decision for a program that is no longer in CERTIFICATION, or whose content changed since it was
 * certified (row_version), is ignored.
 */
@Component
public class ProgramGoLiveApprovalHandler implements ApprovalOutcomeHandler {
    private final ProgramService programs;

    public ProgramGoLiveApprovalHandler(ProgramService programs) {
        this.programs = programs;
    }

    @Override
    public String resourceType() {
        return ProgramService.GO_LIVE_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        Integer rowVersion = d.payload().hasNonNull("row_version") ? d.payload().get("row_version").asInt() : null;
        if (d.resourceId() == null || !programs.awaitsGoLive(d.resourceId(), rowVersion)) {
            return; // activated directly, reworked or already decided: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> programs.activate(d.resourceId(), "go-live approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> programs.rejectGoLive(d.resourceId(),
                    "go-live rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays in CERTIFICATION; can still be activated directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
