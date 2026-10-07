package io.meridian.regulatoryreporting.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.regulatoryreporting.domain.DefinitionException;
import io.meridian.regulatoryreporting.domain.Expr;
import io.meridian.regulatoryreporting.domain.FilingCalendar;
import io.meridian.regulatoryreporting.domain.Lifecycles;
import io.meridian.regulatoryreporting.domain.ReportEngine;
import io.meridian.regulatoryreporting.domain.ReportRenderer;
import io.meridian.regulatoryreporting.domain.ReportValidator;
import io.meridian.regulatoryreporting.infrastructure.Store;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Report runs: collect (source services) -> transform (field mappings) -> validate (rule set) -> render (XML / CSV /
 * JSON with SHA-256). The run is marked RUNNING in its own transaction before the source calls (so it can be
 * cancelled meanwhile) and finished in a second one; a run cancelled in between keeps CANCELLED. Runs with ERROR
 * findings finish PARTIALLY_SUCCEEDED: files exist but cannot be submitted until adjustments fix them.
 */
@Service
public class RunService {
    private static final Logger log = LoggerFactory.getLogger(RunService.class);
    static final Set<String> CLIENT_TRIGGERS = Set.of("MANUAL", "API");

    private final Store store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final DefinitionService definitions;
    private final SourceCollector collector;

    public RunService(Store store, CrudEngine crud, OutboxWriter outbox, JsonMapper json, TransactionTemplate tx,
                      DefinitionService definitions, SourceCollector collector) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
        this.tx = tx;
        this.definitions = definitions;
        this.collector = collector;
    }

    /** Creates a run from an API request; executes it unless {@code execute=false}. Returns the run row. */
    public String create(JsonNode req) {
        String trigger = Optional.ofNullable(Fields.text(req, "trigger")).orElse("MANUAL");
        if (!CLIENT_TRIGGERS.contains(trigger)) {
            throw Fields.invalid("trigger", "trigger_reserved", "trigger " + trigger + " is set by the system; use MANUAL or API");
        }
        JsonNode params = req.hasNonNull("parameters") ? req.get("parameters") : null;
        if (params != null && !params.isObject()) {
            throw Fields.invalid("parameters", "invalid_type", "parameters must be an object");
        }
        UUID id = tx.execute(s -> insert(Fields.requireUuid(req, "report_definition_id"), Fields.requireUuid(req, "legal_entity_id"),
                Fields.requireDate(req, "as_of_date"), Fields.date(req, "period_start"), Fields.date(req, "period_end"),
                params, trigger, null, null));
        return Fields.bool(req, "execute", true) ? execute(id) : store.require("report_run", id).toString();
    }

    /** Inserts a QUEUED run bound to the version in force at the reporting date. Must run in a transaction. */
    UUID insert(UUID definitionId, UUID legalEntityId, LocalDate asOf, LocalDate periodStart, LocalDate periodEnd,
                JsonNode params, String trigger, UUID scheduleId, UUID supersedes) {
        JsonNode def = store.find("report_definition", definitionId).orElseThrow(() ->
                ApiException.unprocessable("definition_not_found", "Report definition " + definitionId + " does not exist"));
        if (!"ACTIVE".equals(def.get("status").asString())) {
            throw ApiException.unprocessable("definition_not_active", "Report definition " + def.get("code").asString()
                    + " is " + def.get("status").asString() + "; publish a version first");
        }
        JsonNode version = definitions.versionAt(definitionId, asOf).orElseThrow(() -> ApiException.unprocessable(
                "no_effective_version", "No published version of " + def.get("code").asString() + " is effective on " + asOf));
        String frequency = def.get("frequency").asString();
        LocalDate pe = periodEnd == null ? asOf : periodEnd;
        LocalDate ps = periodStart == null ? FilingCalendar.periodStart(frequency, pe) : periodStart;
        if (ps.isAfter(pe)) {
            throw Fields.invalid("period_start", "invalid_range", "period_start must not be after period_end");
        }
        UUID id = Ids.newId();
        ObjectNode v = json.createObjectNode();
        v.put("id", id.toString());
        v.put("report_definition_id", definitionId.toString());
        v.put("report_definition_version_id", version.get("id").asString());
        v.put("legal_entity_id", legalEntityId.toString());
        v.put("as_of_date", asOf.toString());
        v.put("period_start", ps.toString());
        v.put("period_end", pe.toString());
        if (params != null) {
            v.set("parameters", params);
        }
        v.put("trigger", trigger);
        v.put("status", "QUEUED");
        v.put("revision", 1);
        if (scheduleId != null) {
            v.put("report_schedule_id", scheduleId.toString());
        }
        if (supersedes != null) {
            v.put("supersedes_run_id", supersedes.toString());
        }
        crud.insert("report_run", v);
        return id;
    }

    /** Executes a QUEUED run end to end; returns the final run row. */
    public String execute(UUID runId) {
        JsonNode run = tx.execute(s -> {
            JsonNode r = store.requireLocked("report_run", runId);
            Lifecycles.run(r.get("status").asString(), "RUNNING");
            return store.updateReturning("""
                    update report_run t set status = 'RUNNING', started_at = now(), updated_by = ?
                    where t.id = ? returning row_to_json(t)::text""", TenantContext.principal(), runId);
        });
        JsonNode def = store.require("report_definition", uuid(run, "report_definition_id"));
        JsonNode version = store.require("report_definition_version", uuid(run, "report_definition_version_id"));
        Map<String, Object> params = params(run);
        DefinitionService.Compiled compiled;
        SourceCollector.Collected collected;
        List<ReportEngine.Cell> cells;
        try {
            compiled = definitions.compile(version);
            collected = collector.collect(version.get("data_sources"), builtIns(run), params);
            // expressions see the run parameters, the looked-up ones and the run's built-ins (param_legal_entity_id,
            // param_as_of_date, param_period_start, param_period_end); explicit parameters win
            Map<String, Object> effective = new LinkedHashMap<>(params);
            collected.resolvedParams().forEach(effective::putIfAbsent);
            builtIns(run).forEach(effective::putIfAbsent);
            cells = ReportEngine.compute(compiled.mappings(), collected.sources(), effective);
        } catch (ApiException | Expr.ExprException | DefinitionException e) {
            String msg = e instanceof ApiException a ? a.code() + ": " + a.getMessage() : e.getMessage();
            log.info("report run {} failed: {}", runId, msg);
            return tx.execute(s -> fail(runId, msg));
        }
        return tx.execute(s -> complete(runId, def, version, compiled, collected, cells));
    }

    private String fail(UUID runId, String message) {
        JsonNode r = store.requireLocked("report_run", runId);
        if (!"RUNNING".equals(r.get("status").asString())) {
            return r.toString();
        }
        JsonNode row = store.updateReturning("""
                update report_run t set status = 'FAILED', error = ?, finished_at = now(), updated_by = ?
                where t.id = ? returning row_to_json(t)::text""", Fields.clip(message, 4000), TenantContext.principal(), runId);
        outbox.publish("reporting.report_run.failed", "report_run", runId, row);
        return row.toString();
    }

    private String complete(UUID runId, JsonNode def, JsonNode version, DefinitionService.Compiled compiled,
                            SourceCollector.Collected collected, List<ReportEngine.Cell> cells) {
        JsonNode r = store.requireLocked("report_run", runId);
        if (!"RUNNING".equals(r.get("status").asString())) {
            return r.toString(); // cancelled while collecting
        }
        List<ObjectNode> rows = new ArrayList<>();
        for (ReportEngine.Cell c : cells) {
            ObjectNode n = json.createObjectNode();
            n.put("report_run_id", runId.toString());
            n.put("cell_ref", c.cellRef());
            putOrNull(n, "label", Fields.clip(c.label(), 255));
            n.put("data_type", c.type());
            putOrNull(n, "value", c.value());
            putOrNull(n, "computed_value", c.value());
            n.put("is_adjusted", false);
            putOrNull(n, "source_name", c.sourceName());
            n.put("source_count", c.sourceCount());
            n.set("source_records", json.valueToTree(c.sourceRecords()));
            rows.add(n);
        }
        crud.insertMany("report_cell", rows);
        return finish(r, def, version, compiled.rules(), cells, 1, collected.snapshotId());
    }

    /**
     * Validates and renders the current cells as {@code revision}, updates the run's counts and status and publishes
     * the outcome. Used at completion (revision 1) and after an approved adjustment (next revision).
     */
    String finish(JsonNode run, JsonNode def, JsonNode version, List<ReportValidator.Rule> rules,
                  List<ReportEngine.Cell> cells, int revision, String snapshotId) {
        UUID runId = uuid(run, "id");
        Map<String, Object> values = new LinkedHashMap<>();
        cells.forEach(c -> values.put(c.cellRef(), c.typedValue()));
        List<ReportValidator.Finding> findings = ReportValidator.validate(rules, values, params(run));
        int errors = 0;
        int warnings = 0;
        List<ObjectNode> results = new ArrayList<>();
        for (ReportValidator.Finding f : findings) {
            if ("ERROR".equals(f.severity())) {
                errors++;
            } else if ("WARNING".equals(f.severity())) {
                warnings++;
            }
            ObjectNode n = json.createObjectNode();
            n.put("report_run_id", runId.toString());
            n.put("revision", revision);
            n.put("rule_code", f.ruleCode());
            n.put("severity", f.severity());
            n.put("message", f.message());
            putOrNull(n, "cell_ref", f.cellRef());
            putOrNull(n, "expected_value", Fields.clip(f.expected(), 255));
            putOrNull(n, "actual_value", Fields.clip(f.actual(), 255));
            results.add(n);
        }
        crud.insertMany("report_validation_result", results);

        ReportRenderer.Header header = new ReportRenderer.Header(def.get("code").asString(), def.get("name").asString(),
                def.get("regulator").asString(), def.get("jurisdiction").asString().trim(),
                version.get("definition_version").asInt(), Fields.text(version, "xbrl_taxonomy"),
                run.get("legal_entity_id").asString(), run.get("as_of_date").asString(), Fields.text(run, "period_start"),
                Fields.text(run, "period_end"), currency(run), revision);
        for (String format : ReportRenderer.formatsFor(def.get("format").asString())) {
            ReportRenderer.Rendered file = ReportRenderer.render(format, header, cells);
            ObjectNode o = json.createObjectNode();
            o.put("report_run_id", runId.toString());
            o.put("format", file.format());
            o.put("revision", revision);
            o.put("file_name", file.fileName());
            o.put("content_type", file.contentType());
            o.put("size_bytes", file.sizeBytes());
            o.put("sha256", file.sha256());
            o.put("record_count", file.recordCount());
            o.put("content", file.content());
            crud.insert("report_output", o);
        }
        String status = errors > 0 ? "PARTIALLY_SUCCEEDED" : "SUCCEEDED";
        Lifecycles.run(run.get("status").asString(), status);
        JsonNode row = store.updateReturning("""
                update report_run t set status = ?, revision = ?, row_count = ?, error_count = ?, warning_count = ?,
                       data_snapshot_id = coalesce(?, t.data_snapshot_id), finished_at = now(), error = null, updated_by = ?
                where t.id = ? returning row_to_json(t)::text""",
                status, revision, cells.size(), errors, warnings, snapshotId, TenantContext.principal(), runId);
        if (revision == 1) {
            outbox.publish("reporting.report_run.completed", "report_run", runId, row);
        }
        if (errors > 0) {
            ObjectNode payload = ((ObjectNode) row).deepCopy();
            ArrayNode fs = payload.putArray("validation_errors");
            findings.stream().filter(f -> "ERROR".equals(f.severity())).limit(50).forEach(f -> fs.addObject()
                    .put("rule_code", f.ruleCode()).put("cell_ref", f.cellRef()).put("message", f.message()));
            outbox.publish("reporting.report_run.validation_failed", "report_run", runId, payload);
        }
        return row.toString();
    }

    /** Cells of a run as computed / adjusted values (for re-validation). */
    List<ReportEngine.Cell> loadCells(UUID runId) {
        List<ReportEngine.Cell> out = new ArrayList<>();
        for (JsonNode c : store.rows("select row_to_json(t)::text from report_cell t where t.report_run_id = ? order by t.id", runId)) {
            out.add(new ReportEngine.Cell(c.get("cell_ref").asString(), Fields.text(c, "label"), c.get("data_type").asString(),
                    Fields.text(c, "value"), Fields.text(c, "source_name"), c.path("source_count").asInt(), List.of()));
        }
        return out;
    }

    /** A corrected run superseding a finished one (same entity, date and period; version in force now). */
    public String rerun(UUID runId, JsonNode params, String reason) {
        UUID id = tx.execute(s -> {
            JsonNode r = store.requireLocked("report_run", runId);
            String st = r.get("status").asString();
            if (!Set.of("SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED").contains(st)) {
                throw ApiException.conflict("invalid_transition", "Only finished runs can be rerun (run is " + st + ")");
            }
            if (r.hasNonNull("superseded_by_run_id")) {
                throw ApiException.conflict("already_superseded", "Run was already superseded by "
                        + r.get("superseded_by_run_id").asString());
            }
            ObjectNode p = params != null && params.isObject() ? (ObjectNode) params
                    : r.hasNonNull("parameters") ? (ObjectNode) r.get("parameters") : null;
            if (reason != null) {
                p = p == null ? json.createObjectNode() : p.deepCopy();
                p.put("rerun_reason", reason);
            }
            UUID created = insert(uuid(r, "report_definition_id"), uuid(r, "legal_entity_id"),
                    LocalDate.parse(r.get("as_of_date").asString()), date(r, "period_start"), date(r, "period_end"), p,
                    "RESUBMISSION", null, runId);
            store.update("update report_run set superseded_by_run_id = ?, updated_by = ? where id = ?", created,
                    TenantContext.principal(), runId);
            return created;
        });
        return execute(id);
    }

    /** Cancels a QUEUED or RUNNING run. */
    public String cancel(UUID runId, String reason) {
        return tx.execute(s -> {
            JsonNode r = store.requireLocked("report_run", runId);
            Lifecycles.run(r.get("status").asString(), "CANCELLED");
            JsonNode row = store.updateReturning("""
                    update report_run t set status = 'CANCELLED', error = ?, finished_at = now(), updated_by = ?
                    where t.id = ? returning row_to_json(t)::text""",
                    "Cancelled by " + TenantContext.principal() + (reason == null ? "" : ": " + reason),
                    TenantContext.principal(), runId);
            outbox.publish("reporting.report_run.cancelled", "report_run", runId, row);
            return row.toString();
        });
    }

    /** The cell, its mapping, adjustments and contributing source records. */
    public ObjectNode drilldown(UUID runId, String cellRef) {
        JsonNode run = store.require("report_run", runId);
        JsonNode cell = store.row("select row_to_json(t)::text from report_cell t where t.report_run_id = ? and t.cell_ref = ?",
                runId, cellRef).orElseThrow(() -> new ApiException(404, "cell_not_found",
                "Run " + runId + " has no cell " + cellRef));
        JsonNode version = store.require("report_definition_version", uuid(run, "report_definition_version_id"));
        ObjectNode out = json.createObjectNode();
        out.put("report_run_id", runId.toString());
        out.put("cell_ref", cellRef);
        out.set("label", cell.get("label"));
        out.put("data_type", cell.get("data_type").asString());
        out.set("value", cell.get("value"));
        out.set("computed_value", cell.get("computed_value"));
        out.put("is_adjusted", cell.path("is_adjusted").asBoolean());
        out.put("as_of_date", run.get("as_of_date").asString());
        out.put("data_snapshot_id", Fields.text(run, "data_snapshot_id"));
        out.put("definition_version", version.get("definition_version").asInt());
        for (JsonNode m : version.path("field_mappings")) {
            if (cellRef.equals(m.path("cell_ref").asString())) {
                out.set("mapping", m);
            }
        }
        out.set("source_name", cell.get("source_name"));
        out.put("source_count", cell.path("source_count").asInt());
        out.set("source_records", cell.hasNonNull("source_records") ? cell.get("source_records") : json.createArrayNode());
        out.put("source_records_truncated", cell.path("source_count").asInt() > out.get("source_records").size());
        ArrayNode adj = out.putArray("adjustments");
        store.rows("select row_to_json(t)::text from report_adjustment t where t.report_run_id = ? and t.cell_ref = ? order by t.id",
                runId, cellRef).forEach(adj::add);
        return out;
    }

    Map<String, Object> params(JsonNode run) {
        if (run.hasNonNull("parameters") && run.get("parameters").isObject()) {
            return json.convertValue(run.get("parameters"), new TypeReference<Map<String, Object>>() {});
        }
        return Map.of();
    }

    private Map<String, Object> builtIns(JsonNode run) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("as_of_date", run.get("as_of_date").asString());
        b.put("period_start", Fields.text(run, "period_start"));
        b.put("period_end", Fields.text(run, "period_end"));
        b.put("legal_entity_id", run.get("legal_entity_id").asString());
        b.put("reporting_year", LocalDate.parse(run.get("as_of_date").asString()).getYear());
        String ps = Fields.text(run, "period_start");
        String pe = Fields.text(run, "period_end");
        if (ps != null) {
            b.put("period_start_ts", ps + "T00:00:00Z");
        }
        if (pe != null) {
            b.put("period_end_ts", LocalDate.parse(pe).plusDays(1) + "T00:00:00Z");
        }
        return b;
    }

    private static String currency(JsonNode run) {
        JsonNode p = run.path("parameters");
        return p.hasNonNull("currency") ? p.get("currency").asString() : null;
    }

    private static void putOrNull(ObjectNode n, String f, String v) {
        if (v == null) {
            n.putNull(f);
        } else {
            n.put(f, v);
        }
    }

    static UUID uuid(JsonNode n, String f) {
        return UUID.fromString(n.get(f).asString());
    }

    private static LocalDate date(JsonNode n, String f) {
        return n.hasNonNull(f) ? LocalDate.parse(n.get(f).asString()) : null;
    }
}
