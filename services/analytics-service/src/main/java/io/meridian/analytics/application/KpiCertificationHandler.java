package io.meridian.analytics.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on KPI certification requests (G11). Approval runs the same certification as
 * the direct endpoint with the deciding approver as principal, so the author still cannot certify their own KPI.
 * A rejection (or changes requested) declines certification like {@code POST /kpis/{id}/reject-certification}
 * (recorded on the KPI; the author's definition change clears it and resubmits). A decision for a definition that
 * changed since submission, or a KPI already certified or declined, is ignored.
 */
@Component
public class KpiCertificationHandler implements ApprovalOutcomeHandler {
    private final KpiService kpis;

    public KpiCertificationHandler(KpiService kpis) {
        this.kpis = kpis;
    }

    @Override
    public String resourceType() {
        return KpiService.APPROVAL_RESOURCE;
    }

    @Override
    public void onDecided(ApprovalDecision d) {
        String checksum = d.payload().path("definition_checksum").asString(null);
        if (d.resourceId() == null || !kpis.awaitsCertification(d.resourceId(), checksum)) {
            return; // certified directly, or the definition changed meanwhile: nothing to apply
        }
        switch (d.outcome()) {
            case APPROVED -> kpis.certify(d.resourceId(), "certified in workflow request " + d.approvalRequestId());
            case REJECTED, CHANGES_REQUESTED -> kpis.rejectCertification(d.resourceId(),
                    "declined in workflow request " + d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays uncertified; can be certified directly or resubmitted
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
