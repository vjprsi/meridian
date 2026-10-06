package io.meridian.merchant.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on merchant onboarding (G11). Approval runs the same
 * {@link MerchantService#activate} as the direct endpoint with the approver as principal (the creator still cannot
 * activate; a fee plan is still required). A rejection terminates the onboarding application (the only exit from
 * ONBOARDING besides activation); changes requested leave it ONBOARDING for the maker to complete. Merchants no longer
 * ONBOARDING are left alone.
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
        if (d.resourceId() == null
                || !merchants.awaitsOnboarding(d.resourceId(), d.payload().path("merchant_code").asString(null))) {
            return; // activated or terminated meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> merchants.activate(d.resourceId(), "approved in workflow request " + d.approvalRequestId());
            case REJECTED -> merchants.terminate(d.resourceId(),
                    "onboarding rejected in workflow request " + d.approvalRequestId());
            case CHANGES_REQUESTED, EXPIRED, CANCELLED -> { } // stays ONBOARDING
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
