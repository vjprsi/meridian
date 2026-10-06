package io.meridian.customer.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on KYC tier upgrades (G11). Only the approval request currently linked to the
 * customer ({@code tier_approval_request_id}) has an effect, so redelivered or stale decisions are harmless; the
 * evidence and the four-eyes rule (approver differs from the requester) are re-checked at execution time.
 */
@Component
public class CustomerTierApprovalHandler implements ApprovalOutcomeHandler {
    private final CustomerService customers;

    public CustomerTierApprovalHandler(CustomerService customers) {
        this.customers = customers;
    }

    @Override
    public String resourceType() {
        return CustomerService.TIER_APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        customers.onTierApprovalDecided(d.request(), d.outcome().name().toLowerCase(Locale.ROOT), d.decidedBy());
    }
}
