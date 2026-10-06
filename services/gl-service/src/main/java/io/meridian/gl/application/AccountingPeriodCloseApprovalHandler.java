package io.meridian.gl.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on soft-closed accounting periods (G11). Approval runs the same four-eyes and
 * close-run checks as {@code POST /accounting-periods/{id}/approve-close} with the deciding approver as principal;
 * rejection reopens the period ({@code POST /accounting-periods/{id}/reopen}, SOFT_CLOSED -> REOPENED) with the
 * workflow request as reason. A decision for an earlier close run (reopened and closed again) is ignored.
 */
@Component
public class AccountingPeriodCloseApprovalHandler implements ApprovalOutcomeHandler {
    private final PeriodService periods;

    public AccountingPeriodCloseApprovalHandler(PeriodService periods) {
        this.periods = periods;
    }

    @Override
    public String resourceType() {
        return PeriodService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String closeRunId = d.payload().path("close_run_id").asString(null);
        if (d.resourceId() == null || !periods.awaitsCloseApproval(d.resourceId(), closeRunId)) {
            return; // decided directly, reopened or redelivered: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> periods.approveClose(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> periods.reopen(d.resourceId(),
                    "close rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays SOFT_CLOSED; can still be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
