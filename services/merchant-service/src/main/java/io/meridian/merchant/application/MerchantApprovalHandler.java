package io.meridian.merchant.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on merchant onboarding (G11). Approval runs the same
 * {@link MerchantService#activate} as the direct endpoint with the approver as principal (the creator still cannot
 * activate; a fee plan is still required). A rejection (or changes requested) returns the onboarding to the maker for
 * rework like {@code POST /merchants/{id}/reject-onboarding}: the merchant stays ONBOARDING with the reason in
 * status_reason, and a PATCH resubmits it. Merchants no longer ONBOARDING, already returned, or changed since the
 * submission (row_version) are left alone.
 */
@Component
public class MerchantApprovalHandler implements ApprovalOutcomeHandler {
    private final MerchantService merchants;

    public MerchantApprovalHandler(MerchantService merchants) {
        this.merchants = merchants;
    }

    @Override
    public String resourceType() {
        return MerchantService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        Integer rowVersion = d.payload().hasNonNull("row_version") ? d.payload().get("row_version").asInt() : null;
        if (d.resourceId() == null || !merchants.awaitsOnboarding(d.resourceId(),
                d.payload().path("merchant_code").asString(null), rowVersion)) {
            return; // activated, returned, reworked or terminated meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> merchants.activate(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> merchants.rejectOnboarding(d.resourceId(),
                    (d.outcome() == ApprovalDecision.Outcome.REJECTED ? "onboarding rejected" : "changes requested")
                            + " in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays ONBOARDING
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
