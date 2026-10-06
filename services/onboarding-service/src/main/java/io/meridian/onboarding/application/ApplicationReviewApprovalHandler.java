package io.meridian.onboarding.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Applies workflow-service review decisions on referred onboarding applications (G11). The same checker rules as
 * the direct endpoints apply (the reviewer is neither the creator nor the submitter). Changes requested send the
 * application back to the applicant like request-info. A decision for an earlier submission, or for an application
 * already decided directly, is ignored.
 */
@Component
public class ApplicationReviewApprovalHandler implements ApprovalOutcomeHandler {
    private final ApplicationService applications;

    public ApplicationReviewApprovalHandler(ApplicationService applications) {
        this.applications = applications;
    }

    @Override
    public String resourceType() {
        return ApplicationService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String submittedAt = d.payload().path("submitted_at").asString(null);
        if (d.resourceId() == null || !applications.awaitsReview(d.resourceId(), submittedAt)) {
            return;
        }
        String ref = "workflow request " + d.approvalRequestId();
        switch (d.outcome()) {
            case APPROVED -> applications.approve(d.resourceId(), "Approved in " + ref);
            case REJECTED -> applications.reject(d.resourceId(), "Rejected in " + ref);
            case CHANGES_REQUESTED -> applications.requestInfo(d.resourceId(), "Changes requested in " + ref,
                    JsonNodeFactory.instance.arrayNode());
            case EXPIRED, CANCELLED -> { } // stays in review; can be decided directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
