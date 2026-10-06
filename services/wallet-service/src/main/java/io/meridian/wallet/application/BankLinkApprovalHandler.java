package io.meridian.wallet.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on checker-verified (MANUAL / OPEN_BANKING) bank links (G11). Approval runs the
 * same {@link BankLinkService#verify} as the direct endpoint with the approver as principal, so the creator still
 * cannot verify their own link; rejection removes the pending link. Links no longer pending, or whose bank account
 * changed since submission, are left alone.
 */
@Component
public class BankLinkApprovalHandler implements ApprovalOutcomeHandler {
    private final BankLinkService links;

    public BankLinkApprovalHandler(BankLinkService links) {
        this.links = links;
    }

    @Override
    public String resourceType() {
        return BankLinkService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || !links.awaitsVerification(d.resourceId(),
                d.payload().path("bank_code").asString(null), d.payload().path("account_number").asString(null))) {
            return; // verified, removed or changed meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> links.verify(d.resourceId(), null);
            case REJECTED, CHANGES_REQUESTED -> links.remove(d.resourceId(),
                    "verification rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_VERIFICATION; can be verified directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
