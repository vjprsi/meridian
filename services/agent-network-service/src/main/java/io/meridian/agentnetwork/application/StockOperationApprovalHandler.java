package io.meridian.agentnetwork.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * WE-FLOAT-1: applies workflow-service decisions on stock operations (clone of {@link FloatRequestApprovalHandler}).
 * The operation's own four-eyes check and wallet-service execution run with the deciding approver as principal; a
 * decision for an operation that is no longer REQUESTED or whose amount differs from the submission is ignored.
 */
@Component
public class StockOperationApprovalHandler implements ApprovalOutcomeHandler {
    private final StockOperationService stock;

    public StockOperationApprovalHandler(StockOperationService stock) {
        this.stock = stock;
    }

    @Override
    public String resourceType() {
        return StockOperationService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String amount = d.payload().path("amount").asString(null);
        if (d.resourceId() == null || !stock.awaitsApproval(d.resourceId(), amount == null ? null : new BigDecimal(amount))) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> {
                try {
                    stock.approve(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
                } catch (ApiException e) {
                    if (e.status() >= 500) {
                        throw new IllegalStateException("Stock operation outcome unknown: " + e.getMessage(), e);
                    }
                    throw e;
                }
            }
            case REJECTED, CHANGES_REQUESTED -> stock.close(d.resourceId(), "REJECTED",
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { }
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
