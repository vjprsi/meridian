package io.meridian.limits.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * WE-LIMIT-1: applies workflow-service decisions on instrument TCP activation submissions. Activation also enforces
 * the Rank-2 subset-of-Rank-1 rule (MQ-TCP-3). Clone of {@link LimitRuleApprovalHandler}.
 */
@Component
public class InstrumentTcpApprovalHandler implements ApprovalOutcomeHandler {
    private final TcpAdminService admin;

    public InstrumentTcpApprovalHandler(TcpAdminService admin) {
        this.admin = admin;
    }

    @Override
    public String resourceType() {
        return TcpAdminService.INSTRUMENT_TCP;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.resourceId() == null
                || !admin.awaitsApproval(TcpAdminService.INSTRUMENT_TCP, d.resourceId(), d.approvalRequestId())) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> admin.activate(TcpAdminService.INSTRUMENT_TCP, TcpAdminService.INSTRUMENT_TCP, d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> admin.reject(TcpAdminService.INSTRUMENT_TCP, TcpAdminService.INSTRUMENT_TCP,
                    d.resourceId(), "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { }
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
