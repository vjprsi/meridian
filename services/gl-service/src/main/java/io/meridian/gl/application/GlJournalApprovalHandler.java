package io.meridian.gl.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on manual GL journals (G11). Approval runs the same four-eyes and posting
 * rules as {@code POST /gl-journals/{id}/approve} with the deciding approver as principal. A journal is append-only
 * and has no rejected state, so a rejection leaves it unapproved (it never posts and keeps blocking the period
 * close until it is reversed by a new journal). A decision for a journal already approved, or whose lines no longer
 * match the submitted checksum, is ignored.
 */
@Component
public class GlJournalApprovalHandler implements ApprovalOutcomeHandler {
    private static final Logger log = LoggerFactory.getLogger(GlJournalApprovalHandler.class);
    private final JournalService journals;

    public GlJournalApprovalHandler(JournalService journals) {
        this.journals = journals;
    }

    @Override
    public String resourceType() {
        return JournalService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String checksum = d.payload().path("checksum").asString(null);
        if (d.resourceId() == null || !journals.awaitsApproval(d.resourceId(), checksum)) {
            return; // approved directly or redelivered: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> journals.approve(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> log.info("GL journal {} rejected in workflow request {}; it stays unposted",
                    d.resourceId(), d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays unapproved; can still be approved directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
