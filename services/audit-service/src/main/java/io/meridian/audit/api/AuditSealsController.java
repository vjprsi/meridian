package io.meridian.audit.api;

import io.meridian.audit.api.generated.AuditSealsApi;
import io.meridian.audit.api.generated.model.AuditSeal;
import io.meridian.audit.api.generated.model.AuditSealsRunRequest;
import io.meridian.audit.application.AuditIntegrityService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sealed daily audit batches on WORM storage (GAP-07): list / get (generic), seal a day now, verify a seal against
 * storage and the database. The daily schedule is {@link io.meridian.audit.application.AuditIntegrityJobs}.
 */
@RestController
public class AuditSealsController implements AuditSealsApi {
    private final AuditIntegrityService integrity;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public AuditSealsController(AuditIntegrityService integrity, Idempotency idempotency, JsonMapper json) {
        this.integrity = integrity;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<AuditSeal> auditSealsRun(String idempotencyKey, AuditSealsRunRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, AuditSeal.class, () -> ResponseEntity.ok(json.convertValue(
                integrity.seal(body == null || body.getSealDate() == null ? Clocks.now().toLocalDate().minusDays(1)
                        : body.getSealDate()), AuditSeal.class)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> auditSealsVerify(UUID id, String idempotencyKey) {
        TenantContext.require();
        ResponseEntity<JsonNode> r = idempotency.execute(idempotencyKey, Map.of("id", id.toString()), JsonNode.class,
                () -> ResponseEntity.ok(integrity.verifySeal(id)));
        return ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders())
                .body(r.getBody() == null ? null : json.convertValue(r.getBody(), AuditEventsController.OBJECT));
    }
}
