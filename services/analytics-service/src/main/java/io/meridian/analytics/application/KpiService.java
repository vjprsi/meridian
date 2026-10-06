package io.meridian.analytics.application;

import io.meridian.analytics.domain.DatasetCatalog;
import io.meridian.analytics.domain.Expr;
import io.meridian.analytics.domain.Filters;
import io.meridian.analytics.domain.MetricSpec;
import io.meridian.analytics.domain.QueryDsl;
import io.meridian.analytics.infrastructure.Store;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Governed KPIs: metric specs compiled against the dataset's fields, certification by someone other than the author
 * (cleared whenever the definition changes), point evaluation over the dataset or caller-provided rows, and time
 * series over the dataset's time field.
 */
@Service
public class KpiService {
    static final Set<String> UNITS = Set.of("COUNT", "AMOUNT", "PERCENT", "BPS", "DAYS");
    static final Set<String> DEFINITION_FIELDS = Set.of("metric", "dataset", "time_field");

    private final Store store;
    private final DatasetService datasets;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public KpiService(Store store, DatasetService datasets, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.datasets = datasets;
        this.outbox = outbox;
        this.json = json;
    }

    /** Validates (merged with the existing row on update) and clears certification when the definition changes. */
    public void prepare(ObjectNode values, JsonNode existing) {
        JsonNode merged = existing == null ? values : ((ObjectNode) existing.deepCopy()).setAll(values);
        DatasetCatalog.Dataset d = datasets.require(Fields.requireText(merged, "dataset"));
        compile(merged.get("metric"), d);
        String timeField = Fields.text(merged, "time_field");
        if (timeField != null) {
            String type = d.fields().get(timeField);
            if (!"DATE".equals(type) && !"TIMESTAMP".equals(type)) {
                throw Fields.invalid("time_field", "invalid_time_field", "time_field must be a DATE or TIMESTAMP field of " + d.name());
            }
        }
        String unit = Fields.text(merged, "unit");
        if (unit != null && !UNITS.contains(unit)) {
            throw Fields.invalid("unit", "invalid_unit", "unit must be one of " + UNITS);
        }
        if (existing != null && existing.path("is_certified").asBoolean(false)
                && DEFINITION_FIELDS.stream().anyMatch(f -> values.has(f) && !values.get(f).equals(existing.get(f)))) {
            values.put("is_certified", false);
            values.putNull("certified_by");
            values.putNull("certified_at");
        }
    }

    MetricSpec compile(JsonNode metric, DatasetCatalog.Dataset d) {
        if (metric == null || !metric.isObject()) {
            throw Fields.invalid("metric", "required", "metric spec object is required");
        }
        try {
            return MetricSpec.compile(json.convertValue(metric, new TypeReference<Map<String, Object>>() {}), d.fields().keySet());
        } catch (QueryDsl.QueryException e) {
            throw new ApiException(422, "invalid_metric", e.getMessage(),
                    e.errors().stream().map(m -> new ApiException.FieldError("metric", "invalid", m)).toList());
        }
    }

    @Transactional
    public String certify(UUID id, String comment) {
        JsonNode k = store.requireLocked("analytics_kpi", id);
        if (k.path("is_certified").asBoolean(false)) {
            throw ApiException.conflict("already_certified", "KPI " + k.get("code").asString() + " is already certified");
        }
        if (TenantContext.principal().equals(Fields.text(k, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The author of a KPI cannot certify it");
        }
        DatasetCatalog.Dataset d = datasets.require(k.get("dataset").asString());
        compile(k.get("metric"), d);
        JsonNode row = store.updateReturning("""
                update analytics_kpi t set is_certified = true, certified_by = ?, certified_at = now(), updated_by = ?
                where t.id = ? returning row_to_json(t)::text""", TenantContext.principal(), TenantContext.principal(), id)
                .orElseThrow();
        ObjectNode payload = ((ObjectNode) row).deepCopy();
        if (comment != null) {
            payload.put("comment", comment);
        }
        outbox.publish("reporting.analytics_kpi.certified", "analytics_kpi", id, payload);
        return row.toString();
    }

    /** Point value over provided rows or the dataset, optionally filtered. */
    public ObjectNode evaluate(UUID id, JsonNode rows, JsonNode filters) {
        JsonNode k = store.require("analytics_kpi", id);
        DatasetCatalog.Dataset d = datasets.require(k.get("dataset").asString());
        MetricSpec metric = compile(k.get("metric"), d);
        boolean provided = rows != null && !rows.isNull();
        List<Map<String, Object>> data = provided ? datasets.provided(d, rows) : datasets.rows(d);
        Predicate<Map<String, Object>> filter = filter(filters, d);
        List<Map<String, Object>> in = data.stream().filter(filter).toList();
        BigDecimal value;
        try {
            value = metric.evaluate(in, Map.of());
        } catch (Expr.ExprException e) {
            throw ApiException.unprocessable("evaluation_failed", e.getMessage());
        }
        ObjectNode out = json.createObjectNode();
        out.put("kpi_id", id.toString());
        out.put("code", k.get("code").asString());
        out.put("unit", k.get("unit").asString());
        out.put("value", value == null ? null : value.toPlainString());
        out.put("row_count", in.size());
        out.put("source", provided ? "PROVIDED" : "DATASET");
        out.put("dataset", d.name());
        out.put("is_certified", k.path("is_certified").asBoolean(false));
        return out;
    }

    /** Series over the dataset's time field. */
    public ObjectNode values(UUID id, LocalDate from, LocalDate to, String granularity) {
        JsonNode k = store.require("analytics_kpi", id);
        String g = granularity == null ? "DAY" : granularity.toUpperCase(Locale.ROOT);
        if (!Set.of("DAY", "WEEK", "MONTH").contains(g)) {
            throw Fields.invalid("granularity", "invalid_granularity", "granularity must be DAY, WEEK or MONTH");
        }
        if (from == null || to == null || to.isBefore(from)) {
            throw Fields.invalid("to", "invalid_range", "from and to are required and to must not be before from");
        }
        long days = ChronoUnit.DAYS.between(from, to);
        if (("DAY".equals(g) && days > 366) || days > 3660) {
            throw Fields.invalid("to", "range_too_long", "Range too long for " + g + " granularity");
        }
        DatasetCatalog.Dataset d = datasets.require(k.get("dataset").asString());
        String timeField = Fields.text(k, "time_field") != null ? k.get("time_field").asString() : d.timeField();
        if (timeField == null) {
            throw ApiException.unprocessable("no_time_field", "Dataset " + d.name() + " has no time field; values need one");
        }
        MetricSpec metric = compile(k.get("metric"), d);
        List<Map<String, Object>> series;
        try {
            series = metric.series(datasets.rows(d), timeField, from, to, g, Map.of());
        } catch (Expr.ExprException e) {
            throw ApiException.unprocessable("evaluation_failed", e.getMessage());
        }
        ObjectNode out = json.createObjectNode();
        out.put("kpi_id", id.toString());
        out.put("code", k.get("code").asString());
        out.put("unit", k.get("unit").asString());
        out.put("granularity", g);
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("time_field", timeField);
        List<Map<String, Object>> points = new ArrayList<>();
        for (Map<String, Object> p : series) {
            Object v = p.get("value");
            p.put("value", v == null ? null : ((BigDecimal) v).toPlainString());
            points.add(p);
        }
        out.set("points", json.valueToTree(points));
        return out;
    }

    Predicate<Map<String, Object>> filter(JsonNode filters, DatasetCatalog.Dataset d) {
        if (filters == null || filters.isNull()) {
            return r -> true;
        }
        if (!filters.isArray()) {
            throw Fields.invalid("filters", "invalid_type", "filters must be a list of {field, op, value}");
        }
        List<String> errors = new ArrayList<>();
        List<Filters.Filter> parsed = Filters.parse(json.convertValue(filters, new TypeReference<List<Map<String, Object>>>() {}), errors);
        parsed.stream().filter(f -> !d.fields().containsKey(f.field()))
                .forEach(f -> errors.add("filter field " + f.field() + " is not a field of " + d.name()));
        if (!errors.isEmpty()) {
            throw Fields.invalid("filters", "invalid_filter", String.join("; ", errors));
        }
        return Filters.predicate(parsed, d.fields().keySet());
    }
}
