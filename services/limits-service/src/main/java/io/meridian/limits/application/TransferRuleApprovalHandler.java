package io.meridian.limits.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * WE-LIMIT-1: applies workflow-service decisions on transfer-rule activation submissions (clone of
 * {@link LimitRuleApprovalHandler}).
 */
@Component
public class TransferRuleApprovalHandler implements ApprovalOutcomeHandler {
    private final TcpAdminService admin;

    public TransferRuleApprovalHandler(TcpAdminService admin) {
        this.admin = admin;
    }

    @Override
    public String resourceType() {
        return TcpAdminService.TRANSFER_RULE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null
                || !admin.awaitsApproval(TcpAdminService.TRANSFER_RULE, d.resourceId(), d.approvalRequestId())) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> admin.activate(TcpAdminService.TRANSFER_RULE, TcpAdminService.TRANSFER_RULE, d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> admin.reject(TcpAdminService.TRANSFER_RULE, TcpAdminService.TRANSFER_RULE,
                    d.resourceId(), "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { }
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
