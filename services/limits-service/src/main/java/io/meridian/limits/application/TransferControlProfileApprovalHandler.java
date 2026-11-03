package io.meridian.limits.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * WE-LIMIT-1: applies workflow-service decisions on cumulative TCP activation submissions (clone of
 * {@link LimitRuleApprovalHandler}); the profile's own four-eyes and validation checks still run.
 */
@Component
public class TransferControlProfileApprovalHandler implements ApprovalOutcomeHandler {
    private final TcpAdminService admin;

    public TransferControlProfileApprovalHandler(TcpAdminService admin) {
        this.admin = admin;
    }

    @Override
    public String resourceType() {
        return TcpAdminService.TCP;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null || !admin.awaitsApproval(TcpAdminService.TCP, d.resourceId(), d.approvalRequestId())) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> admin.activate(TcpAdminService.TCP, TcpAdminService.TCP, d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> admin.reject(TcpAdminService.TCP, TcpAdminService.TCP, d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { }
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
