package io.meridian.pricingrules.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.pricingrules.api.generated.DecisionTablesApi;
import io.meridian.pricingrules.api.generated.model.DecisionTable;
import io.meridian.pricingrules.api.generated.model.DecisionTableCreate;
import io.meridian.pricingrules.api.generated.model.DecisionTableUpdate;
import io.meridian.pricingrules.api.generated.model.DecisionTableVersionPage;
import io.meridian.pricingrules.api.generated.model.DecisionTablesEvaluateRequest;
import io.meridian.pricingrules.api.generated.model.DecisionTablesImportDmnRequest;
import io.meridian.pricingrules.api.generated.model.RuleEvaluationLog;
import io.meridian.pricingrules.application.DecisionTableService;
import io.meridian.pricingrules.infrastructure.PricingStore;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Decision tables: lifecycle-guarded CRUD, evaluation of the published version, DMN import. */
@RestController
public class DecisionTablesController implements DecisionTablesApi {
    private final DecisionTableService service;
    private final PricingStore store;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public DecisionTablesController(DecisionTableService service, PricingStore store, CrudEngine crud,
                                    Idempotency idempotency, JsonMapper json) {
        this.service = service;
        this.store = store;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<DecisionTable> decisionTablesCreate(String idempotencyKey, DecisionTableCreate body) {
        TenantContext.require();
        service.validateNewTable(json.valueToTree(body));
        return crud.create("decision_table", DecisionTable.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<DecisionTable> decisionTablesUpdate(UUID id, String ifMatch, DecisionTableUpdate body) {
        TenantContext.require();
        String row = service.updateTable(id, json.valueToTree(body), ApiSupport.ifMatch(ifMatch));
        return ApiSupport.withEtag(HttpStatus.OK, crud.read(row, DecisionTable.class), json.readTree(row));
    }

    @Override
    public ResponseEntity<RuleEvaluationLog> decisionTablesEvaluate(UUID id, String idempotencyKey,
                                                                    DecisionTablesEvaluateRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String caller = ApiSupport.header("X-Caller-Service");
        DecisionTableService.EvaluationRequest r = new DecisionTableService.EvaluationRequest(
                PricingStore.toMap(req.get("inputs")), ApiSupport.text(req, "subject_type"),
                req.hasNonNull("subject_id") ? UUID.fromString(req.get("subject_id").asString()) : null,
                caller == null || caller.isBlank() ? null : caller, ApiSupport.correlationId());
        return idempotency.execute(idempotencyKey, ApiSupport.idemRequest("evaluate", id, body), RuleEvaluationLog.class,
                () -> ResponseEntity.ok(crud.read(service.evaluate(id, r), RuleEvaluationLog.class)));
    }

    @Override
    public ResponseEntity<DecisionTableVersionPage> decisionTablesVersions(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        store.require("decision_table", id);
        return crud.list("decision_table_version", DecisionTableVersionPage.class,
                Crud.params().with("decision_table_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<DecisionTable> decisionTablesImportDmn(String idempotencyKey, DecisionTablesImportDmnRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, ApiSupport.idemRequest("import-dmn", null, body), DecisionTable.class,
                () -> {
                    String row = service.importDmn(ApiSupport.text(req, "code"), ApiSupport.text(req, "dmn_xml"));
                    return ResponseEntity.ok(crud.read(row, DecisionTable.class));
                });
    }
}
