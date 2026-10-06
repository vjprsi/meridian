package io.meridian.pricingrules.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.pricingrules.domain.Canonical;
import io.meridian.pricingrules.domain.DecisionEngine;
import io.meridian.pricingrules.domain.DecisionTableException;
import io.meridian.pricingrules.domain.DecisionTableModel;
import io.meridian.pricingrules.domain.DmnXml;
import io.meridian.pricingrules.domain.VersionLifecycle;
import io.meridian.pricingrules.domain.VersionLifecycle.Action;
import io.meridian.pricingrules.infrastructure.PricingStore;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Decision tables and their versions: definition validation, maker-checker publication, evaluation with an
 * append-only explainability log, test runs and DMN import/export.
 */
@Service
public class DecisionTableService {
    static final String TABLE = "decision_table";
    static final String VERSION = "decision_table_version";
    static final String LOG = "rule_evaluation_log";

    private final PricingStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public DecisionTableService(PricingStore store, CrudEngine crud, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
    }

    // ------------------------------------------------------------------ tables

    /** Validates a new table: status is lifecycle-managed and always starts as DRAFT. */
    public void validateNewTable(JsonNode req) {
        String status = text(req, "status");
        if (status != null && !VersionLifecycle.DRAFT.equals(status)) {
            throw ApiException.unprocessable("invalid_initial_status", "A decision table is created as DRAFT");
        }
    }

    @Transactional
    public String updateTable(UUID id, JsonNode req, Integer expectedVersion) {
        JsonNode current = store.lock(TABLE, id);
        String status = current.get("status").asString();
        ObjectNode values = crud.toColumns(TABLE, req);
        if (values.has("status") && !values.get("status").asString().equals(status)) {
            String target = values.get("status").asString();
            if (!VersionLifecycle.RETIRED.equals(target) || VersionLifecycle.PENDING_APPROVAL.equals(status)) {
                throw ApiException.conflict("invalid_transition",
                        "Decision table status is managed by version actions; only retirement (not while pending approval) is allowed via PATCH");
            }
        }
        if (VersionLifecycle.RETIRED.equals(status)) {
            throw ApiException.conflict("table_retired", "Decision table " + id + " is retired");
        }
        if (values.has("code") && !values.get("code").asString().equals(current.get("code").asString())
                && current.hasNonNull("current_version_id")) {
            throw ApiException.conflict("immutable_field", "code cannot change once a version is published");
        }
        return crud.updateRow(TABLE, Map.of("id", id), values, expectedVersion)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match does not match the current version; reload and retry"));
    }

    // ------------------------------------------------------------------ versions

    @Transactional
    public String createVersion(JsonNode req) {
        UUID tableId = UUID.fromString(req.get("decision_table_id").asString());
        JsonNode table = store.require(TABLE, tableId);
        if (VersionLifecycle.RETIRED.equals(table.get("status").asString())) {
            throw ApiException.conflict("table_retired", "Decision table " + tableId + " is retired");
        }
        model(req);
        ObjectNode values = crud.toColumns(VERSION, req);
        values.put("version_no", store.nextVersionNo(tableId));
        values.put("status", VersionLifecycle.DRAFT);
        validateWindow(values);
        return crud.insert(VERSION, values);
    }

    @Transactional
    public String updateVersion(UUID id, JsonNode req, Integer expectedVersion) {
        JsonNode current = store.lock(VERSION, id);
        VersionLifecycle.requireEditable(current.get("status").asString());
        ObjectNode values = crud.toColumns(VERSION, req);
        if (values.has("decision_table_id")
                && !values.get("decision_table_id").asString().equals(current.get("decision_table_id").asString())) {
            throw ApiException.unprocessable("immutable_field", "decision_table_id cannot be changed");
        }
        values.remove("decision_table_id");
        if (VersionLifecycle.REJECTED.equals(current.get("status").asString())) {
            values.put("status", VersionLifecycle.DRAFT); // rework of a rejected version
        }
        ObjectNode merged = (ObjectNode) current.deepCopy();
        merged.setAll(values);
        model(merged);
        validateWindow(merged);
        return crud.updateRow(VERSION, Map.of("id", id), values, expectedVersion)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match does not match the current version; reload and retry"));
    }

    @Transactional
    public String submit(UUID id, String comment) {
        JsonNode v = store.lock(VERSION, id);
        String target = VersionLifecycle.transition(v.get("status").asString(), Action.SUBMIT);
        DecisionTableModel model = model(v);
        if (model.rules().isEmpty()) {
            throw ApiException.unprocessable("no_rules", "A decision table version needs at least one rule");
        }
        ObjectNode values = json.createObjectNode();
        values.put("status", target);
        values.put("submitted_by", TenantContext.principal());
        values.put("submitted_at", io.meridian.common.time.Clocks.now().toString());
        values.putNull("rejected_by");
        values.putNull("rejection_reason");
        values.put("checksum", checksum(v));
        JsonNode row = store.update(VERSION, id, values);
        UUID tableId = UUID.fromString(v.get("decision_table_id").asString());
        JsonNode table = store.lock(TABLE, tableId);
        if (VersionLifecycle.DRAFT.equals(table.get("status").asString())) {
            store.update(TABLE, tableId, json.createObjectNode().put("status", VersionLifecycle.PENDING_APPROVAL));
        }
        return row.toString();
    }

    /**
     * Checker: four-eyes and integrity check. The version is published at once when its effective_from has been
     * reached (or is empty); otherwise it stays APPROVED and is published by the first evaluation on or after it.
     */
    @Transactional
    public String approve(UUID id, String comment) {
        JsonNode v = store.lock(VERSION, id);
        String target = VersionLifecycle.transition(v.get("status").asString(), Action.APPROVE);
        VersionLifecycle.requireDifferentChecker(text(v, "submitted_by"), TenantContext.principal());
        if (!checksum(v).equals(text(v, "checksum"))) {
            throw ApiException.conflict("checksum_mismatch", "Version content changed after submission; resubmit it");
        }
        UUID tableId = UUID.fromString(v.get("decision_table_id").asString());
        JsonNode table = store.lock(TABLE, tableId);
        if (VersionLifecycle.RETIRED.equals(table.get("status").asString())) {
            throw ApiException.conflict("table_retired", "Decision table " + tableId + " is retired");
        }
        OffsetDateTime now = io.meridian.common.time.Clocks.now();
        ObjectNode values = json.createObjectNode();
        values.put("status", target);
        values.put("approved_by", TenantContext.principal());
        values.put("approved_at", now.toString());
        if (!v.hasNonNull("effective_from")) {
            values.put("effective_from", now.toString());
        }
        JsonNode row = store.update(VERSION, id, values);
        if (!effectiveFrom(row).isAfter(now)) {
            return publish(tableId, row, now).toString();
        }
        settleTableStatus(tableId);
        outbox.publish("product.decision_table_version.approved", VERSION, id, row);
        return row.toString();
    }

    @Transactional
    public String reject(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "A rejection reason is required");
        }
        JsonNode v = store.lock(VERSION, id);
        String target = VersionLifecycle.transition(v.get("status").asString(), Action.REJECT);
        ObjectNode values = json.createObjectNode();
        values.put("status", target);
        values.put("rejected_by", TenantContext.principal());
        values.put("rejection_reason", reason);
        values.putNull("checksum");
        JsonNode row = store.update(VERSION, id, values);
        settleTableStatus(UUID.fromString(v.get("decision_table_id").asString()));
        outbox.publish("product.decision_table_version.rejected", VERSION, id, row);
        return row.toString();
    }

    /** Makes an APPROVED version the table's current one; earlier published (and overtaken approved) versions retire. */
    private JsonNode publish(UUID tableId, JsonNode version, OffsetDateTime now) {
        UUID id = UUID.fromString(version.get("id").asString());
        OffsetDateTime from = effectiveFrom(version);
        List<JsonNode> older = new ArrayList<>(store.versionsInStatus(tableId, VersionLifecycle.PUBLISHED));
        store.versionsInStatus(tableId, VersionLifecycle.APPROVED).stream()
                .filter(a -> !a.get("id").asString().equals(id.toString()) && !effectiveFrom(a).isAfter(from))
                .forEach(older::add);
        for (JsonNode prev : older) {
            ObjectNode retire = json.createObjectNode();
            retire.put("status", VersionLifecycle.transition(prev.get("status").asString(), Action.RETIRE));
            if (!prev.hasNonNull("effective_to")) {
                retire.put("effective_to", now.toString());
            }
            store.update(VERSION, UUID.fromString(prev.get("id").asString()), retire);
        }
        JsonNode row = store.update(VERSION, id, json.createObjectNode()
                .put("status", VersionLifecycle.transition(version.get("status").asString(), Action.PUBLISH)));
        ObjectNode tableValues = json.createObjectNode();
        tableValues.put("status", VersionLifecycle.PUBLISHED);
        tableValues.put("current_version_id", id.toString());
        store.update(TABLE, tableId, tableValues);
        outbox.publish("product.decision_table_version.published", VERSION, id, row);
        return row;
    }

    /** Publishes APPROVED versions whose effective_from has been reached (oldest first). */
    private void promoteDue(UUID tableId) {
        OffsetDateTime now = io.meridian.common.time.Clocks.now();
        List<JsonNode> due = store.versionsInStatus(tableId, VersionLifecycle.APPROVED).stream()
                .filter(a -> !effectiveFrom(a).isAfter(now))
                .sorted(java.util.Comparator.comparing(DecisionTableService::effectiveFrom))
                .toList();
        if (due.isEmpty()) {
            return;
        }
        JsonNode table = store.lock(TABLE, tableId);
        if (VersionLifecycle.RETIRED.equals(table.get("status").asString())) {
            return;
        }
        for (JsonNode a : due) {
            JsonNode current = store.lock(VERSION, UUID.fromString(a.get("id").asString()));
            if (VersionLifecycle.APPROVED.equals(current.get("status").asString())) {
                publish(tableId, current, now);
            }
        }
    }

    /** Table status after a version leaves PENDING_APPROVAL without being published. */
    private void settleTableStatus(UUID tableId) {
        JsonNode table = store.lock(TABLE, tableId);
        if (VersionLifecycle.PENDING_APPROVAL.equals(table.get("status").asString())
                && store.versionsInStatus(tableId, VersionLifecycle.PENDING_APPROVAL).isEmpty()) {
            store.update(TABLE, tableId, json.createObjectNode().put("status",
                    table.hasNonNull("current_version_id") ? VersionLifecycle.PUBLISHED : VersionLifecycle.DRAFT));
        }
    }

    private static OffsetDateTime effectiveFrom(JsonNode v) {
        return v.hasNonNull("effective_from") ? OffsetDateTime.parse(v.get("effective_from").asString()) : OffsetDateTime.MIN;
    }

    /** Runs test cases against a version (any status). Nothing is logged. */
    public ObjectNode test(UUID id, JsonNode cases) {
        JsonNode v = store.require(VERSION, id);
        DecisionTableModel model = model(v);
        if (cases == null || !cases.isArray() || cases.isEmpty()) {
            throw ApiException.unprocessable("invalid_test_cases",
                    "test_cases must be [{\"name\", \"inputs\": {...}, \"expected\": {...}}]");
        }
        ObjectNode report = json.createObjectNode();
        report.put("decision_table_version_id", id.toString());
        report.put("hit_policy", model.hitPolicy().name());
        ArrayNode results = report.putArray("results");
        int passed = 0;
        int i = 0;
        for (JsonNode c : cases) {
            i++;
            ObjectNode r = results.addObject();
            r.put("name", c.hasNonNull("name") ? c.get("name").asString() : "case " + i);
            Map<String, Object> expected = PricingStore.toMap(c.get("expected"));
            try {
                DecisionEngine.Outcome o = DecisionEngine.evaluate(model, PricingStore.toMap(c.get("inputs")));
                r.set("matched_rules", store.toNode(o.matchedRules()));
                r.set("outputs", store.toNode(o.outputs()));
                ArrayNode mismatches = r.putArray("mismatches");
                if (expected != null) {
                    expected.forEach((k, exp) -> {
                        Object actual = o.outputs().get(k);
                        if (!DecisionEngine.outputEquals(actual, exp)) {
                            ObjectNode m = mismatches.addObject();
                            m.put("output", k);
                            m.set("expected", store.toNode(exp));
                            m.set("actual", store.toNode(actual));
                        }
                    });
                }
                boolean ok = mismatches.isEmpty();
                r.put("passed", ok);
                passed += ok ? 1 : 0;
            } catch (DecisionTableException e) {
                boolean expectError = c.hasNonNull("expected_error") && c.get("expected_error").asString().equals(e.code());
                r.put("passed", expectError);
                r.put("error", e.code());
                r.put("error_message", e.getMessage());
                passed += expectError ? 1 : 0;
            }
        }
        report.put("total", i);
        report.put("passed", passed);
        report.put("failed", i - passed);
        report.put("all_passed", passed == i);
        return report;
    }

    public String exportDmn(UUID id) {
        JsonNode v = store.require(VERSION, id);
        JsonNode table = store.require(TABLE, UUID.fromString(v.get("decision_table_id").asString()));
        return DmnXml.write(table.get("code").asString(), table.get("name").asString(), model(v));
    }

    /** Imports DMN XML as a new DRAFT version, creating the decision table when the code is new. */
    @Transactional
    public String importDmn(String code, String xml) {
        if (code == null || code.isBlank()) {
            throw ApiException.unprocessable("code_required", "code is required");
        }
        DmnXml.Imported imp = wrap(() -> DmnXml.parse(xml));
        JsonNode table = store.tableByCode(code).orElse(null);
        if (table == null) {
            ObjectNode t = json.createObjectNode();
            t.put("code", code);
            String name = imp.decisionName() != null && !imp.decisionName().isBlank() ? imp.decisionName() : code;
            t.put("name", name.length() > 160 ? name.substring(0, 160) : name);
            t.put("purpose", "CUSTOM");
            t.put("status", VersionLifecycle.DRAFT);
            t.put("description", "Imported from DMN" + (imp.decisionId() == null ? "" : " decision " + imp.decisionId()));
            table = json.readTree(crud.insert(TABLE, t));
        } else if (VersionLifecycle.RETIRED.equals(table.get("status").asString())) {
            throw ApiException.conflict("table_retired", "Decision table " + code + " is retired");
        }
        UUID tableId = UUID.fromString(table.get("id").asString());
        ObjectNode v = json.createObjectNode();
        v.put("decision_table_id", tableId.toString());
        v.put("version_no", store.nextVersionNo(tableId));
        v.put("hit_policy", imp.hitPolicy());
        v.put("aggregation", imp.aggregation());
        v.set("inputs", store.toNode(imp.inputs()));
        v.set("outputs", store.toNode(imp.outputs()));
        v.set("rules", store.toNode(imp.rules()));
        v.put("dmn_xml", xml);
        v.put("status", VersionLifecycle.DRAFT);
        crud.insert(VERSION, v);
        return store.require(TABLE, tableId).toString();
    }

    // ------------------------------------------------------------------ evaluation

    public record EvaluationRequest(Map<String, Object> inputs, String subjectType, UUID subjectId,
                                    String callerService, String correlationId) {}

    /** Evaluates the published version and appends the evaluation to the explainability log. */
    @Transactional
    public String evaluate(UUID tableId, EvaluationRequest r) {
        if (r.inputs() == null) {
            throw ApiException.unprocessable("inputs_required", "inputs is required");
        }
        store.require(TABLE, tableId);
        promoteDue(tableId);
        JsonNode table = store.require(TABLE, tableId);
        if (VersionLifecycle.RETIRED.equals(table.get("status").asString())) {
            throw ApiException.conflict("table_retired", "Decision table " + tableId + " is retired");
        }
        if (!table.hasNonNull("current_version_id")) {
            throw ApiException.conflict("no_published_version", "Decision table " + tableId + " has no published version");
        }
        UUID versionId = UUID.fromString(table.get("current_version_id").asString());
        JsonNode v = store.require(VERSION, versionId);
        OffsetDateTime now = io.meridian.common.time.Clocks.now();
        if (v.hasNonNull("effective_from") && OffsetDateTime.parse(v.get("effective_from").asString()).isAfter(now)) {
            throw ApiException.conflict("version_not_effective", "The published version is effective from "
                    + v.get("effective_from").asString());
        }
        if (v.hasNonNull("effective_to") && !OffsetDateTime.parse(v.get("effective_to").asString()).isAfter(now)) {
            throw ApiException.conflict("version_expired", "The published version expired at " + v.get("effective_to").asString());
        }
        DecisionTableModel model = model(v);
        long start = System.nanoTime();
        DecisionEngine.Outcome o = wrap(() -> DecisionEngine.evaluate(model, r.inputs()));
        long micros = Math.max(0, (System.nanoTime() - start) / 1000);

        ObjectNode log = json.createObjectNode();
        log.put("decision_table_id", tableId.toString());
        log.put("decision_table_version_id", versionId.toString());
        log.put("caller_service", truncate(r.callerService() == null ? TenantContext.principal() : r.callerService(), 64));
        if (r.subjectType() != null) {
            log.put("subject_type", r.subjectType());
        }
        if (r.subjectId() != null) {
            log.put("subject_id", r.subjectId().toString());
        }
        log.set("inputs", store.toNode(r.inputs()));
        Map<String, Object> matched = new LinkedHashMap<>();
        matched.put("hit_policy", model.hitPolicy().name());
        matched.put("version_no", v.get("version_no").asInt());
        matched.put("matched", o.matchedRules());
        matched.put("selected", o.selectedRules());
        matched.put("defaulted", o.defaulted());
        List<Object> annotations = new ArrayList<>();
        for (Integer n : o.selectedRules()) {
            String a = model.rules().get(n - 1).annotation();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rule", n);
            if (a != null) {
                m.put("annotation", a);
            }
            annotations.add(m);
        }
        matched.put("rules", annotations);
        log.set("matched_rules", store.toNode(matched));
        log.set("outputs", store.toNode(o.outputs()));
        log.put("duration_micros", (int) Math.min(Integer.MAX_VALUE, micros));
        if (r.correlationId() != null) {
            log.put("correlation_id", truncate(r.correlationId(), 128));
        }
        return crud.insert(LOG, log);
    }

    // ------------------------------------------------------------------ helpers

    DecisionTableModel model(JsonNode v) {
        return wrap(() -> DecisionTableModel.parse(text(v, "hit_policy"), text(v, "aggregation"), list(v.get("inputs")),
                list(v.get("outputs")), list(v.get("rules"))));
    }

    /** A stored/requested JSON array as a plain list; null when absent; non-arrays are rejected. */
    private static List<?> list(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isArray()) {
            throw ApiException.unprocessable("invalid_decision_table", "inputs, outputs and rules must be JSON arrays");
        }
        return (List<?>) PricingStore.toJava(n);
    }

    private static String checksum(JsonNode v) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("hit_policy", text(v, "hit_policy"));
        content.put("aggregation", text(v, "aggregation"));
        content.put("inputs", PricingStore.toJava(v.get("inputs")));
        content.put("outputs", PricingStore.toJava(v.get("outputs")));
        content.put("rules", PricingStore.toJava(v.get("rules")));
        return Canonical.sha256(content);
    }

    private static void validateWindow(JsonNode v) {
        if (v.hasNonNull("effective_from") && v.hasNonNull("effective_to")
                && !OffsetDateTime.parse(v.get("effective_to").asString())
                .isAfter(OffsetDateTime.parse(v.get("effective_from").asString()))) {
            throw ApiException.unprocessable("invalid_effective_window", "effective_to must be after effective_from");
        }
    }

    /** Maps domain definition/evaluation errors to 422 problems with per-field details. */
    static <T> T wrap(java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (DecisionTableException e) {
            throw new ApiException(422, e.code(), e.getMessage(), e.violations().stream()
                    .map(x -> new ApiException.FieldError(x.field(), x.code(), x.message())).toList());
        }
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    static String text(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) ? n.get(f).asString() : null;
    }
}
