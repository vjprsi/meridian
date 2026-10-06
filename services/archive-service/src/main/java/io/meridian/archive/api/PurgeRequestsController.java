package io.meridian.archive.api;

import io.meridian.archive.api.generated.PurgeRequestsApi;
import io.meridian.archive.api.generated.model.PurgeRequest;
import io.meridian.archive.api.generated.model.PurgeRequestCreate;
import io.meridian.archive.api.generated.model.PurgeRequestsCancelRequest;
import io.meridian.archive.application.PurgeService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Purge requests with four-eyes approval. The contract has no approve/reject operation although the domain
 * defines {@code PENDING_APPROVAL -> APPROVED / REJECTED} and the {@code purge_request.approved} event, so
 * {@code POST /{id}/approve} and {@code POST /{id}/reject} are added here.
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

    @PostMapping(value = "/v1/archive/purge-requests/{id}/approve", produces = "application/json")
    public ResponseEntity<PurgeRequest> purgeRequestsApprove(@PathVariable("id") UUID id,
                                                             @RequestHeader("Idempotency-Key") String idempotencyKey) {
        TenantContext.require();
        return action(idempotencyKey, "approve", id, () -> purges.approve(id));
    }

    @PostMapping(value = "/v1/archive/purge-requests/{id}/reject", produces = "application/json")
    public ResponseEntity<PurgeRequest> purgeRequestsReject(@PathVariable("id") UUID id,
                                                            @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                            @RequestBody JsonNode body) {
        TenantContext.require();
        String reason = Http.required(body, "reason");
        return idempotency.execute(idempotencyKey, body, PurgeRequest.class,
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
    public ResponseEntity<Void> purgeRequestsCertificate(UUID id) {
        TenantContext.require();
        return Http.ok(purges.certificate(id));
    }

    private ResponseEntity<PurgeRequest> action(String key, String name, UUID id, java.util.function.Supplier<String> op) {
        return idempotency.execute(key, json.createObjectNode().put(name, id.toString()), PurgeRequest.class,
                () -> ResponseEntity.ok(crud.read(op.get(), PurgeRequest.class)));
    }
}
