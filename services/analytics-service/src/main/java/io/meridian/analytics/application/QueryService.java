package io.meridian.analytics.application;

import io.meridian.analytics.domain.DatasetCatalog;
import io.meridian.analytics.domain.Expr;
import io.meridian.analytics.domain.QueryDsl;
import io.meridian.analytics.infrastructure.Store;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Saved queries in the Meridian query DSL: validated on save (dataset derived from the query, every parameter
 * declared), visible to their owner only when PRIVATE, run in memory over the whitelisted dataset with typed
 * parameters.
 */
@Service
public class QueryService {
    static final Set<String> PARAM_TYPES = Set.of("STRING", "NUMBER", "DATE", "BOOL");

    private final Store store;
    private final CrudEngine crud;
    private final DatasetService datasets;
    private final JsonMapper json;

    public QueryService(Store store, CrudEngine crud, DatasetService datasets, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.datasets = datasets;
        this.json = json;
    }

    /** Parses a query; 422 invalid_query with one field error per problem. */
    public QueryDsl.Query compile(String query) {
        try {
            return QueryDsl.parse(query, name -> DatasetCatalog.find(name).map(DatasetCatalog.Dataset::fields));
        } catch (QueryDsl.QueryException e) {
            throw new ApiException(422, "invalid_query", e.getMessage(),
                    e.errors().stream().map(m -> new ApiException.FieldError("query", "invalid", m)).toList());
        }
    }

    /** Validation endpoint: never fails on an invalid query, reports it. */
    public ObjectNode validate(String query) {
        ObjectNode out = json.createObjectNode();
        try {
            QueryDsl.Query q = QueryDsl.parse(query, name -> DatasetCatalog.find(name).map(DatasetCatalog.Dataset::fields));
            out.put("valid", true);
            out.put("dataset", q.dataset());
            ArrayNode cols = out.putArray("columns");
            q.columns(DatasetCatalog.find(q.dataset()).orElseThrow().fields()).forEach(cols::add);
            ArrayNode ps = out.putArray("parameters");
            q.parameters().forEach(ps::add);
            out.put("aggregated", q.aggregated());
            out.putArray("errors");
        } catch (QueryDsl.QueryException e) {
            out.put("valid", false);
            ArrayNode errs = out.putArray("errors");
            e.errors().forEach(errs::add);
        }
        return out;
    }

    /** Column values for a create / update: dataset from the query, declared parameters checked. */
    public void prepare(ObjectNode values, JsonNode existing) {
        String query = values.hasNonNull("query") ? values.get("query").asString() : existing == null ? null : Fields.text(existing, "query");
        JsonNode params = values.has("parameters") ? values.get("parameters") : existing == null ? null : existing.get("parameters");
        if (query == null) {
            throw Fields.invalid("query", "required", "query is required");
        }
        QueryDsl.Query q = compile(query);
        Set<String> declared = checkDefinitions(params);
        for (String p : q.parameters()) {
            if (!declared.contains(p)) {
                throw Fields.invalid("parameters", "undeclared_parameter", "Parameter :" + p + " is used but not declared");
            }
        }
        values.put("dataset", q.dataset());
    }

    private Set<String> checkDefinitions(JsonNode params) {
        Set<String> names = new HashSet<>();
        if (params == null || params.isNull()) {
            return names;
        }
        if (!params.isArray()) {
            throw Fields.invalid("parameters", "invalid_type", "parameters must be a list of {name, type, required, default}");
        }
        for (JsonNode p : params) {
            String name = p.path("name").asString("");
            String type = p.path("type").asString("STRING").toUpperCase(Locale.ROOT);
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || !names.add(name)) {
                throw Fields.invalid("parameters", "invalid_parameter", "Parameter names must be unique identifiers: '" + name + "'");
            }
            if (!PARAM_TYPES.contains(type)) {
                throw Fields.invalid("parameters", "invalid_parameter", "Parameter " + name + " type must be one of " + PARAM_TYPES);
            }
            if (p.hasNonNull("default")) {
                convert(name, type, json.convertValue(p.get("default"), Object.class));
            }
        }
        return names;
    }

    /** Typed parameter values: declared defaults applied, required ones enforced, unknown ones rejected. */
    public Map<String, Object> resolve(JsonNode definitions, JsonNode provided) {
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> declared = new HashSet<>();
        if (definitions != null && definitions.isArray()) {
            for (JsonNode d : definitions) {
                String name = d.path("name").asString();
                declared.add(name);
                String type = d.path("type").asString("STRING").toUpperCase(Locale.ROOT);
                JsonNode v = provided != null && provided.hasNonNull(name) ? provided.get(name) : d.get("default");
                if (v == null || v.isNull()) {
                    if (d.path("required").asBoolean(false)) {
                        throw Fields.invalid("parameters." + name, "missing_parameter", "Parameter " + name + " is required");
                    }
                    out.put(name, null);
                    continue;
                }
                out.put(name, convert(name, type, json.convertValue(v, Object.class)));
            }
        }
        if (provided != null && provided.isObject()) {
            provided.propertyNames().forEach(name -> {
                if (!declared.contains(name)) {
                    throw Fields.invalid("parameters." + name, "unknown_parameter", "Parameter " + name + " is not declared by the query");
                }
            });
        }
        return out;
    }

    private static Object convert(String name, String type, Object v) {
        switch (type) {
            case "NUMBER" -> {
                BigDecimal d = Expr.toDecimal(v);
                if (d == null) {
                    throw Fields.invalid("parameters." + name, "invalid_parameter", "Parameter " + name + " must be a number");
                }
                return d;
            }
            case "BOOL" -> {
                if (v instanceof Boolean b) {
                    return b;
                }
                String s = String.valueOf(v);
                if (!s.equalsIgnoreCase("true") && !s.equalsIgnoreCase("false")) {
                    throw Fields.invalid("parameters." + name, "invalid_parameter", "Parameter " + name + " must be true or false");
                }
                return Boolean.parseBoolean(s);
            }
            case "DATE" -> {
                try {
                    return LocalDate.parse(String.valueOf(v)).toString();
                } catch (DateTimeParseException e) {
                    throw Fields.invalid("parameters." + name, "invalid_parameter", "Parameter " + name + " must be an ISO date");
                }
            }
            default -> {
                if (v instanceof List<?> list) {
                    return list;
                }
                return String.valueOf(v);
            }
        }
    }

    /** A query visible to the caller (PRIVATE queries only to their owner); others are reported as not found. */
    public JsonNode visible(UUID id) {
        JsonNode q = store.require("analytics_saved_query", id);
        if ("PRIVATE".equals(q.get("visibility").asString()) && !TenantContext.principal().equals(Fields.text(q, "owner"))) {
            throw ApiException.notFound("analytics saved query", id);
        }
        return q;
    }

    /** Owner-only changes. */
    public JsonNode owned(UUID id) {
        JsonNode q = visible(id);
        if (!TenantContext.principal().equals(Fields.text(q, "owner"))) {
            throw new ApiException(403, "not_owner", "Only the owner can change or delete this query");
        }
        return q;
    }

    /** Runs a saved query; records last_run_at / last_row_count. */
    public ObjectNode run(UUID id, JsonNode provided, Integer limit, Predicate<Map<String, Object>> extraFilter) {
        JsonNode saved = visible(id);
        int cap = limit == null ? 100 : limit;
        if (cap < 1 || cap > 1000) {
            throw Fields.invalid("limit", "out_of_range", "limit must be 1-1000");
        }
        ObjectNode out = execute(saved, provided, cap, extraFilter);
        store.update("update analytics_saved_query set last_run_at = now(), last_row_count = ?, updated_by = ? where id = ?",
                out.get("row_count").asInt(), TenantContext.principal(), id);
        return out;
    }

    /** Executes a saved query row without bookkeeping (exports, widgets). */
    public ObjectNode execute(JsonNode saved, JsonNode provided, int cap, Predicate<Map<String, Object>> extraFilter) {
        QueryDsl.Query q = compile(saved.get("query").asString());
        Map<String, Object> params = resolve(saved.get("parameters"), provided);
        DatasetCatalog.Dataset d = datasets.require(q.dataset());
        List<Map<String, Object>> rows = datasets.rows(d);
        QueryDsl.Result r;
        try {
            r = QueryDsl.execute(q, d.fields(), rows, params, extraFilter, cap);
        } catch (Expr.ExprException e) {
            throw ApiException.unprocessable("query_failed", e.getMessage());
        }
        ObjectNode out = json.createObjectNode();
        out.put("saved_query_id", saved.get("id").asString());
        out.put("dataset", q.dataset());
        ArrayNode cols = out.putArray("columns");
        r.columns().forEach(cols::add);
        out.set("rows", json.valueToTree(r.rows()));
        out.put("row_count", r.rows().size());
        out.put("matched_rows", r.matched());
        out.put("truncated", r.truncated());
        return out;
    }

    public Optional<JsonNode> find(UUID id) {
        return store.find("analytics_saved_query", id);
    }

    public CrudEngine crud() {
        return crud;
    }
}
