package io.meridian.crm.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service review decisions on knowledge articles (G11). Approval publishes under the same
 * four-eyes rule as the direct endpoint, with the reviewer as principal. The article lifecycle has no reject edge,
 * so a rejection (or changes requested) archives the article; the author reworks it through new-version.
 * A decision for another version, or for an article no longer in review, is ignored.
 */
@Component
public class KnowledgeArticleApprovalHandler implements ApprovalOutcomeHandler {
    private final KnowledgeService knowledge;

    public KnowledgeArticleApprovalHandler(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @Override
    public String resourceType() {
        return KnowledgeService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        Integer version = d.payload().hasNonNull("version") ? d.payload().get("version").asInt() : null;
        if (d.resourceId() == null || !knowledge.awaitsReview(d.resourceId(), version)) {
            return;
        }
        switch (d.outcome()) {
            case APPROVED -> knowledge.publish(d.resourceId());
            case REJECTED, CHANGES_REQUESTED -> knowledge.archive(d.resourceId());
            case EXPIRED, CANCELLED -> { } // stays IN_REVIEW; can be published directly
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
