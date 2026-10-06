package io.meridian.productcatalog.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.productcatalog.domain.Lifecycles;
import io.meridian.productcatalog.domain.definition.Canonical;
import io.meridian.productcatalog.domain.scripts.ScriptCompiler;
import io.meridian.productcatalog.infrastructure.CatalogStore;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Product scripts (sandboxed WASM): deterministic compile (static checks + module hash), test against hook
 * contracts, maker-checker activation and deprecation. Source can change only while the script is not submitted
 * and its version is DRAFT; changing it invalidates the compiled module.
 */
@Service
public class ScriptService {
    static final String SCRIPT = "product_script";
    /** Placeholder hash of a script that has not been compiled yet. */
    public static final String PENDING_SHA = "0".repeat(64);
    static final Set<String> CODE_FIELDS = Set.of("source_code", "language", "entrypoint", "hook", "max_fuel",
            "max_memory_mb", "timeout_ms", "name");

    private final CatalogStore store;
    private final CrudEngine crud;
    private final VersionContentService content;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public ScriptService(CatalogStore store, CrudEngine crud, VersionContentService content, OutboxWriter outbox,
                         JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.content = content;
        this.outbox = outbox;
        this.json = json;
    }

    public static String moduleUri(UUID scriptId, String sha) {
        return "wasm://product-scripts/" + scriptId + "/" + (sha == null ? "uncompiled" : sha) + ".wasm";
    }

    @Transactional
    public String create(Object body) {
        ObjectNode values = crud.toColumns(SCRIPT, body);
        if (!values.hasNonNull("product_version_id")) {
            throw ApiException.unprocessable("parent_required", "product_version_id is required");
        }
        content.guard(SCRIPT, values);
        budgets(values);
        UUID id = io.meridian.common.id.Ids.newId();
        values.put("id", id.toString());
        values.put("status", "DRAFT");
        values.put("wasm_sha256", PENDING_SHA);
        values.put("wasm_module_uri", moduleUri(id, null));
        return store.insert(SCRIPT, values).toString();
    }

    @Transactional
    public String update(UUID id, Object body, Integer expectedVersion) {
        JsonNode current = store.lock(SCRIPT, id);
        ObjectNode values = crud.toColumns(SCRIPT, body);
        if (values.hasNonNull("product_version_id")
                && !values.get("product_version_id").asString().equals(current.get("product_version_id").asString())) {
            throw ApiException.unprocessable("immutable_field", "product_version_id cannot be changed");
        }
        values.remove("product_version_id");
        content.guard(SCRIPT, current);
        String status = current.get("status").asString();
        boolean codeChange = values.propertyNames().stream().anyMatch(CODE_FIELDS::contains);
        if (!Lifecycles.SCRIPT.allows(status, "edit")) {
            throw ApiException.conflict("script_not_editable", "Script is " + status + "; deprecate it and create a new one");
        }
        ObjectNode merged = (ObjectNode) current.deepCopy();
        merged.setAll(values);
        budgets(merged);
        if (codeChange) {
            values.put("status", Lifecycles.SCRIPT.apply(status, "edit"));
            values.put("wasm_sha256", PENDING_SHA);
            values.put("wasm_module_uri", moduleUri(id, null));
            values.putNull("validation_report");
        }
        return crud.updateRow(SCRIPT, Map.of("id", id), values, expectedVersion)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match does not match the current version; reload and retry"));
    }

    /** Static compile; passes -> VALIDATED with module hash, fails -> DRAFT with the report. */
    @Transactional
    public String compile(UUID id) {
        JsonNode s = store.lock(SCRIPT, id);
        String status = s.get("status").asString();
        if (!Lifecycles.SCRIPT.allows(status, "compile")) {
            throw ApiException.conflict("invalid_transition", "Cannot compile a script in status " + status);
        }
        ScriptCompiler.CompileReport r = ScriptCompiler.compile(s.get("language").asString(),
                PricingInputs.text(s, "source_code"), PricingInputs.text(s, "entrypoint"),
                s.get("max_fuel").asLong(), s.get("max_memory_mb").asInt(), s.get("timeout_ms").asInt());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("compile", ScriptCompiler.toMap(r));
        ObjectNode values = json.createObjectNode();
        values.put("status", Lifecycles.SCRIPT.apply(status, r.passed() ? "compile" : "compile_failed"));
        values.put("wasm_sha256", r.passed() ? r.moduleSha256() : PENDING_SHA);
        values.put("wasm_module_uri", moduleUri(id, r.passed() ? r.moduleSha256() : null));
        values.set("validation_report", store.toNumericNode(report));
        return store.update(SCRIPT, id, values).toString();
    }

    /** Validates a test invocation against the compiled module; the result is appended to the report. */
    @Transactional
    public Map<String, Object> test(UUID id, Map<String, Object> inputs) {
        if (inputs == null) {
            throw ApiException.unprocessable("inputs_required", "inputs is required");
        }
        JsonNode s = store.lock(SCRIPT, id);
        String status = s.get("status").asString();
        if (!Set.of("VALIDATED", "PENDING_APPROVAL", "ACTIVE").contains(status)) {
            throw ApiException.conflict("script_not_compiled", "Compile the script before testing it (status " + status + ")");
        }
        ScriptCompiler.TestReport r = ScriptCompiler.test(s.get("hook").asString(), s.get("wasm_sha256").asString(),
                s.get("language").asString(), PricingInputs.text(s, "source_code"), PricingInputs.text(s, "entrypoint"),
                s.get("max_fuel").asLong(), inputs, Canonical.of(inputs));
        Map<String, Object> result = ScriptService.withId(id, ScriptCompiler.toMap(r));
        Map<String, Object> report = CatalogStore.toMap(s.get("validation_report"));
        report.put("last_test", result);
        store.update(SCRIPT, id, json.createObjectNode().set("validation_report", store.toNumericNode(report)));
        return result;
    }

    @Transactional
    public String submit(UUID id, String comment) {
        JsonNode s = store.lock(SCRIPT, id);
        String to = Lifecycles.SCRIPT.apply(s.get("status").asString(), "submit");
        requireIntegrity(s);
        Map<String, Object> report = CatalogStore.toMap(s.get("validation_report"));
        Object lastTest = report.get("last_test");
        if (lastTest instanceof Map<?, ?> t && Boolean.FALSE.equals(t.get("passed"))) {
            throw ApiException.unprocessable("tests_failing", "The last test run failed; fix the script or inputs first");
        }
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("submitted_by", TenantContext.principal());
        submission.put("submitted_at", OffsetDateTime.now().toString());
        if (comment != null) {
            submission.put("comment", comment);
        }
        report.put("submission", submission);
        ObjectNode values = json.createObjectNode();
        values.put("status", to);
        values.set("validation_report", store.toNumericNode(report));
        return store.update(SCRIPT, id, values).toString();
    }

    @Transactional
    public String approve(UUID id, String comment) {
        JsonNode s = store.lock(SCRIPT, id);
        String to = Lifecycles.SCRIPT.apply(s.get("status").asString(), "approve");
        JsonNode submission = s.path("validation_report").path("submission");
        String maker = submission.hasNonNull("submitted_by") ? submission.get("submitted_by").asString() : null;
        Lifecycles.requireFourEyes(maker, TenantContext.principal(), "Script " + s.get("name").asString());
        requireIntegrity(s);
        Map<String, Object> report = CatalogStore.toMap(s.get("validation_report"));
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("approved_by", TenantContext.principal());
        approval.put("approved_at", OffsetDateTime.now().toString());
        if (comment != null) {
            approval.put("comment", comment);
        }
        report.put("approval", approval);
        ObjectNode values = json.createObjectNode();
        values.put("status", to);
        values.put("approved_by", TenantContext.principal());
        values.set("validation_report", store.toNumericNode(report));
        JsonNode row = store.update(SCRIPT, id, values);
        outbox.publish("product.product_script.activated", SCRIPT, id, row);
        return row.toString();
    }

    @Transactional
    public String deprecate(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "A deprecation reason is required");
        }
        JsonNode s = store.lock(SCRIPT, id);
        String to = Lifecycles.SCRIPT.apply(s.get("status").asString(), "deprecate");
        Map<String, Object> report = CatalogStore.toMap(s.get("validation_report"));
        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("deprecated_by", TenantContext.principal());
        dep.put("deprecated_at", OffsetDateTime.now().toString());
        dep.put("reason", reason);
        report.put("deprecation", dep);
        ObjectNode values = json.createObjectNode();
        values.put("status", to);
        values.set("validation_report", store.toNumericNode(report));
        return store.update(SCRIPT, id, values).toString();
    }

    /** The stored module hash must still match the source (no edits since compile). */
    private static void requireIntegrity(JsonNode s) {
        String expected = ScriptCompiler.moduleSha(s.get("language").asString(), PricingInputs.text(s, "source_code"),
                PricingInputs.text(s, "entrypoint"));
        if (!expected.equals(s.get("wasm_sha256").asString())) {
            throw ApiException.conflict("module_hash_mismatch", "The compiled module no longer matches the source; compile again");
        }
    }

    private static void budgets(JsonNode v) {
        List<ApiException.FieldError> errors = new java.util.ArrayList<>();
        if (v.hasNonNull("max_fuel") && (v.get("max_fuel").asLong() < 1 || v.get("max_fuel").asLong() > ScriptCompiler.MAX_FUEL)) {
            errors.add(new ApiException.FieldError("max_fuel", "out_of_range", "max_fuel must be 1.." + ScriptCompiler.MAX_FUEL));
        }
        if (v.hasNonNull("max_memory_mb") && (v.get("max_memory_mb").asInt() < 1 || v.get("max_memory_mb").asInt() > ScriptCompiler.MAX_MEMORY_MB)) {
            errors.add(new ApiException.FieldError("max_memory_mb", "out_of_range", "max_memory_mb must be 1.." + ScriptCompiler.MAX_MEMORY_MB));
        }
        if (v.hasNonNull("timeout_ms") && (v.get("timeout_ms").asInt() < 1 || v.get("timeout_ms").asInt() > ScriptCompiler.MAX_TIMEOUT_MS)) {
            errors.add(new ApiException.FieldError("timeout_ms", "out_of_range", "timeout_ms must be 1.." + ScriptCompiler.MAX_TIMEOUT_MS));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_script", "The script settings are invalid", errors);
        }
    }

    static Map<String, Object> withId(UUID id, Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("product_script_id", id.toString());
        out.putAll(m);
        return out;
    }
}
