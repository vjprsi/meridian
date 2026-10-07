package io.meridian.tenant.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import org.springframework.stereotype.Component;

/**
 * Decisions on tenant moves coming back from workflow-service. The deciding approver is the principal, so
 * {@link TenantMoveService#applyApproved} enforces four-eyes; anything other than an approval ends the move and
 * releases the tenant. Idempotent: a move no longer waiting for this approval request is left alone.
 */
@Component
public class TenantMoveApprovalHandler implements ApprovalOutcomeHandler {
    private final TenantMoveService moves;

    public TenantMoveApprovalHandler(TenantMoveService moves) {
        this.moves = moves;
    }

    @Override
    public boolean centralOnly() {
        return true; // no local approve endpoint: decided in workflow-service only
    }

    @Override
    public String resourceType() {
        return TenantMoveService.RESOURCE_TYPE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.approved()) {
            moves.applyApproved(d.request());
        } else {
            moves.applyNotApproved(d);
        }
    }
}
