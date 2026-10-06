package io.meridian.deposit.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on account openings (G11). Approval runs the same four-eyes and guardian
 * checks as the direct endpoint, with the deciding approver as principal; a rejection declines the opening.
 * A decision for an account no longer awaiting approval (decided directly, redelivered) is ignored.
 */
@Component
public class DepositAccountApprovalHandler implements ApprovalOutcomeHandler {
    private final DepositAccountService accounts;

    public DepositAccountApprovalHandler(DepositAccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public String resourceType() {
        return DepositAccountService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String accountNumber = d.payload().path("account_number").asString(null);
        if (d.resourceId() == null || !accounts.awaitsApproval(d.resourceId(), accountNumber)) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> accounts.approve(d.resourceId(), "Approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> accounts.reject(d.resourceId(),
                    "Rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can be approved directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
