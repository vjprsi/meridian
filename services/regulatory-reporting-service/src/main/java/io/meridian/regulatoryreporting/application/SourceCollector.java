package io.meridian.regulatoryreporting.application;

import io.meridian.common.error.ApiException;
import io.meridian.regulatoryreporting.domain.PathTemplate;
import io.meridian.regulatoryreporting.domain.ReportEngine;
import io.meridian.regulatoryreporting.domain.ReportRenderer;
import io.meridian.web.client.ServiceClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * through {@link ServiceClient}, propagating the caller's tenant and token; or inline rows) and fingerprints the
 * collected data so a run can be shown to be reproducible.
 */
@Component
public class SourceCollector {
    /** Services a report mapping may read from. */
    public static final Set<String> ALLOWED_SERVICES = Set.of("gl-service", "ledger-service", "loan-service",
            "deposit-service", "customer-service", "treasury-deal-service", "liquidity-service", "alm-service",
            "collateral-service", "card-service", "payment-service", "fx-service", "limits-service",
            "transaction-monitoring-service");
    static final int MAX_PAGES = 50;
    static final int MAX_ROWS = 50_000;

    private final ServiceClient services;
    private final JsonMapper json;

    public SourceCollector(ServiceClient services, JsonMapper json) {
        this.services = services;
        this.json = json;
    }

    /** Collected sources plus the snapshot fingerprint. */
    public record Collected(Map<String, ReportEngine.Source> sources, String snapshotId, int rowCount) {}

    public Collected collect(JsonNode dataSources, Map<String, Object> builtIns, Map<String, Object> params) {
        Map<String, ReportEngine.Source> out = new LinkedHashMap<>();
        Map<String, Object> fingerprint = new TreeMap<>();
        int total = 0;
        for (JsonNode ds : dataSources) {
            String name = ds.get("name").asString();
            String type = ds.path("type").asString("SERVICE").toUpperCase(Locale.ROOT);
            List<Map<String, Object>> rows;
            if ("INLINE".equals(type)) {
                rows = toRows(ds.get("rows"), name);
            } else {
                rows = fetch(ds, builtIns, params);
            }
            String idField = ds.hasNonNull("id_field") ? ds.get("id_field").asString() : null;
            out.put(name, new ReportEngine.Source(name, idField, rows));
            fingerprint.put(name, rows);
            total += rows.size();
        }
        String snapshot = "sha256:" + ReportRenderer.sha256(json.writeValueAsString(fingerprint));
        return new Collected(out, snapshot, total);
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
