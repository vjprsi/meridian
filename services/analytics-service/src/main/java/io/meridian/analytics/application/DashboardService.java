package io.meridian.analytics.application;

import io.meridian.analytics.domain.DatasetCatalog;
import io.meridian.analytics.domain.EmbedTokens;
import io.meridian.analytics.domain.Filters;
import io.meridian.analytics.domain.QueryDsl;
import io.meridian.analytics.infrastructure.Store;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Dashboards and widgets: PRIVATE dashboards are visible to their owner only and only owners change dashboards and
 * their widgets; duplication copies the widgets; embed tokens are HMAC-signed and short-lived; widget data runs the
 * widget's saved query or KPI with the dashboard's global filters and shapes the result into series.
 */
@Service
public class DashboardService {
    private final Store store;
    private final CrudEngine crud;
    private final QueryService queries;
    private final KpiService kpis;
    private final JsonMapper json;
    private final byte[] embedSecret;

    public DashboardService(Store store, CrudEngine crud, QueryService queries, KpiService kpis, JsonMapper json,
                            @Value("${meridian.analytics.embed-secret}") String embedSecret) {
        this.store = store;
        this.crud = crud;
        this.queries = queries;
        this.kpis = kpis;
        this.json = json;
        if (embedSecret == null || embedSecret.length() < 32) {
            throw new IllegalStateException("meridian.analytics.embed-secret must be at least 32 characters");
        }
        this.embedSecret = embedSecret.getBytes(StandardCharsets.UTF_8);
    }

    public JsonNode visible(UUID id) {
        JsonNode d = store.require("analytics_dashboard", id);
        if ("PRIVATE".equals(d.get("visibility").asString()) && !TenantContext.principal().equals(Fields.text(d, "owner"))) {
            throw ApiException.notFound("analytics dashboard", id);
        }
        return d;
    }

    public JsonNode owned(UUID id) {
        JsonNode d = visible(id);
        if (d.path("is_system").asBoolean(false)) {
            throw new ApiException(403, "system_dashboard", "System dashboards are read-only; duplicate it to customise");
        }
        if (!TenantContext.principal().equals(Fields.text(d, "owner"))) {
            throw new ApiException(403, "not_owner", "Only the owner can change this dashboard");
        }
        return d;
    }

    public void checkFilters(JsonNode filters) {
        if (filters == null || filters.isNull()) {
            return;
        }
        if (!filters.isArray()) {
            throw Fields.invalid("filters", "invalid_type", "filters must be a list of {field, op, value}");
        }
        List<String> errors = new ArrayList<>();
        Filters.parse(json.convertValue(filters, new TypeReference<List<Map<String, Object>>>() {}), errors);
        if (!errors.isEmpty()) {
            throw Fields.invalid("filters", "invalid_filter", String.join("; ", errors));
        }
    }

    @Transactional
    public String duplicate(UUID id, String name) {
        JsonNode src = visible(id);
        ObjectNode copy = json.createObjectNode();
        UUID newId = Ids.newId();
        copy.put("id", newId.toString());
        copy.put("name", name);
        for (String f : List.of("description", "layout", "filters", "refresh_seconds", "tags")) {
            if (src.hasNonNull(f)) {
                copy.set(f, src.get(f));
            }
        }
        copy.put("owner", TenantContext.principal());
        copy.put("visibility", "PRIVATE");
        copy.put("is_system", false);
        String row = crud.insert("analytics_dashboard", copy);
        for (JsonNode w : store.rows("select row_to_json(t)::text from analytics_widget t where t.analytics_dashboard_id = ? order by t.id", id)) {
            ObjectNode wc = json.createObjectNode();
            wc.put("analytics_dashboard_id", newId.toString());
            for (String f : List.of("title", "widget_type", "analytics_saved_query_id", "analytics_kpi_id", "config", "position")) {
                if (w.hasNonNull(f)) {
                    wc.set(f, w.get(f));
                }
            }
            crud.insert("analytics_widget", wc);
        }
        return row;
    }

    public ObjectNode embedToken(UUID id, Integer ttlSeconds) {
        visible(id);
        int ttl = ttlSeconds == null ? 900 : ttlSeconds;
        if (ttl < 60 || ttl > 3600) {
            throw Fields.invalid("ttl_seconds", "out_of_range", "ttl_seconds must be 60-3600");
        }
        long now = store.nowEpoch();
        EmbedTokens.Claims c = new EmbedTokens.Claims(TenantContext.require().toString(), id.toString(),
                TenantContext.principal(), now, now + ttl, Ids.newId().toString());
        ObjectNode out = json.createObjectNode();
        out.put("token", EmbedTokens.issue(embedSecret, c));
        out.put("dashboard_id", id.toString());
        out.put("expires_at", Instant.ofEpochSecond(c.expiresAt()).toString());
        out.put("ttl_seconds", ttl);
        return out;
    }

    public ObjectNode verify(String token) {
        EmbedTokens.Verification v = EmbedTokens.verify(embedSecret, token, store.nowEpoch(), TenantContext.require().toString());
        if (v.valid() && store.find("analytics_dashboard", UUID.fromString(v.claims().dashboardId())).isEmpty()) {
            v = new EmbedTokens.Verification(false, "dashboard_not_found", v.claims());
        }
        return json.valueToTree(EmbedTokens.describe(v));
    }

    /** Widget create / update rules (sources consistent with the widget type, position on the grid). */
    public void checkWidget(ObjectNode values, JsonNode existing) {
        JsonNode merged = existing == null ? values : ((ObjectNode) existing.deepCopy()).setAll(values);
        UUID dashboard = Fields.requireUuid(merged, "analytics_dashboard_id");
        if (store.find("analytics_dashboard", dashboard).isEmpty()) {
            throw ApiException.unprocessable("dashboard_not_found", "Dashboard " + dashboard + " does not exist");
        }
        owned(dashboard);
        String type = Fields.requireText(merged, "widget_type");
        UUID query = Fields.uuid(merged, "analytics_saved_query_id");
        UUID kpi = Fields.uuid(merged, "analytics_kpi_id");
        if ("TEXT".equals(type)) {
            if (query != null || kpi != null) {
                throw Fields.invalid("widget_type", "invalid_widget", "TEXT widgets have no data source");
            }
        } else if ("KPI".equals(type)) {
            if (kpi == null || query != null) {
                throw Fields.invalid("analytics_kpi_id", "invalid_widget", "KPI widgets need analytics_kpi_id (and no query)");
            }
        } else if ((query == null) == (kpi == null)) {
            throw Fields.invalid("analytics_saved_query_id", "invalid_widget", type + " widgets need exactly one of analytics_saved_query_id or analytics_kpi_id");
        }
        if (query != null) {
            queries.visible(query);
        }
        if (kpi != null && store.find("analytics_kpi", kpi).isEmpty()) {
            throw ApiException.unprocessable("kpi_not_found", "KPI " + kpi + " does not exist");
        }
        JsonNode pos = merged.get("position");
        if (pos == null || !pos.isObject()) {
            throw Fields.invalid("position", "required", "position {x, y, w, h} is required");
        }
        for (String f : List.of("x", "y", "w", "h")) {
            if (!pos.path(f).isIntegralNumber() || pos.get(f).asInt() < (f.equals("w") || f.equals("h") ? 1 : 0)
                    || pos.get(f).asInt() > 48) {
                throw Fields.invalid("position." + f, "invalid_position", "position." + f + " must be an integer within the 48-column grid");
            }
        }
    }

    /** Data of a widget: rows / series with the dashboard filters applied. */
    public ObjectNode widgetData(UUID widgetId) {
        JsonNode w = store.require("analytics_widget", widgetId);
        JsonNode dash = visible(UUID.fromString(w.get("analytics_dashboard_id").asString()));
        JsonNode config = w.path("config");
        String type = w.get("widget_type").asString();
        ObjectNode out = json.createObjectNode();
        out.put("widget_id", widgetId.toString());
        out.put("widget_type", type);
        out.put("title", w.get("title").asString());
        List<Filters.Filter> filters = new ArrayList<>();
        if (dash.path("filters").isArray()) {
            filters = Filters.parse(json.convertValue(dash.get("filters"), new TypeReference<List<Map<String, Object>>>() {}),
                    new ArrayList<>());
        }
        out.set("applied_filters", dash.path("filters").isArray() ? dash.get("filters") : json.createArrayNode());
        if ("TEXT".equals(type)) {
            out.put("text", config.path("text").asString(""));
            return out;
        }
        if (w.hasNonNull("analytics_kpi_id")) {
            UUID kpiId = UUID.fromString(w.get("analytics_kpi_id").asString());
            JsonNode k = store.require("analytics_kpi", kpiId);
            DatasetCatalog.Dataset d = DatasetCatalog.find(k.get("dataset").asString()).orElseThrow();
            JsonNode filterJson = json.valueToTree(filters.stream().filter(f -> d.fields().containsKey(f.field()))
                    .map(f -> Map.of("field", f.field(), "op", f.op(), "value", f.value() == null ? "" : f.value())).toList());
            ObjectNode value = kpis.evaluate(kpiId, null, filterJson);
            out.set("value", value.get("value"));
            out.put("unit", value.get("unit").asString());
            if (config.hasNonNull("from") && config.hasNonNull("to")) {
                ObjectNode series = kpis.values(kpiId, LocalDate.parse(config.get("from").asString()),
                        LocalDate.parse(config.get("to").asString()), config.path("granularity").asString("DAY"));
                ArrayNode points = json.createArrayNode();
                series.get("points").forEach(p -> points.addObject().put("x", p.get("period_start").asString()).set("y", p.get("value")));
                ArrayNode s = out.putArray("series");
                s.addObject().put("name", k.get("code").asString()).set("points", points);
            }
            return out;
        }
        JsonNode saved = queries.visible(UUID.fromString(w.get("analytics_saved_query_id").asString()));
        QueryDsl.Query q = queries.compile(saved.get("query").asString());
        DatasetCatalog.Dataset d = DatasetCatalog.find(q.dataset()).orElseThrow();
        Predicate<Map<String, Object>> predicate = Filters.predicate(filters, d.fields().keySet());
        ObjectNode result = queries.execute(saved, config.path("parameters").isObject() ? config.get("parameters") : null,
                1000, predicate);
        out.set("columns", result.get("columns"));
        out.set("rows", result.get("rows"));
        out.put("row_count", result.get("row_count").asInt());
        out.put("truncated", result.get("truncated").asBoolean());
        List<String> cols = new ArrayList<>();
        result.get("columns").forEach(c -> cols.add(c.asString()));
        if (cols.isEmpty()) {
            return out;
        }
        String x = config.hasNonNull("x") ? config.get("x").asString() : cols.getFirst();
        List<String> ys = new ArrayList<>();
        if (config.path("y").isArray()) {
            config.get("y").forEach(y -> ys.add(y.asString()));
        } else {
            cols.stream().filter(c -> !c.equals(x)).forEach(ys::add);
        }
        Set<String> known = Set.copyOf(cols);
        if (!known.contains(x) || !known.containsAll(ys)) {
            throw ApiException.unprocessable("invalid_widget_config", "config x / y must name query columns " + cols);
        }
        ArrayNode series = out.putArray("series");
        for (String y : ys) {
            ObjectNode s = series.addObject();
            s.put("name", y);
            ArrayNode points = s.putArray("points");
            for (JsonNode r : result.get("rows")) {
                ObjectNode p = points.addObject();
                p.set("x", r.get(x));
                p.set("y", r.get(y));
            }
        }
        return out;
    }
}
