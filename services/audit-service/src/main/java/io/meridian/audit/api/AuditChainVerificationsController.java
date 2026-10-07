package io.meridian.audit.api;

import io.meridian.audit.api.generated.AuditChainVerificationsApi;
import io.meridian.audit.api.generated.model.AuditChainVerification;
import io.meridian.audit.api.generated.model.AuditChainVerificationsRunRequest;
import io.meridian.audit.application.AuditIntegrityService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.idempotency.Idempotency;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hash-chain verification runs (GAP-07): list / get (generic) and an on-demand run; runs are also scheduled daily
 * per tenant ({@link io.meridian.audit.application.AuditIntegrityJobs}).
 */
@RestController
public class AuditChainVerificationsController implements AuditChainVerificationsApi {
    private final AuditIntegrityService integrity;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public AuditChainVerificationsController(AuditIntegrityService integrity, Idempotency idempotency, JsonMapper json) {
        this.integrity = integrity;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<AuditChainVerification> auditChainVerificationsRun(String idempotencyKey,
                                                                             AuditChainVerificationsRunRequest body) {
        TenantContext.require();
        boolean full = body != null && Boolean.TRUE.equals(body.getFull());
        return idempotency.execute(idempotencyKey, body, AuditChainVerification.class, () -> ResponseEntity.ok(
                json.convertValue(integrity.verifyChain("ON_DEMAND", full), AuditChainVerification.class)));
    }
}
