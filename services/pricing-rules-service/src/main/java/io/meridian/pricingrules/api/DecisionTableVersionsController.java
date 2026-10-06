package io.meridian.pricingrules.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.pricingrules.api.generated.DecisionTableVersionsApi;
import io.meridian.pricingrules.api.generated.model.DecisionTableVersion;
import io.meridian.pricingrules.api.generated.model.DecisionTableVersionCreate;
import io.meridian.pricingrules.api.generated.model.DecisionTableVersionUpdate;
import io.meridian.pricingrules.api.generated.model.DecisionTableVersionsApproveRequest;
import io.meridian.pricingrules.api.generated.model.DecisionTableVersionsRejectRequest;
import io.meridian.pricingrules.api.generated.model.DecisionTableVersionsSubmitRequest;
import io.meridian.pricingrules.api.generated.model.DecisionTableVersionsTestRequest;
import io.meridian.pricingrules.application.DecisionTableService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Decision table versions: draft editing, test runs, maker-checker submit/approve/reject, DMN export. */
@RestController
public class DecisionTableVersionsController implements DecisionTableVersionsApi {
    private final DecisionTableService service;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public DecisionTableVersionsController(DecisionTableService service, CrudEngine crud, Idempotency idempotency,
                                           JsonMapper json) {
        this.service = service;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<DecisionTableVersion> decisionTableVersionsCreate(String idempotencyKey,
                                                                            DecisionTableVersionCreate body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, body, DecisionTableVersion.class, () -> {
            String row = service.createVersion(req);
            return ApiSupport.withEtag(HttpStatus.CREATED, crud.read(row, DecisionTableVersion.class), json.readTree(row));
        });
    }

    @Override
    public ResponseEntity<DecisionTableVersion> decisionTableVersionsUpdate(UUID id, String ifMatch,
                                                                            DecisionTableVersionUpdate body) {
        TenantContext.require();
        String row = service.updateVersion(id, json.valueToTree(body), ApiSupport.ifMatch(ifMatch));
        return ApiSupport.withEtag(HttpStatus.OK, crud.read(row, DecisionTableVersion.class), json.readTree(row));
    }

    @Override
    public ResponseEntity<DecisionTableVersion> decisionTableVersionsSubmit(UUID id, String idempotencyKey,
                                                                            DecisionTableVersionsSubmitRequest body) {
        JsonNode req = tree(body);
        return action("submit", id, idempotencyKey, body, () -> service.submit(id, ApiSupport.text(req, "comment")));
    }

    @Override
    public ResponseEntity<DecisionTableVersion> decisionTableVersionsApprove(UUID id, String idempotencyKey,
                                                                             DecisionTableVersionsApproveRequest body) {
        JsonNode req = tree(body);
        return action("approve", id, idempotencyKey, body, () -> service.approve(id, ApiSupport.text(req, "comment")));
    }

    @Override
    public ResponseEntity<DecisionTableVersion> decisionTableVersionsReject(UUID id, String idempotencyKey,
                                                                            DecisionTableVersionsRejectRequest body) {
        JsonNode req = tree(body);
        return action("reject", id, idempotencyKey, body, () -> service.reject(id, ApiSupport.text(req, "reason")));
    }

    @Override
    public ResponseEntity<Void> decisionTableVersionsTest(UUID id, String idempotencyKey,
                                                          DecisionTableVersionsTestRequest body) {
        TenantContext.require();
        JsonNode req = tree(body);
        return ApiSupport.asVoid(idempotency.execute(idempotencyKey, ApiSupport.idemRequest("test", id, body), String.class,
                () -> ResponseEntity.ok(service.test(id, req.get("test_cases")).toString())));
    }

    @Override
    public ResponseEntity<Void> decisionTableVersionsExportDmn(UUID id) {
        TenantContext.require();
        return ApiSupport.document(HttpStatus.OK, MediaType.APPLICATION_XML, service.exportDmn(id));
    }

    private ResponseEntity<DecisionTableVersion> action(String op, UUID id, String key, Object body, Supplier<String> run) {
        TenantContext.require();
        return idempotency.execute(key, ApiSupport.idemRequest(op, id, body), DecisionTableVersion.class,
                () -> {
                    String row = run.get();
                    return ApiSupport.withEtag(HttpStatus.OK, crud.read(row, DecisionTableVersion.class), json.readTree(row));
                });
    }

    private JsonNode tree(Object body) {
        return body == null ? json.createObjectNode() : json.valueToTree(body);
    }
}
