package io.meridian.tenant.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import org.springframework.stereotype.Component;

/**
 * Executes approved module changes coming back from workflow-service (G11). The deciding approver is the principal,
 * so {@link ModuleService#applyApproved} still enforces four-eyes; execution is idempotent (a module already in the
 * requested state is left alone). Rejected, expired or cancelled requests changed nothing locally.
 */
@Component
public class TenantModuleApprovalHandler implements ApprovalOutcomeHandler {
    private final ModuleService modules;

    public TenantModuleApprovalHandler(ModuleService modules) {
        this.modules = modules;
    }

    @Override
    public String resourceType() {
        return ModuleService.RESOURCE_TYPE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        if (d.approved()) {
            modules.applyApproved(d.request());
        }
    }
}
