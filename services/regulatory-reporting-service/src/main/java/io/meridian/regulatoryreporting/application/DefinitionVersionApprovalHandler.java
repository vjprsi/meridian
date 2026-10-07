package io.meridian.regulatoryreporting.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on DRAFT report definition versions (G11). Approval publishes through the same
 * path as the direct endpoint (four eyes against the version's author, compilation, effective-date checks) with the
 * deciding approver as principal. A rejection (or changes requested; versions are immutable) rejects the version like
 * {@code POST /definition-versions/{id}/reject}: REJECTED, and the maker adds a corrected version. Decisions for a
 * version that is no longer a DRAFT are ignored.
 */
@Component
public class DefinitionVersionApprovalHandler implements ApprovalOutcomeHandler {
    private final DefinitionService definitions;

    public DefinitionVersionApprovalHandler(DefinitionService definitions) {
        this.definitions = definitions;
    }

    @Override
    public String resourceType() {
        return DefinitionService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        Integer rowVersion = d.payload().hasNonNull("row_version") ? d.payload().get("row_version").asInt() : null;
        if (d.resourceId() == null || !definitions.awaitsPublication(d.resourceId(), rowVersion)) {
            return; // published directly, superseded or deprecated meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> definitions.publishVersion(d.resourceId(),
                    "published in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> definitions.rejectVersion(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays an unpublished DRAFT
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
