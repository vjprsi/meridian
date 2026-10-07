package io.meridian.channel.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on USSD menu drafts submitted for publication (G11). Approval publishes with the
 * same tree validation and four-eyes rule as {@code POST /ussd-menus/{id}/publish} (the approver is neither the maker
 * nor the submitter); a rejection (or changes requested) rejects the submission and the menu stays DRAFT for rework.
 * A decision for a menu no longer submitted, or whose menu or nodes changed since submission (checksum), is ignored.
 */
@Component
public class UssdMenuApprovalHandler implements ApprovalOutcomeHandler {
    private final MenuService menus;

    public UssdMenuApprovalHandler(MenuService menus) {
        this.menus = menus;
    }

    @Override
    public String resourceType() {
        return MenuService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String checksum = d.payload().path("checksum").asString(null);
        if (d.resourceId() == null || !menus.awaitsApproval(d.resourceId(), checksum)) {
            return; // decided directly, edited or resubmitted meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> menus.publish(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> menus.reject(d.resourceId(),
                    "rejected in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays submitted; can be decided directly or resubmitted
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
