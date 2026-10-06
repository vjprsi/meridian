package io.meridian.audit.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on data exports (G11). Approved runs the export with the approver as the
 * executing principal, so the four-eyes rule is enforced again locally; rejected, sent back or cancelled cancels
 * the queued export (an export request cannot be amended); an expired request leaves it queued. Decisions for an
 * approval request that is not the export's own are refused ({@code approval_mismatch}); re-deliveries are no-ops.
 */
@Component
public class DataExportApprovalHandler implements ApprovalOutcomeHandler {
    private final DataExportService exports;

    public DataExportApprovalHandler(DataExportService exports) {
        this.exports = exports;
    }

    @Override
    public String resourceType() {
        return DataExportService.RESOURCE_TYPE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        switch (d.outcome()) {
            case APPROVED -> exports.applyApproved(d.request());
            case REJECTED, CHANGES_REQUESTED, CANCELLED ->
                    exports.closeUnapproved(d.request(), d.outcome().name().toLowerCase(Locale.ROOT));
            case EXPIRED -> { }
            default -> { }
        }
    }
}
