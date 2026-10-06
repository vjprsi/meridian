package io.meridian.liquidity.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import java.util.UUID;

/**
 * Applies workflow-service decisions on LCR / NSFR snapshots (G11). Approval runs the same status and four-eyes
 * checks as the direct endpoint, with the deciding approver as principal. A decision for a calculation that was
 * replaced (row_version changed) or already decided is ignored. There is no local reject for ratio snapshots: a
 * rejection leaves the snapshot CALCULATED for the maker to recalculate. Registered per resource type in
 * {@link RatioApprovalHandlers}.
 */
public class RatioApprovalHandler implements ApprovalOutcomeHandler {
    private final RatioService ratios;
    private final String table;

    RatioApprovalHandler(RatioService ratios, String table) {
        this.ratios = ratios;
        this.table = table;
    }

    @Override
    public String resourceType() {
        return table;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        UUID id = d.resourceId();
        Integer version = d.payload().hasNonNull("row_version") ? d.payload().get("row_version").asInt() : null;
        if (id == null || !ratios.awaitsApproval(table, id, version)) {
            return; // decided directly or recalculated meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> ratios.approve(table, id);
            case REJECTED, CHANGES_REQUESTED, EXPIRED, CANCELLED -> { } // stays CALCULATED: recalculate or decide directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
