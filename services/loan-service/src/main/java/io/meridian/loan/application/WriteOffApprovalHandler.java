package io.meridian.loan.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on requested write-offs (G11). Approval runs the same four-eyes, balance and
 * ledger posting logic as the direct endpoint, with the deciding approver as principal. A decision for a request
 * the write-off no longer waits for (decided directly, other request, changed amount) is ignored.
 */
@Component
public class WriteOffApprovalHandler implements ApprovalOutcomeHandler {
    private final WriteOffService writeOffs;

    public WriteOffApprovalHandler(WriteOffService writeOffs) {
        this.writeOffs = writeOffs;
    }

    @Override
    public String resourceType() {
        return WriteOffService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String total = d.payload().path("total_amount").asString(null);
        if (d.resourceId() == null || !writeOffs.awaitsApproval(d.resourceId(), d.approvalRequestId(),
                total == null ? null : new BigDecimal(total))) {
            return; // decided directly or no longer pending: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> writeOffs.approve(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> writeOffs.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays PENDING_APPROVAL; can still be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
