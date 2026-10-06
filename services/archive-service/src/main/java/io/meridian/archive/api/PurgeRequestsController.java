package io.meridian.archive.api;

import io.meridian.archive.api.generated.PurgeRequestsApi;
import io.meridian.archive.api.generated.model.PurgeRequest;
import io.meridian.archive.api.generated.model.PurgeRequestCreate;
import io.meridian.archive.api.generated.model.PurgeRequestsCancelRequest;
import io.meridian.archive.api.generated.model.PurgeRequestsRejectRequest;
import io.meridian.archive.application.PurgeService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Purge requests: DRAFT -> submit -> PENDING_APPROVAL -> approve (four-eyes) / reject (with reason) -> execute,
 * cancel (with reason) before execution, and the certificate of destruction of a completed purge.
 */
@RestController
public class PurgeRequestsController implements PurgeRequestsApi {
    private final PurgeService purges;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public PurgeRequestsController(PurgeService purges, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.purges = purges;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<PurgeRequest> purgeRequestsCreate(String idempotencyKey, PurgeRequestCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, PurgeRequest.class, () -> ResponseEntity.status(HttpStatus.CREATED)
                .body(crud.read(crud.insert("purge_request", purges.prepareCreate(crud.toColumns("purge_request", body))),
                        PurgeRequest.class)));
    }

    @Override
    public ResponseEntity<PurgeRequest> purgeRequestsSubmit(UUID id, String idempotencyKey) {
        TenantContext.require();
        return action(idempotencyKey, "submit", id, () -> purges.submit(id));
    }

    @Override
    public ResponseEntity<PurgeRequest> purgeRequestsApprove(UUID id, String idempotencyKey) {
        TenantContext.require();
        return action(idempotencyKey, "approve", id, () -> purges.approve(id));
    }

    @Override
    public ResponseEntity<PurgeRequest> purgeRequestsReject(UUID id, String idempotencyKey, PurgeRequestsRejectRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String reason = Http.required(req, "reason");
        return idempotency.execute(idempotencyKey, req, PurgeRequest.class,
                () -> ResponseEntity.ok(crud.read(purges.reject(id, reason), PurgeRequest.class)));
    }

    @Override
    public ResponseEntity<PurgeRequest> purgeRequestsExecute(UUID id, String idempotencyKey) {
        TenantContext.require();
        return action(idempotencyKey, "execute", id, () -> purges.execute(id));
    }

    @Override
    public ResponseEntity<PurgeRequest> purgeRequestsCancel(UUID id, String idempotencyKey, PurgeRequestsCancelRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String reason = Http.required(req, "reason");
        return idempotency.execute(idempotencyKey, req, PurgeRequest.class,
                () -> ResponseEntity.ok(crud.read(purges.cancel(id, reason), PurgeRequest.class)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> purgeRequestsCertificate(UUID id) {
        TenantContext.require();
        return ResponseEntity.ok(Http.object(json, purges.certificate(id)));
    }

    private ResponseEntity<PurgeRequest> action(String key, String name, UUID id, java.util.function.Supplier<String> op) {
        return idempotency.execute(key, json.createObjectNode().put(name, id.toString()), PurgeRequest.class,
                () -> ResponseEntity.ok(crud.read(op.get(), PurgeRequest.class)));
    }
}
