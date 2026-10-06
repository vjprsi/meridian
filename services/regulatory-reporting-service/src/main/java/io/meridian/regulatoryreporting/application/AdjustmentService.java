package io.meridian.regulatoryreporting.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.regulatoryreporting.domain.Expr;
import io.meridian.regulatoryreporting.domain.Lifecycles;
import io.meridian.regulatoryreporting.domain.ReportEngine;
import io.meridian.regulatoryreporting.infrastructure.Store;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Manual adjustments (overlays) of reported cells under four eyes. Approval overrides the cell value, re-validates
 * the run and renders a new output revision; files of earlier revisions stay (append-only) but can no longer be
 * submitted.
 */
@Service
public class AdjustmentService {
    private final Store store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final RunService runs;
    private final DefinitionService definitions;
    private final Approvals approvals;

    /** Approval resource type for report adjustments in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "report_adjustment";

    public AdjustmentService(Store store, CrudEngine crud, OutboxWriter outbox, JsonMapper json, RunService runs,
                             DefinitionService definitions, Approvals approvals) {
        this.approvals = approvals;
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
        this.runs = runs;
        this.definitions = definitions;
    }

    /** Maker step. */
    @Transactional
    public String record(UUID runId, String cellRef, String adjustedValue, String reason) {
        JsonNode run = store.requireLocked("report_run", runId);
        requireAdjustable(run);
        JsonNode cell = store.row("select row_to_json(t)::text from report_cell t where t.report_run_id = ? and t.cell_ref = ?",
                runId, cellRef).orElseThrow(() -> ApiException.unprocessable("unknown_cell", "Run has no cell " + cellRef));
        String value = adjustedValue;
        if ("NUMBER".equals(cell.get("data_type").asString())) {
            BigDecimal d = Expr.toDecimal(adjustedValue);
            if (d == null) {
                throw Fields.invalid("adjusted_value", "invalid_decimal", "Cell " + cellRef + " is numeric; adjusted_value must be a number");
            }
            String current = Fields.text(cell, "computed_value");
            int scale = current != null && current.contains(".") ? current.length() - current.indexOf('.') - 1 : Math.max(d.scale(), 0);
            value = d.setScale(Math.max(scale, Math.min(d.scale(), 8)), RoundingMode.HALF_EVEN).toPlainString();
        }
        if (store.count("select count(*) from report_adjustment where report_run_id = ? and cell_ref = ? and status = 'PENDING_APPROVAL'",
                runId, cellRef) > 0) {
            throw ApiException.conflict("adjustment_pending", "Cell " + cellRef + " already has an adjustment awaiting approval");
        }
        ObjectNode v = json.createObjectNode();
        v.put("report_run_id", runId.toString());
        v.put("cell_ref", cellRef);
        if (cell.hasNonNull("value")) {
            v.put("original_value", cell.get("value").asString());
        }
        v.put("adjusted_value", value);
        v.put("reason", reason);
        v.put("status", "PENDING_APPROVAL");
        v.put("adjusted_by", TenantContext.principal());
        String inserted = crud.insert("report_adjustment", v);
        // Central maker-checker: the checker decides in the workflow inbox; AdjustmentApprovalHandler applies it.
        JsonNode row = json.readTree(inserted);
        UUID id = UUID.fromString(row.get("id").asString());
        int rowVersion = row.path("row_version").asInt(1);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("report_run_id", runId.toString());
        payload.put("run_revision", run.path("revision").asInt(1));
        payload.put("cell_ref", cellRef);
        payload.put("original_value", Fields.text(row, "original_value"));
        payload.put("adjusted_value", value);
        payload.put("reason", reason);
        payload.put("row_version", rowVersion);
        approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                "Report adjustment " + cellRef + " on run " + runId, payload)
                .withSummary(cellRef + ": " + Fields.text(row, "original_value") + " to " + value + " (" + reason + ")"),
                "adjust:" + id + ":" + rowVersion);
        return inserted;
    }

    /** True while the adjustment is PENDING_APPROVAL with the submitted value and row_version unchanged. */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID adjustmentId, String adjustedValue, Integer rowVersion) {
        return store.find("report_adjustment", adjustmentId)
                .filter(a -> "PENDING_APPROVAL".equals(a.path("status").asString()))
                .filter(a -> adjustedValue == null || adjustedValue.equals(a.path("adjusted_value").asString(null)))
                .filter(a -> rowVersion == null || rowVersion == a.path("row_version").asInt())
                .isPresent();
    }

    /** Checker step: applies the overlay and produces the next revision. */
    @Transactional
    public String approve(UUID adjustmentId, String comment) {
        JsonNode adj = store.requireLocked("report_adjustment", adjustmentId);
        Lifecycles.adjustment(adj.get("status").asString(), "APPROVED");
        if (TenantContext.principal().equals(Fields.text(adj, "adjusted_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The maker of an adjustment cannot approve it");
        }
        UUID runId = RunService.uuid(adj, "report_run_id");
        JsonNode run = store.requireLocked("report_run", runId);
        requireAdjustable(run);
        store.update("update report_cell set value = ?, is_adjusted = true, updated_by = ? where report_run_id = ? and cell_ref = ?",
                adj.get("adjusted_value").asString(), TenantContext.principal(), runId, adj.get("cell_ref").asString());
        JsonNode row = store.updateReturning("""
                update report_adjustment t set status = 'APPROVED', approved_by = ?, decision_comment = ?, decided_at = now(),
                       updated_by = ? where t.id = ? returning row_to_json(t)::text""",
                TenantContext.principal(), comment, TenantContext.principal(), adjustmentId);
        JsonNode def = store.require("report_definition", RunService.uuid(run, "report_definition_id"));
        JsonNode version = store.require("report_definition_version", RunService.uuid(run, "report_definition_version_id"));
        // Derived cells (totals, ratios) follow the adjusted inputs unless they were adjusted themselves.
        DefinitionService.Compiled compiled = definitions.compile(version);
        List<ReportEngine.Cell> current = runs.loadCells(runId);
        Set<String> pinned = new HashSet<>();
        store.rows("select json_build_object('ref', cell_ref)::text from report_cell where report_run_id = ? and is_adjusted",
                runId).forEach(n -> pinned.add(n.get("ref").asString()));
        List<ReportEngine.Cell> cells = ReportEngine.rederive(compiled.mappings(), current, pinned, runs.params(run));
        for (int i = 0; i < cells.size(); i++) {
            if (!Objects.equals(cells.get(i).value(), current.get(i).value())) {
                store.update("update report_cell set value = ?, updated_by = ? where report_run_id = ? and cell_ref = ?",
                        cells.get(i).value(), TenantContext.principal(), runId, cells.get(i).cellRef());
            }
        }
        runs.finish(run, def, version, compiled.rules(), cells, run.path("revision").asInt(1) + 1, null);
        outbox.publish("reporting.report_adjustment.approved", "report_adjustment", adjustmentId, row);
        approvals.cancelOpen(APPROVAL_RESOURCE, adjustmentId, null, "approved directly");
        return row.toString();
    }

    @Transactional
    public String reject(UUID adjustmentId, String reason) {
        JsonNode adj = store.requireLocked("report_adjustment", adjustmentId);
        Lifecycles.adjustment(adj.get("status").asString(), "REJECTED");
        if (TenantContext.principal().equals(Fields.text(adj, "adjusted_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The maker of an adjustment cannot decide on it");
        }
        JsonNode row = store.updateReturning("""
                update report_adjustment t set status = 'REJECTED', approved_by = ?, decision_comment = ?, decided_at = now(),
                       updated_by = ? where t.id = ? returning row_to_json(t)::text""",
                TenantContext.principal(), reason, TenantContext.principal(), adjustmentId);
        outbox.publish("reporting.report_adjustment.rejected", "report_adjustment", adjustmentId, row);
        approvals.cancelOpen(APPROVAL_RESOURCE, adjustmentId, null, "rejected directly");
        return row.toString();
    }

    private void requireAdjustable(JsonNode run) {
        String st = run.get("status").asString();
        if (!Lifecycles.RUN_PRODUCED.contains(st)) {
            throw ApiException.conflict("run_not_adjustable", "Only produced runs can be adjusted (run is " + st + ")");
        }
        if (run.hasNonNull("superseded_by_run_id")) {
            throw ApiException.conflict("run_superseded", "The run was superseded by " + run.get("superseded_by_run_id").asString());
        }
        if (store.count("select count(*) from report_submission where report_run_id = ? and status in ('SUBMITTED', 'ACKNOWLEDGED', 'ACCEPTED')",
                RunService.uuid(run, "id")) > 0) {
            throw ApiException.conflict("run_already_submitted", "The run was already submitted to the regulator; rerun it instead");
        }
    }
}
