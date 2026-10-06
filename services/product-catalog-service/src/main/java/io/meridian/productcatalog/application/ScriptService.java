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
        return "wasm://product-scripts/" + scriptId + "/" + sha + ".wasm";
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
            values.putNull("wasm_sha256");
            values.putNull("wasm_module_uri");
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
        if (r.passed()) {
            values.put("wasm_sha256", r.moduleSha256());
            values.put("wasm_module_uri", moduleUri(id, r.moduleSha256()));
        } else {
            values.putNull("wasm_sha256");
            values.putNull("wasm_module_uri");
        }
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
        if (comment != null) {
            report.put("submission_comment", comment);
        }
        ObjectNode values = json.createObjectNode();
        values.put("status", to);
        values.put("submitted_by", TenantContext.principal());
        values.put("submitted_at", OffsetDateTime.now().toString());
        values.putNull("approved_by");
        values.putNull("approved_at");
        values.putNull("rejection_reason");
        values.set("validation_report", store.toNumericNode(report));
        return store.update(SCRIPT, id, values).toString();
    }

    @Transactional
    public String approve(UUID id, String comment) {
        JsonNode s = store.lock(SCRIPT, id);
        String to = Lifecycles.SCRIPT.apply(s.get("status").asString(), "approve");
        Lifecycles.requireFourEyes(PricingInputs.text(s, "submitted_by"), TenantContext.principal(),
                "Script " + s.get("name").asString());
        requireIntegrity(s);
        ObjectNode values = json.createObjectNode();
        values.put("status", to);
        values.put("approved_by", TenantContext.principal());
        values.put("approved_at", OffsetDateTime.now().toString());
        if (comment != null) {
            Map<String, Object> report = CatalogStore.toMap(s.get("validation_report"));
            report.put("approval_comment", comment);
            values.set("validation_report", store.toNumericNode(report));
        }
        JsonNode row = store.update(SCRIPT, id, values);
        outbox.publish("product.product_script.activated", SCRIPT, id, row);
        return row.toString();
    }

    /** Checker rejects a submitted script; the maker can edit and recompile it (REJECTED is reworkable). */
    @Transactional
    public String reject(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "A rejection reason is required");
        }
        JsonNode s = store.lock(SCRIPT, id);
        String to = Lifecycles.SCRIPT.apply(s.get("status").asString(), "reject");
        ObjectNode values = json.createObjectNode();
        values.put("status", to);
        values.put("rejection_reason", reason);
        JsonNode row = store.update(SCRIPT, id, values);
        outbox.publish("product.product_script.rejected", SCRIPT, id, row);
        return row.toString();
    }

    @Transactional
    public String deprecate(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "A deprecation reason is required");
        }
        JsonNode s = store.lock(SCRIPT, id);
        String to = Lifecycles.SCRIPT.apply(s.get("status").asString(), "deprecate");
        ObjectNode values = json.createObjectNode();
        values.put("status", to);
        values.put("deprecation_reason", reason);
        return store.update(SCRIPT, id, values).toString();
    }

    /** The stored module hash must still match the source (no edits since compile). */
    private static void requireIntegrity(JsonNode s) {
        String expected = ScriptCompiler.moduleSha(s.get("language").asString(), PricingInputs.text(s, "source_code"),
                PricingInputs.text(s, "entrypoint"));
        if (!expected.equals(PricingInputs.text(s, "wasm_sha256"))) {
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
