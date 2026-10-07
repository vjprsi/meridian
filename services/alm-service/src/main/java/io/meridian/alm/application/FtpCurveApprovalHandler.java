package io.meridian.alm.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service (ALCO) decisions on submitted FTP curves (G11). Approval publishes the curve with the same
 * four-eyes rule as {@code POST /curves/{id}/publish} (the approver is neither the maker nor the submitter); a
 * rejection (or changes requested) rejects the submission and the curve stays unpublished for rework. A decision for
 * a curve that is published, no longer submitted, or whose points changed since submission (checksum) is ignored.
 */
@Component
public class FtpCurveApprovalHandler implements ApprovalOutcomeHandler {
    private final FtpService ftp;

    public FtpCurveApprovalHandler(FtpService ftp) {
        this.ftp = ftp;
    }

    @Override
    public String resourceType() {
        return FtpService.CURVE_APPROVAL;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String checksum = d.payload().path("checksum").asString(null);
        if (d.resourceId() == null || !ftp.awaitsApproval(d.resourceId(), checksum)) {
            return; // decided directly, reworked or resubmitted meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> ftp.publish(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> ftp.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays submitted; can be decided directly or resubmitted
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
