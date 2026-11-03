package io.meridian.agentnetwork.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * WE-HIER-1: applies workflow-service decisions on submitted grades (clone of the distribution-domain handler); the
 * grade's own four-eyes and category-active checks still run.
 */
@Component
public class GradeApprovalHandler implements ApprovalOutcomeHandler {
    private final GradeService grades;

    public GradeApprovalHandler(GradeService grades) {
        this.grades = grades;
    }

    @Override
    public String resourceType() {
        return GradeService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String submittedAt = d.payload().path("submitted_at").asString(null);
        if (d.resourceId() == null || !grades.awaitsApproval(d.resourceId(), submittedAt)) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> grades.approve(d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> grades.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { }
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
