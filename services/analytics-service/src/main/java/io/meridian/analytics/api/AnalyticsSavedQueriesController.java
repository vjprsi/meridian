package io.meridian.analytics.api;

import io.meridian.analytics.api.generated.AnalyticsSavedQueriesApi;
import io.meridian.analytics.api.generated.model.AnalyticsExportJob;
import io.meridian.analytics.api.generated.model.AnalyticsSavedQueriesExportRequest;
import io.meridian.analytics.api.generated.model.AnalyticsSavedQueriesRunRequest;
import io.meridian.analytics.api.generated.model.AnalyticsSavedQueriesValidateRequest;
import io.meridian.analytics.api.generated.model.AnalyticsSavedQuery;
import io.meridian.analytics.api.generated.model.AnalyticsSavedQueryCreate;
import io.meridian.analytics.api.generated.model.AnalyticsSavedQueryPage;
import io.meridian.analytics.api.generated.model.AnalyticsSavedQueryUpdate;
import io.meridian.analytics.application.ExportService;
import io.meridian.analytics.application.Fields;
import io.meridian.analytics.application.QueryService;
import io.meridian.analytics.domain.DatasetCatalog;
import io.meridian.analytics.infrastructure.Store;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Saved queries in the query DSL: owner / visibility rules, validate, run, export, dataset catalogue. */
@RestController
public class AnalyticsSavedQueriesController implements AnalyticsSavedQueriesApi {
    private final QueryService queries;
    private final ExportService exports;
    private final Idempotency idempotency;
    private final Responses responses;
    private final CrudEngine crud;
    private final Store store;
    private final JsonMapper json;

    public AnalyticsSavedQueriesController(QueryService queries, ExportService exports, Idempotency idempotency,
                                           Responses responses, CrudEngine crud, Store store, JsonMapper json) {
        this.queries = queries;
        this.exports = exports;
        this.idempotency = idempotency;
        this.responses = responses;
        this.crud = crud;
        this.store = store;
        this.json = json;
    }

    @Override
    public ResponseEntity<AnalyticsSavedQuery> analyticsSavedQueriesCreate(String idempotencyKey, AnalyticsSavedQueryCreate body) {
        TenantContext.require();
        ObjectNode values = crud.toColumns("analytics_saved_query", body);
        queries.prepare(values, null);
        values.put("owner", TenantContext.principal());
        return idempotency.execute(idempotencyKey, body, AnalyticsSavedQuery.class,
                () -> responses.created(crud.insert("analytics_saved_query", values), AnalyticsSavedQuery.class));
    }

    @Override
    public ResponseEntity<AnalyticsSavedQuery> analyticsSavedQueriesGet(UUID id) {
        TenantContext.require();
        return responses.ok(queries.visible(id).toString(), AnalyticsSavedQuery.class);
    }

    @Override
    public ResponseEntity<AnalyticsSavedQueryPage> analyticsSavedQueriesList(Integer limit, String cursor,
                                                                            OffsetDateTime createdAfter, OffsetDateTime createdBefore,
                                                                            String owner, String visibility, String dataset,
                                                                            String customField1, String customField2,
                                                                            String customField3, String customField4,
                                                                            String customField5) {
        TenantContext.require();
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("owner", owner);
        filters.put("visibility", visibility);
        filters.put("dataset", dataset);
        filters.put("custom_field_1", customField1);
        filters.put("custom_field_2", customField2);
        filters.put("custom_field_3", customField3);
        filters.put("custom_field_4", customField4);
        filters.put("custom_field_5", customField5);
        String condition = "(t.visibility <> 'PRIVATE' or t.owner = ?)"
                + (createdAfter == null ? "" : " and t.created_at >= ?") + (createdBefore == null ? "" : " and t.created_at < ?");
        List<Object> args = new java.util.ArrayList<>(List.of(TenantContext.principal()));
        if (createdAfter != null) {
            args.add(createdAfter);
        }
        if (createdBefore != null) {
            args.add(createdBefore);
        }
        return ResponseEntity.ok(store.page("analytics_saved_query", condition, args, filters, limit, cursor,
                AnalyticsSavedQueryPage.class));
    }

    @Override
    public ResponseEntity<AnalyticsSavedQuery> analyticsSavedQueriesUpdate(UUID id, String ifMatch, AnalyticsSavedQueryUpdate body) {
        TenantContext.require();
        int version = Fields.ifMatch(ifMatch);
        JsonNode existing = queries.owned(id);
        ObjectNode values = crud.toColumns("analytics_saved_query", body);
        if (values.isEmpty()) {
            return responses.ok(existing.toString(), AnalyticsSavedQuery.class);
        }
        queries.prepare(values, existing);
        String row = crud.updateRow("analytics_saved_query", Map.of("id", id), values, version).orElseThrow(() ->
                ApiException.conflict("version_conflict", "If-Match " + version + " does not match the current version; reload and retry"));
        return responses.ok(row, AnalyticsSavedQuery.class);
    }

    @Override
    public ResponseEntity<Void> analyticsSavedQueriesDelete(UUID id) {
        TenantContext.require();
        queries.owned(id);
        if (store.count("select count(*) from analytics_export_job where analytics_saved_query_id = ?", id) > 0
                || store.count("select count(*) from analytics_widget where analytics_saved_query_id = ?", id) > 0) {
            throw ApiException.conflict("query_in_use", "The query feeds widgets or has exports; remove them first");
        }
        return crud.delete("analytics_saved_query", Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<Map<String, Object>> analyticsSavedQueriesRun(UUID id, String idempotencyKey,
                                                                       AnalyticsSavedQueriesRunRequest body, Integer limit) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        ObjectNode fingerprint = json.createObjectNode();
        fingerprint.set("request", responses.action("run", id, req));
        fingerprint.put("limit", limit == null ? 100 : limit);
        return responses.object(idempotency.execute(idempotencyKey, fingerprint, JsonNode.class,
                () -> ResponseEntity.ok(queries.run(id, req.get("parameters"), limit, null))));
    }

    @Override
    public ResponseEntity<AnalyticsExportJob> analyticsSavedQueriesExport(UUID id, String idempotencyKey,
                                                                         AnalyticsSavedQueriesExportRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String format = Fields.requireText(req, "format");
        return idempotency.execute(idempotencyKey, responses.action("export", id, req), AnalyticsExportJob.class,
                () -> responses.created(exports.create(id, format, req.get("parameters")), AnalyticsExportJob.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> analyticsSavedQueriesValidate(String idempotencyKey,
                                                                            AnalyticsSavedQueriesValidateRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String query = Fields.requireText(req, "query");
        return responses.object(idempotency.execute(idempotencyKey, req, JsonNode.class,
                () -> ResponseEntity.ok(queries.validate(query))));
    }

    @Override
    public ResponseEntity<Map<String, Object>> analyticsSavedQueriesDatasets() {
        TenantContext.require();
        ObjectNode out = json.createObjectNode();
        ArrayNode list = out.putArray("datasets");
        DatasetCatalog.all().values().forEach(d -> {
            ObjectNode n = list.addObject();
            n.put("name", d.name());
            n.put("description", d.description());
            n.put("time_field", d.timeField());
            ObjectNode f = n.putObject("fields");
            d.fields().forEach(f::put);
        });
        return responses.object(out);
    }
}
