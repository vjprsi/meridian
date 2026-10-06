package io.meridian.analytics.application;

import io.meridian.approvals.ApprovalDecision;
import io.meridian.approvals.ApprovalOutcomeHandler;
import io.meridian.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Applies workflow-service decisions on KPI certification requests (G11). Approval runs the same certification as
 * the direct endpoint with the deciding approver as principal, so the author still cannot certify their own KPI.
 * A decision for a definition that changed since submission, or a KPI already certified, is ignored. KPIs have no
 * rejected state: a rejection leaves the KPI uncertified (the author reworks the definition, which resubmits).
 */
@Component
public class KpiCertificationHandler implements ApprovalOutcomeHandler {
    private static final Logger log = LoggerFactory.getLogger(KpiCertificationHandler.class);

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
            case REJECTED, CHANGES_REQUESTED -> log.info("KPI {} certification {} in workflow request {}; stays uncertified",
                    d.resourceId(), d.outcome(), d.approvalRequestId());
            case EXPIRED, CANCELLED -> { } // stays uncertified; can be certified directly or resubmitted
            default -> throw new ApiException(500, "unknown_outcome", "Unhandled outcome " + d.outcome());
        }
    }
}
