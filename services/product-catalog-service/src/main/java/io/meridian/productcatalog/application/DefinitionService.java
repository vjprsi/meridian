package io.meridian.productcatalog.application;

import io.meridian.common.error.ApiException;
import io.meridian.productcatalog.domain.definition.Canonical;
import io.meridian.productcatalog.domain.definition.DefinitionDiff;
import io.meridian.productcatalog.domain.scripts.ScriptCompiler;
import io.meridian.productcatalog.infrastructure.CatalogStore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Resolved definitions of product versions. A version's content lives in typed rows (parameters, interest rates
 * with tiers, fees with tiers and waivers, limits, eligibility rules, scripts); the {@code definition} column holds
 * free-form settings plus, from submission on, the frozen snapshot of all rows (checksummed for tamper evidence).
 * The same snapshot format seeds new versions (templates, clones, new drafts) via {@link #materialize}.
 */
@Service
public class DefinitionService {
    /** Columns that are identity/audit/linkage, not product configuration. */
    static final Set<String> STRIP = Set.of("id", "tenant_id", "created_at", "created_by", "updated_at", "updated_by",
            "row_version", "product_version_id", "interest_rate_config_id", "fee_definition_id", "custom_field_1",
            "custom_field_2", "custom_field_3", "custom_field_4", "custom_field_5");
    static final Set<String> SCRIPT_RUNTIME = Set.of("validation_report", "wasm_module_uri", "submitted_by",
            "submitted_at", "approved_at", "rejection_reason", "deprecation_reason");

    private final CatalogStore store;

    public DefinitionService(CatalogStore store) {
        this.store = store;
    }

    /** Snapshot of the version's rows in definition format. */
    public Map<String, Object> snapshot(UUID versionId) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("parameters", rows("product_parameter", versionId, "param_key"));
        List<Object> rates = new ArrayList<>();
        for (JsonNode c : store.children("interest_rate_config", "product_version_id", versionId, "rate_kind, effective_from")) {
            Map<String, Object> m = clean(c);
            m.put("tiers", rowsOf(store.children("interest_rate_tier", "interest_rate_config_id",
                    UUID.fromString(c.get("id").asString()), "tier_no")));
            rates.add(m);
        }
        def.put("interest_rates", rates);
        List<Object> fees = new ArrayList<>();
        for (JsonNode f : store.children("fee_definition", "product_version_id", versionId, "code")) {
            Map<String, Object> m = clean(f);
            UUID feeId = UUID.fromString(f.get("id").asString());
            m.put("tiers", rowsOf(store.children("fee_tier", "fee_definition_id", feeId, "tier_no")));
            m.put("waiver_rules", rowsOf(store.children("fee_waiver_rule", "fee_definition_id", feeId, "id")));
            fees.add(m);
        }
        def.put("fees", fees);
        def.put("limits", rows("product_limit_template", versionId, "limit_code, kyc_tier"));
        def.put("eligibility_rules", rows("eligibility_rule", versionId, "id"));
        List<Object> scripts = new ArrayList<>();
        for (JsonNode s : store.children("product_script", "product_version_id", versionId, "hook, name")) {
            Map<String, Object> m = clean(s);
            SCRIPT_RUNTIME.forEach(m::remove);
            scripts.add(m);
        }
        def.put("scripts", scripts);
        return def;
    }

    /**
     * The version's effective definition: frozen snapshot once submitted; for drafts (and rejected versions) the
     * stored free-form settings overlaid with the live rows.
     */
    public Map<String, Object> resolved(JsonNode version) {
        Map<String, Object> stored = CatalogStore.toMap(version.get("definition"));
        String status = version.get("status").asString();
        if (!"DRAFT".equals(status) && !"REJECTED".equals(status) && version.hasNonNull("checksum")) {
            return stored;
        }
        Map<String, Object> out = new LinkedHashMap<>(stored);
        out.putAll(snapshot(UUID.fromString(version.get("id").asString())));
        return out;
    }

    public String checksum(Map<String, Object> definition) {
        return Canonical.sha256(definition);
    }

    /** True when the stored frozen definition still equals its checksum and the rows it was taken from. */
    public boolean intact(JsonNode version) {
        Map<String, Object> stored = CatalogStore.toMap(version.get("definition"));
        if (!checksum(stored).equals(version.path("checksum").asString(""))) {
            return false;
        }
        Map<String, Object> live = snapshot(UUID.fromString(version.get("id").asString()));
        for (String section : DefinitionDiff.SECTIONS.keySet()) {
            // Script approval/deprecation is a separate lifecycle; their content is guarded by the module hash.
            if (!section.equals("scripts")
                    && !Canonical.of(stored.get(section)).equals(Canonical.of(live.get(section)))) {
                return false;
            }
        }
        return true;
    }

    /** Free-form part of a definition (everything except the row-backed sections). */
    public Map<String, Object> settingsOnly(Map<String, Object> def) {
        Map<String, Object> m = new LinkedHashMap<>(def == null ? Map.of() : def);
        DefinitionDiff.SECTIONS.keySet().forEach(m::remove);
        return m;
    }

    /**
     * Creates the rows of a definition's sections under {@code versionId}. Currency columns default to the product
     * currency. Parameters may be a list of rows or a {@code key -> value} map (type inferred).
     */
    @SuppressWarnings("unchecked")
    public void materialize(UUID versionId, Map<String, Object> def, String currency) {
        if (def == null) {
            return;
        }
        for (Map<String, Object> p : parameters(def.get("parameters"))) {
            insert("product_parameter", p, versionId, null);
        }
        for (Map<String, Object> c : maps(def.get("interest_rates"))) {
            Map<String, Object> row = withCurrency(c, currency);
            JsonNode cfg = insert("interest_rate_config", row, versionId, null);
            UUID cfgId = UUID.fromString(cfg.get("id").asString());
            int n = 0;
            for (Map<String, Object> t : maps(c.get("tiers"))) {
                Map<String, Object> tier = new LinkedHashMap<>(t);
                tier.putIfAbsent("tier_no", ++n);
                tier.put("interest_rate_config_id", cfgId.toString());
                insert("interest_rate_tier", tier, null, null);
            }
        }
        for (Map<String, Object> f : maps(def.get("fees"))) {
            JsonNode fee = insert("fee_definition", withCurrency(f, currency), versionId, null);
            UUID feeId = UUID.fromString(fee.get("id").asString());
            int n = 0;
            for (Map<String, Object> t : maps(f.get("tiers"))) {
                Map<String, Object> tier = new LinkedHashMap<>(t);
                tier.putIfAbsent("tier_no", ++n);
                tier.put("fee_definition_id", feeId.toString());
                insert("fee_tier", tier, null, null);
            }
            for (Map<String, Object> w : maps(f.get("waiver_rules"))) {
                Map<String, Object> waiver = new LinkedHashMap<>(w);
                waiver.put("fee_definition_id", feeId.toString());
                insert("fee_waiver_rule", waiver, null, null);
            }
        }
        for (Map<String, Object> l : maps(def.get("limits"))) {
            Map<String, Object> row = new LinkedHashMap<>(l);
            if (!"COUNT".equals(row.get("metric"))) {
                row.putIfAbsent("currency", currency);
            }
            insert("product_limit_template", row, versionId, null);
        }
        for (Map<String, Object> r : maps(def.get("eligibility_rules"))) {
            Map<String, Object> row = new LinkedHashMap<>(r);
            if (!(row.get("value") instanceof Map<?, ?>)) {
                Map<String, Object> wrapped = new LinkedHashMap<>();
                wrapped.put(row.get("value") instanceof List<?> ? "values" : "value", row.get("value"));
                row.put("value", wrapped);
            }
            insert("eligibility_rule", row, versionId, null);
        }
        for (Map<String, Object> s : maps(def.get("scripts"))) {
            Map<String, Object> row = new LinkedHashMap<>(s);
            String lang = (String) row.get("language");
            String sha = ScriptCompiler.moduleSha(lang, (String) row.get("source_code"), (String) row.get("entrypoint"));
            boolean carried = sha.equals(row.get("wasm_sha256"))
                    && ("ACTIVE".equals(row.get("status")) || "VALIDATED".equals(row.get("status")));
            SCRIPT_RUNTIME.forEach(row::remove);
            if (!carried) {
                row.put("status", "DRAFT");
                row.remove("wasm_sha256");
                row.remove("approved_by");
            }
            JsonNode inserted = insert("product_script", row, versionId, null);
            if (carried) {
                UUID id = UUID.fromString(inserted.get("id").asString());
                store.update("product_script", id, (ObjectNode) store.toNumericNode(
                        Map.of("wasm_module_uri", ScriptService.moduleUri(id, sha))));
            }
        }
    }

    private JsonNode insert(String table, Map<String, Object> row, UUID versionId, Map<String, Object> extra) {
        Map<String, Object> values = new LinkedHashMap<>(row);
        STRIP.forEach(k -> {
            if (!k.equals("interest_rate_config_id") && !k.equals("fee_definition_id")) {
                values.remove(k);
            }
        });
        if (versionId != null) {
            values.put("product_version_id", versionId.toString());
        }
        if (extra != null) {
            values.putAll(extra);
        }
        return store.insert(table, (ObjectNode) store.toNumericNode(values));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parameters(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> l) {
            l.forEach(o -> {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            });
        } else if (raw instanceof Map<?, ?> m) {
            ((Map<String, Object>) m).forEach((k, v) -> {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("param_key", k);
                if (v instanceof Boolean) {
                    p.put("param_type", "BOOLEAN");
                    p.put("value_text", v.toString());
                } else if (v instanceof BigDecimal b) {
                    p.put("param_type", b.stripTrailingZeros().scale() <= 0 ? "INTEGER" : "DECIMAL");
                    p.put("value_text", b.toPlainString());
                } else if (v instanceof Map<?, ?> || v instanceof List<?>) {
                    p.put("param_type", "JSON");
                    p.put("value_json", v instanceof Map<?, ?> ? v : Map.of("values", v));
                } else {
                    p.put("param_type", "STRING");
                    p.put("value_text", String.valueOf(v));
                }
                out.add(p);
            });
        } else if (raw != null) {
            throw ApiException.unprocessable("invalid_definition", "parameters must be a list or an object");
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> m)) {
                    throw ApiException.unprocessable("invalid_definition", "definition sections must be lists of objects");
                }
                out.add((Map<String, Object>) m);
            }
        } else if (raw != null) {
            throw ApiException.unprocessable("invalid_definition", "definition sections must be lists of objects");
        }
        return out;
    }

    private static Map<String, Object> withCurrency(Map<String, Object> m, String currency) {
        Map<String, Object> out = new LinkedHashMap<>(m);
        out.putIfAbsent("currency", currency);
        return out;
    }

    private List<Object> rows(String table, UUID versionId, String order) {
        return rowsOf(store.children(table, "product_version_id", versionId, order));
    }

    private static List<Object> rowsOf(List<JsonNode> rows) {
        List<Object> out = new ArrayList<>();
        rows.forEach(r -> out.add(clean(r)));
        return out;
    }

    static Map<String, Object> clean(JsonNode row) {
        Map<String, Object> m = CatalogStore.toMap(row);
        STRIP.forEach(m::remove);
        m.values().removeIf(java.util.Objects::isNull);
        m.replaceAll((k, v) -> v instanceof String s && k.equals("currency") ? s.trim() : v);
        return m;
    }
}
