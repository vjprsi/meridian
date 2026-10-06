package io.meridian.analytics.api;

import io.meridian.analytics.api.generated.AnalyticsDashboardsApi;
import io.meridian.analytics.api.generated.model.AnalyticsDashboard;
import io.meridian.analytics.api.generated.model.AnalyticsDashboardCreate;
import io.meridian.analytics.api.generated.model.AnalyticsDashboardPage;
import io.meridian.analytics.api.generated.model.AnalyticsDashboardUpdate;
import io.meridian.analytics.api.generated.model.AnalyticsDashboardsDuplicateRequest;
import io.meridian.analytics.api.generated.model.AnalyticsDashboardsEmbedTokensVerifyRequest;
import io.meridian.analytics.api.generated.model.AnalyticsWidgetPage;
import io.meridian.analytics.application.DashboardService;
import io.meridian.analytics.application.Fields;
import io.meridian.analytics.infrastructure.Store;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Dashboards: owner / visibility rules, duplicate, signed embed tokens. */
@RestController
public class AnalyticsDashboardsController implements AnalyticsDashboardsApi {
    private final DashboardService dashboards;
    private final Idempotency idempotency;
    private final Responses responses;
    private final CrudEngine crud;
    private final Store store;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    public AnalyticsDashboardsController(DashboardService dashboards, Idempotency idempotency, Responses responses,
                                         CrudEngine crud, Store store, JsonMapper json, TransactionTemplate tx) {
        this.dashboards = dashboards;
        this.idempotency = idempotency;
        this.responses = responses;
        this.crud = crud;
        this.store = store;
        this.json = json;
        this.tx = tx;
    }

    @Override
    public ResponseEntity<AnalyticsDashboard> analyticsDashboardsCreate(String idempotencyKey, AnalyticsDashboardCreate body) {
        TenantContext.require();
        ObjectNode values = crud.toColumns("analytics_dashboard", body);
        dashboards.checkFilters(values.get("filters"));
        values.put("owner", TenantContext.principal());
        values.put("is_system", false);
        return idempotency.execute(idempotencyKey, body, AnalyticsDashboard.class,
                () -> responses.created(crud.insert("analytics_dashboard", values), AnalyticsDashboard.class));
    }

    @Override
    public ResponseEntity<AnalyticsDashboard> analyticsDashboardsGet(UUID id) {
        TenantContext.require();
        return responses.ok(dashboards.visible(id).toString(), AnalyticsDashboard.class);
    }

    @Override
    public ResponseEntity<AnalyticsDashboardPage> analyticsDashboardsList(Integer limit, String cursor, OffsetDateTime createdAfter,
                                                                         OffsetDateTime createdBefore, String owner,
                                                                         String visibility, Boolean isSystem,
                                                                         String customField1, String customField2,
                                                                         String customField3, String customField4,
                                                                         String customField5) {
        TenantContext.require();
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("owner", owner);
        filters.put("visibility", visibility);
        filters.put("is_system", isSystem);
        filters.put("custom_field_1", customField1);
        filters.put("custom_field_2", customField2);
        filters.put("custom_field_3", customField3);
        filters.put("custom_field_4", customField4);
        filters.put("custom_field_5", customField5);
        String condition = "(t.visibility <> 'PRIVATE' or t.owner = ?)"
                + (createdAfter == null ? "" : " and t.created_at >= ?") + (createdBefore == null ? "" : " and t.created_at < ?");
        List<Object> args = new ArrayList<>(List.of(TenantContext.principal()));
        if (createdAfter != null) {
            args.add(createdAfter);
        }
        if (createdBefore != null) {
            args.add(createdBefore);
        }
        return ResponseEntity.ok(store.page("analytics_dashboard", condition, args, filters, limit, cursor, AnalyticsDashboardPage.class));
    }

    @Override
    public ResponseEntity<AnalyticsDashboard> analyticsDashboardsUpdate(UUID id, String ifMatch, AnalyticsDashboardUpdate body) {
        TenantContext.require();
        int version = Fields.ifMatch(ifMatch);
        JsonNode existing = dashboards.owned(id);
        ObjectNode values = crud.toColumns("analytics_dashboard", body);
        if (values.isEmpty()) {
            return responses.ok(existing.toString(), AnalyticsDashboard.class);
        }
        dashboards.checkFilters(values.get("filters"));
        String row = crud.updateRow("analytics_dashboard", Map.of("id", id), values, version).orElseThrow(() ->
                ApiException.conflict("version_conflict", "If-Match " + version + " does not match the current version; reload and retry"));
        return responses.ok(row, AnalyticsDashboard.class);
    }

    @Override
    public ResponseEntity<Void> analyticsDashboardsDelete(UUID id) {
        TenantContext.require();
        dashboards.owned(id);
        tx.executeWithoutResult(s -> {
            store.update("delete from analytics_widget where analytics_dashboard_id = ?", id);
            crud.delete("analytics_dashboard", Crud.params().with("id", id));
        });
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<AnalyticsWidgetPage> analyticsDashboardsWidgets(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        dashboards.visible(id);
        return crud.list("analytics_widget", AnalyticsWidgetPage.class,
                Crud.params().with("analytics_dashboard_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<AnalyticsDashboard> analyticsDashboardsDuplicate(UUID id, String idempotencyKey,
                                                                          AnalyticsDashboardsDuplicateRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String name = Fields.requireText(req, "name");
        return idempotency.execute(idempotencyKey, responses.action("duplicate", id, req), AnalyticsDashboard.class,
                () -> responses.created(dashboards.duplicate(id, name), AnalyticsDashboard.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> analyticsDashboardsEmbedToken(UUID id, Integer ttlSeconds) {
        TenantContext.require();
        return responses.object(dashboards.embedToken(id, ttlSeconds));
    }

    @Override
    public ResponseEntity<Map<String, Object>> analyticsDashboardsEmbedTokensVerify(String idempotencyKey,
                                                                                   AnalyticsDashboardsEmbedTokensVerifyRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String token = Fields.requireText(req, "token");
        return responses.object(idempotency.execute(idempotencyKey, req, JsonNode.class,
                () -> ResponseEntity.ok(dashboards.verify(token))));
    }
}
