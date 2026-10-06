package io.meridian.regulatoryreporting.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.regulatoryreporting.api.generated.ReportRunsApi;
import io.meridian.regulatoryreporting.api.generated.model.ReportAdjustment;
import io.meridian.regulatoryreporting.api.generated.model.ReportAdjustmentPage;
import io.meridian.regulatoryreporting.api.generated.model.ReportCellPage;
import io.meridian.regulatoryreporting.api.generated.model.ReportOutputPage;
import io.meridian.regulatoryreporting.api.generated.model.ReportRun;
import io.meridian.regulatoryreporting.api.generated.model.ReportRunCreate;
import io.meridian.regulatoryreporting.api.generated.model.ReportRunsAdjustmentsRequest;
import io.meridian.regulatoryreporting.api.generated.model.ReportRunsCancelRequest;
import io.meridian.regulatoryreporting.api.generated.model.ReportRunsRerunRequest;
import io.meridian.regulatoryreporting.api.generated.model.ReportValidationResultPage;
import io.meridian.regulatoryreporting.application.AdjustmentService;
import io.meridian.regulatoryreporting.application.Fields;
import io.meridian.regulatoryreporting.application.RunService;
import io.meridian.regulatoryreporting.infrastructure.Store;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Report runs: create (and execute), execute, rerun, cancel, adjustments (maker), files, cells, drilldown. */
@RestController
public class ReportRunsController implements ReportRunsApi {
    private final RunService runs;
    private final AdjustmentService adjustments;
    private final Idempotency idempotency;
    private final Responses responses;
    private final CrudEngine crud;
    private final Store store;
    private final JsonMapper json;

    public ReportRunsController(RunService runs, AdjustmentService adjustments, Idempotency idempotency,
                                Responses responses, CrudEngine crud, Store store, JsonMapper json) {
        this.runs = runs;
        this.adjustments = adjustments;
        this.idempotency = idempotency;
        this.responses = responses;
        this.crud = crud;
        this.store = store;
        this.json = json;
    }

    @Override
    public ResponseEntity<ReportRun> reportRunsCreate(String idempotencyKey, ReportRunCreate body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, body, ReportRun.class,
                () -> responses.created(runs.create(req), ReportRun.class));
    }

    @Override
    public ResponseEntity<ReportRun> reportRunsExecute(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, responses.action("execute", id, null), ReportRun.class,
                () -> responses.ok(runs.execute(id), ReportRun.class));
    }

    @Override
    public ResponseEntity<ReportRun> reportRunsRerun(UUID id, String idempotencyKey, ReportRunsRerunRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("rerun", id, req), ReportRun.class,
                () -> responses.created(runs.rerun(id, req.get("parameters"), Fields.text(req, "reason")), ReportRun.class));
    }

    @Override
    public ResponseEntity<ReportRun> reportRunsCancel(UUID id, String idempotencyKey, ReportRunsCancelRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("cancel", id, req), ReportRun.class,
                () -> responses.ok(runs.cancel(id, Fields.text(req, "reason")), ReportRun.class));
    }

    @Override
    public ResponseEntity<ReportAdjustment> reportRunsAddAdjustments(UUID id, String idempotencyKey,
                                                                    ReportRunsAdjustmentsRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String cell = Fields.requireText(req, "cell_ref");
        String value = Fields.requireText(req, "adjusted_value");
        String reason = Fields.requireText(req, "reason");
        return idempotency.execute(idempotencyKey, responses.action("adjust", id, req), ReportAdjustment.class,
                () -> responses.created(adjustments.record(id, cell, value, reason), ReportAdjustment.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> reportRunsDrilldown(UUID id, String cellRef) {
        TenantContext.require();
        return responses.object(runs.drilldown(id, cellRef));
    }

    @Override
    public ResponseEntity<ReportOutputPage> reportRunsOutputs(UUID id, Integer limit, String cursor) {
        return child("report_output", ReportOutputPage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<ReportCellPage> reportRunsCells(UUID id, Integer limit, String cursor) {
        return child("report_cell", ReportCellPage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<ReportValidationResultPage> reportRunsValidationResults(UUID id, Integer limit, String cursor) {
        return child("report_validation_result", ReportValidationResultPage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<ReportAdjustmentPage> reportRunsAdjustments(UUID id, Integer limit, String cursor) {
        return child("report_adjustment", ReportAdjustmentPage.class, id, limit, cursor);
    }

    private <T> ResponseEntity<T> child(String table, Class<T> type, UUID runId, Integer limit, String cursor) {
        TenantContext.require();
        store.require("report_run", runId);
        return crud.list(table, type, Crud.params().with("report_run_id", runId).with("limit", limit).with("cursor", cursor));
    }
}
