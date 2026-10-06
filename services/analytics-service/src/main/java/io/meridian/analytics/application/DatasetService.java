package io.meridian.analytics.application;

import io.meridian.analytics.domain.DatasetCatalog;
import io.meridian.analytics.domain.Expr;
import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads whitelisted datasets from their owning services through {@link ServiceClient} (the caller's tenant and token
 * are propagated, so the source service's row-level security applies) and projects rows to the declared fields with
 * typed values. Caller-provided rows go through the same projection.
 */
@Service
public class DatasetService {
    public static final int MAX_ROWS = 10_000;
    static final int PAGE = 200;

    private final ServiceClient services;
    private final JsonMapper json;

    public DatasetService(ServiceClient services, JsonMapper json) {
        this.services = services;
        this.json = json;
    }

    public DatasetCatalog.Dataset require(String name) {
        return DatasetCatalog.find(name).orElseThrow(() -> Fields.invalid("dataset", "unknown_dataset",
                "Unknown dataset " + name + "; available: " + DatasetCatalog.all().keySet()));
    }

    public List<Map<String, Object>> rows(DatasetCatalog.Dataset d) {
        List<Map<String, Object>> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_ROWS / PAGE + 1; page++) {
            String path = d.path() + "?limit=" + PAGE + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            JsonNode r = services.get(d.service(), path);
            if (r == null || !r.path("data").isArray()) {
                throw new ApiException(502, "dataset_unavailable", "Dataset " + d.name() + ": " + d.service() + " returned no data");
            }
            for (JsonNode row : r.get("data")) {
                if (out.size() >= MAX_ROWS) {
                    throw ApiException.unprocessable("dataset_too_large", "Dataset " + d.name() + " exceeds " + MAX_ROWS
                            + " rows; narrow it with a filter at the source");
                }
                out.add(project(d, row));
            }
            if (!r.path("has_more").asBoolean(false) || !r.hasNonNull("next_cursor")) {
                break;
            }
            cursor = r.get("next_cursor").asString();
        }
        return out;
    }

    /** Projects provided rows (a JSON array of objects) onto the dataset fields. */
    public List<Map<String, Object>> provided(DatasetCatalog.Dataset d, JsonNode rows) {
        if (rows == null || !rows.isArray()) {
            throw Fields.invalid("rows", "invalid_type", "rows must be a list of objects");
        }
        if (rows.size() > MAX_ROWS) {
            throw Fields.invalid("rows", "too_many_rows", "at most " + MAX_ROWS + " rows can be provided");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode r : rows) {
            if (!r.isObject()) {
                throw Fields.invalid("rows", "invalid_type", "rows must be a list of objects");
            }
            out.add(project(d, r));
        }
        return out;
    }

    Map<String, Object> project(DatasetCatalog.Dataset d, JsonNode row) {
        Map<String, Object> raw = json.convertValue(row, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> out = new LinkedHashMap<>();
        d.fields().forEach((f, type) -> {
            Object v = raw.get(f);
            if (v != null) {
                out.put(f, switch (type) {
                    case "NUMBER" -> {
                        BigDecimal n = Expr.toDecimal(v);
                        yield n == null ? null : n;
                    }
                    case "BOOL" -> v instanceof Boolean ? v : Boolean.parseBoolean(String.valueOf(v));
                    default -> v instanceof String ? ((String) v).trim() : String.valueOf(v);
                });
            } else {
                out.put(f, null);
            }
        });
        return out;
    }
}
