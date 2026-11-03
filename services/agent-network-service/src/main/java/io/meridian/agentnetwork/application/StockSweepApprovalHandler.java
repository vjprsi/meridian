package io.meridian.agentnetwork.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * WE-FLOAT-1: applies workflow-service decisions on auto-deallocation sweep runs. Approval runs the run's four-eyes
 * check and the per-line sweep with the deciding approver as principal; an unknown (5xx) outcome is surfaced so the
 * listener retries (already-swept lines are skipped). A decision for a run no longer awaiting approval is ignored.
 */
@Component
public class StockSweepApprovalHandler implements ApprovalOutcomeHandler {
    private final StockSweepService sweeps;

    public StockSweepApprovalHandler(StockSweepService sweeps) {
        this.sweeps = sweeps;
    }

    @Override
    public String resourceType() {
        return StockSweepService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || !sweeps.awaitsApproval(d.resourceId())) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> {
                try {
                    sweeps.approve(d.resourceId());
                } catch (ApiException e) {
                    if (e.status() >= 500) {
                        throw new IllegalStateException("Sweep run outcome unknown: " + e.getMessage(), e);
                    }
                    throw e;
                }
            }
            case REJECTED, CHANGES_REQUESTED -> sweeps.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { }
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
