package io.meridian.audit.api;

import io.meridian.audit.api.generated.AuditEventsApi;
import io.meridian.audit.api.generated.model.AuditEventPage;
import io.meridian.audit.api.generated.model.AuditEventsSearchRequest;
import io.meridian.audit.api.generated.model.AuditEventsVerifyChainRequest;
import io.meridian.audit.application.AuditQueryService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Audit events: read-only list/get (generic CRUD), hash-chain verification and structured search.
 * Events are appended by {@link io.meridian.audit.application.AuditRecorder} (fed from the platform's event topics).
 */
@RestController
public class AuditEventsController implements AuditEventsApi {
    static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
    private final AuditQueryService queries;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public AuditEventsController(AuditQueryService queries, Idempotency idempotency, JsonMapper json) {
        this.queries = queries;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<Map<String, Object>> auditEventsVerifyChain(String idempotencyKey,
                                                                      AuditEventsVerifyChainRequest body) {
        TenantContext.require();
        ResponseEntity<JsonNode> r = idempotency.execute(idempotencyKey, body, JsonNode.class,
                () -> ResponseEntity.ok(queries.verifyChain(body.getFromSequence(), body.getToSequence())));
        return ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders())
                .body(r.getBody() == null ? null : json.convertValue(r.getBody(), OBJECT));
    }

    @Override
    public ResponseEntity<AuditEventPage> auditEventsSearch(String idempotencyKey, AuditEventsSearchRequest body,
                                                            Integer limit, String cursor) {
        TenantContext.require();
        // limit/cursor are part of the request identity: the next page is a different request.
        ObjectNode identity = json.createObjectNode();
        identity.set("body", json.valueToTree(body));
        identity.put("limit", limit);
        identity.put("cursor", cursor);
        return idempotency.execute(idempotencyKey, identity, AuditEventPage.class, () -> ResponseEntity.ok(
                json.readValue(queries.search(body.getQuery(), limit, cursor), AuditEventPage.class)));
    }
}
