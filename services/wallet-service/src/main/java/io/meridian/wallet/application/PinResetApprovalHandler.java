package io.meridian.wallet.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on staff-verified PIN resets (G11). Approval runs the same
 * {@link PinResetService#verify} as the direct endpoint with the approver as principal (the opener of the request
 * still cannot verify it; an ID check still needs its document; an expired request is refused). The new PIN is
 * always set by the customer afterwards through {@code /complete}, never through workflow-service.
 */
@Component
public class PinResetApprovalHandler implements ApprovalOutcomeHandler {
    private final PinResetService resets;

    public PinResetApprovalHandler(PinResetService resets) {
        this.resets = resets;
    }

    @Override
    public String resourceType() {
        return PinResetService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || !resets.awaitsVerification(d.resourceId())) {
            return; // verified, rejected or expired meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> resets.verify(d.resourceId(), null, null);
            case REJECTED, CHANGES_REQUESTED -> resets.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays REQUESTED until its own expiry
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
