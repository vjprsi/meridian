package io.meridian.treasurydeal.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on SSI verification (G11). Approval runs the same four-eyes check as the direct
 * verify endpoint, with the deciding approver as principal. A decision for payment details that changed since
 * submission (row_version) is ignored. SSIs have no local reject: a rejection leaves the SSI unverified (unusable
 * for settlement) until the maker corrects it (which opens a new request) or a checker verifies it directly.
 */
@Component
public class SsiApprovalHandler implements ApprovalOutcomeHandler {
    private final CounterpartyService counterparties;

    public SsiApprovalHandler(CounterpartyService counterparties) {
        this.counterparties = counterparties;
    }

    @Override
    public String resourceType() {
        return CounterpartyService.SSI_APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        Integer rowVersion = d.payload().hasNonNull("row_version") ? d.payload().get("row_version").asInt() : null;
        if (d.resourceId() == null || !counterparties.awaitsVerification(d.resourceId(), rowVersion)) {
            return; // verified directly or payment details changed meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> counterparties.verifySsi(d.resourceId());
            case REJECTED, CHANGES_REQUESTED, EXPIRED, CANCELLED -> { } // stays unverified (no local reject)
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
