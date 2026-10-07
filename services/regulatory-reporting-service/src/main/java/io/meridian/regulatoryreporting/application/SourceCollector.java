package io.meridian.regulatoryreporting.application;

import io.meridian.common.error.ApiException;
import io.meridian.regulatoryreporting.domain.DefinitionException;
import io.meridian.regulatoryreporting.domain.Expr;
import io.meridian.regulatoryreporting.domain.PathTemplate;
import io.meridian.regulatoryreporting.domain.ReportEngine;
import io.meridian.regulatoryreporting.domain.ReportRenderer;
import io.meridian.regulatoryreporting.domain.SourceTransforms;
import io.meridian.web.client.ServiceClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Collect step of a report run: reads every data source of the version (GET calls to whitelisted Meridian services
 * through {@link ServiceClient}, propagating the caller's tenant and token; inline rows; or COMBINE groupings of
 * earlier sources), applies GL mapping tables (with the tenant's overrides from config-service) and fingerprints the
 * collected data so a run can be shown to be reproducible. Missing run parameters declared with {@code resolve} are
 * looked up first (e.g. the GL accounting period ending on the period end).
 */
@Component
public class SourceCollector {
    /** Services a report mapping may read from. */
    public static final Set<String> ALLOWED_SERVICES = Set.of("gl-service", "ledger-service", "loan-service",
            "deposit-service", "customer-service", "treasury-deal-service", "liquidity-service", "alm-service",
            "collateral-service", "card-service", "payment-service", "fx-service", "limits-service",
            "transaction-monitoring-service", "case-management-service", "crm-service", "wallet-service",
            "agent-network-service");
    /** config-service namespace holding tenant overrides of GL mapping tables (key {@code gl_mapping.<table>}). */
    public static final String CONFIG_NAMESPACE = "regulatory_reporting";
    static final int MAX_PAGES = 50;
    static final int MAX_ROWS = 50_000;

    private final ServiceClient services;
    private final JsonMapper json;

    public SourceCollector(ServiceClient services, JsonMapper json) {
        this.services = services;
        this.json = json;
    }

    /** Collected sources plus the snapshot fingerprint and the parameters looked up by resolvers. */
    public record Collected(Map<String, ReportEngine.Source> sources, String snapshotId, int rowCount,
                            Map<String, Object> resolvedParams) {
        public Collected(Map<String, ReportEngine.Source> sources, String snapshotId, int rowCount) {
            this(sources, snapshotId, rowCount, Map.of());
        }
    }

    public Collected collect(JsonNode dataSources, Map<String, Object> builtIns, Map<String, Object> params) {
        Map<String, ReportEngine.Source> out = new LinkedHashMap<>();
        Map<String, Object> fingerprint = new TreeMap<>();
        Map<String, Object> effective = new LinkedHashMap<>(params);
        Map<String, Object> resolved = new LinkedHashMap<>();
        Overrides overrides = new Overrides(builtIns);
        int total = 0;
        for (JsonNode ds : dataSources) {
            String name = ds.get("name").asString();
            String type = ds.path("type").asString("SERVICE").toUpperCase(Locale.ROOT);
            List<Map<String, Object>> rows;
            switch (type) {
                case "INLINE" -> rows = toRows(ds.get("rows"), name);
                case "COMBINE" -> rows = combine(ds, name, out, effective);
                default -> {
                    resolve(ds, builtIns, effective, resolved);
                    rows = fetch(ds, builtIns, effective);
                }
            }
            if (ds.hasNonNull("gl_mapping")) {
                SourceTransforms.GlMapping mapping = overrides.apply(glMapping(ds, name));
                rows = SourceTransforms.applyGlMapping(mapping, rows);
                fingerprint.put(name + "#gl_mapping", mapping.rules().stream().map(r -> r.origin() + ":" + r.line()).toList());
            }
            String idField = ds.hasNonNull("id_field") ? ds.get("id_field").asString() : null;
            out.put(name, new ReportEngine.Source(name, idField, rows));
            fingerprint.put(name, rows);
            total += rows.size();
        }
        if (!resolved.isEmpty()) {
            fingerprint.put("#resolved", new TreeMap<>(resolved));
        }
        String snapshot = "sha256:" + ReportRenderer.sha256(json.writeValueAsString(fingerprint));
        return new Collected(out, snapshot, total, resolved);
    }

    /** Looks up parameters of {@code resolve} the run did not supply: first row of the GET matching {@code match}. */
    private void resolve(JsonNode ds, Map<String, Object> builtIns, Map<String, Object> params, Map<String, Object> resolved) {
        JsonNode r = ds.get("resolve");
        if (r == null || !r.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> e : r.properties()) {
            String param = e.getKey();
            if (params.get(param) != null) {
                continue;
            }
            JsonNode spec = e.getValue();
            String service = spec.path("service").asString("");
            if (!ALLOWED_SERVICES.contains(service)) {
                throw ApiException.unprocessable("source_not_allowed", "Resolver of " + param + " reads from " + service
                        + ", which is not an allowed reporting source");
            }
            String path;
            Map<String, String> match = new LinkedHashMap<>();
            try {
                path = PathTemplate.expand(spec.path("path").asString(""), builtIns, params);
                for (Map.Entry<String, JsonNode> m : spec.path("match").properties()) {
                    match.put(m.getKey(), PathTemplate.text(m.getValue().asString(), builtIns, params));
                }
            } catch (IllegalArgumentException ex) {
                throw ApiException.unprocessable("missing_parameter", "Resolver of " + param + ": " + ex.getMessage());
            }
            String field = spec.path("field").asString("id");
            Object value = null;
            for (Map<String, Object> row : page(service, path, ds.get("name").asString(), "/data")) {
                boolean ok = match.entrySet().stream().allMatch(m -> {
                    Object v = ReportEngine.lookup(row, m.getKey());
                    return v != null && String.valueOf(v).equals(m.getValue());
                });
                if (ok) {
                    value = ReportEngine.lookup(row, field);
                    break;
                }
            }
            if (value == null) {
                throw ApiException.unprocessable("missing_parameter", "Parameter " + param + " was not supplied and no "
                        + service + " record matches " + match + "; pass parameters." + param + " with the run");
            }
            params.put(param, value);
            resolved.put(param, value);
        }
    }

    private List<Map<String, Object>> fetch(JsonNode ds, Map<String, Object> builtIns, Map<String, Object> params) {
        String name = ds.get("name").asString();
        String service = ds.path("service").asString("");
        if (!ALLOWED_SERVICES.contains(service)) {
            throw ApiException.unprocessable("source_not_allowed", "Data source " + name + " reads from " + service
                    + ", which is not an allowed reporting source");
        }
        String rawPath = ds.path("path").asString("");
        if (!PathTemplate.isSafe(rawPath)) {
            throw ApiException.unprocessable("invalid_source_path", "Data source " + name + " has an unsafe path");
        }
        String path;
        try {
            path = PathTemplate.expand(rawPath, builtIns, params);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("missing_parameter", "Data source " + name + ": " + e.getMessage());
        }
        String pointer = ds.hasNonNull("rows_pointer") ? ds.get("rows_pointer").asString() : "/data";
        return page(service, path, name, pointer);
    }

    private List<Map<String, Object>> page(String service, String path, String name, String pointer) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String p = cursor == null ? path : path + (path.contains("?") ? "&" : "?") + "cursor=" + cursor;
            JsonNode resp = services.get(service, p);
            JsonNode arr = resp == null ? null : resp.isArray() ? resp : resp.at(pointer);
            if (arr == null || arr.isMissingNode() || arr.isNull()) {
                throw ApiException.unprocessable("source_shape_mismatch", "Data source " + name + ": " + service
                        + " response has no rows at " + pointer);
            }
            rows.addAll(toRows(arr, name));
            if (rows.size() > MAX_ROWS) {
                throw ApiException.unprocessable("source_too_large", "Data source " + name + " returned more than " + MAX_ROWS + " rows");
            }
            boolean paged = "/data".equals(pointer) && resp.path("has_more").asBoolean(false) && resp.hasNonNull("next_cursor");
            if (!paged) {
                break;
            }
            cursor = java.net.URLEncoder.encode(resp.get("next_cursor").asString(), java.nio.charset.StandardCharsets.UTF_8);
        }
        return rows;
    }

    private List<Map<String, Object>> combine(JsonNode ds, String name, Map<String, ReportEngine.Source> collected,
                                              Map<String, Object> params) {
        List<DefinitionException.Problem> problems = new ArrayList<>();
        SourceTransforms.Combine c = SourceTransforms.combine(map(ds), new LinkedHashSet<>(collected.keySet()),
                "data_sources." + name, problems);
        if (!problems.isEmpty()) {
            throw new DefinitionException(problems);
        }
        try {
            return SourceTransforms.applyCombine(c, collected, params);
        } catch (Expr.ExprException e) {
            throw new Expr.ExprException("data source " + name + ": " + e.getMessage());
        }
    }

    private SourceTransforms.GlMapping glMapping(JsonNode ds, String name) {
        List<DefinitionException.Problem> problems = new ArrayList<>();
        SourceTransforms.GlMapping m = SourceTransforms.glMapping(map(ds).get("gl_mapping"), "data_sources." + name + ".gl_mapping", problems);
        if (!problems.isEmpty()) {
            throw new DefinitionException(problems);
        }
        return m;
    }

    private Map<String, Object> map(JsonNode n) {
        return json.convertValue(n, new TypeReference<Map<String, Object>>() {});
    }

    /**
     * Tenant overrides of GL mapping tables: config-service namespace {@value #CONFIG_NAMESPACE}, key
     * {@code gl_mapping.<table in lower case>}, value {@code {rules: [...], replace: false}}. Tenant rules are matched
     * before the pack's (or replace them). Read once per run; config-service failures fail the run rather than
     * silently reporting with the pack's defaults.
     */
    final class Overrides {
        private final Map<String, Object> builtIns;
        private JsonNode values;

        Overrides(Map<String, Object> builtIns) {
            this.builtIns = builtIns;
        }

        SourceTransforms.GlMapping apply(SourceTransforms.GlMapping mapping) {
            JsonNode o = values().get("gl_mapping." + mapping.table().toLowerCase(Locale.ROOT));
            if (o == null || o.isNull() || !o.isObject()) {
                return mapping;
            }
            List<DefinitionException.Problem> problems = new ArrayList<>();
            List<SourceTransforms.GlRule> rules = SourceTransforms.glRules(
                    json.convertValue(o.get("rules"), new TypeReference<List<Object>>() {}),
                    "config " + CONFIG_NAMESPACE + "/gl_mapping." + mapping.table().toLowerCase(Locale.ROOT) + ".rules",
                    "tenant", problems);
            if (!problems.isEmpty()) {
                throw ApiException.unprocessable("invalid_gl_mapping_override", "The tenant's GL mapping override of "
                        + mapping.table() + " is invalid: " + problems.getFirst().field() + " " + problems.getFirst().message());
            }
            return mapping.withOverride(rules, o.path("replace").asBoolean(false));
        }

        private JsonNode values() {
            if (values == null) {
                Object le = builtIns.get("legal_entity_id");
                JsonNode resolved;
                try {
                    resolved = services.get("config-service", "/v1/config/entries/resolve?namespace=" + CONFIG_NAMESPACE
                            + (le == null ? "" : "&legal_entity_id=" + le));
                } catch (ApiException e) {
                    throw ApiException.unprocessable("gl_mapping_override_unavailable",
                            "GL mapping overrides could not be read from config-service: " + e.getMessage());
                }
                JsonNode v = resolved == null ? null : resolved.get("values");
                values = v != null && v.isObject() ? v : json.createObjectNode();
            }
            return values;
        }
    }

    private List<Map<String, Object>> toRows(JsonNode arr, String name) {
        if (arr == null) {
            return List.of();
        }
        if (arr.isObject()) {
            return List.of(json.convertValue(arr, new TypeReference<Map<String, Object>>() {}));
        }
        if (!arr.isArray()) {
            throw ApiException.unprocessable("source_shape_mismatch", "Data source " + name + " rows must be a list of objects");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode r : arr) {
            if (!r.isObject()) {
                throw ApiException.unprocessable("source_shape_mismatch", "Data source " + name + " rows must be objects");
            }
            rows.add(json.convertValue(r, new TypeReference<Map<String, Object>>() {}));
        }
        return rows;
    }
}
