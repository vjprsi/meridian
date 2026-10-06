package io.meridian.analytics.api;

import io.meridian.analytics.api.generated.AnalyticsWidgetsApi;
import io.meridian.analytics.api.generated.model.AnalyticsWidget;
import io.meridian.analytics.api.generated.model.AnalyticsWidgetCreate;
import io.meridian.analytics.api.generated.model.AnalyticsWidgetUpdate;
import io.meridian.analytics.application.DashboardService;
import io.meridian.analytics.application.Fields;
import io.meridian.analytics.infrastructure.Store;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Widgets: consistency of type and data source, owner-only changes, data with dashboard filters. */
@RestController
public class AnalyticsWidgetsController implements AnalyticsWidgetsApi {
    private final DashboardService dashboards;
    private final Idempotency idempotency;
    private final Responses responses;
    private final CrudEngine crud;
    private final Store store;

    public AnalyticsWidgetsController(DashboardService dashboards, Idempotency idempotency, Responses responses,
                                      CrudEngine crud, Store store) {
        this.dashboards = dashboards;
        this.idempotency = idempotency;
        this.responses = responses;
        this.crud = crud;
        this.store = store;
    }

    @Override
    public ResponseEntity<AnalyticsWidget> analyticsWidgetsCreate(String idempotencyKey, AnalyticsWidgetCreate body) {
        TenantContext.require();
        ObjectNode values = crud.toColumns("analytics_widget", body);
        dashboards.checkWidget(values, null);
        return idempotency.execute(idempotencyKey, body, AnalyticsWidget.class,
                () -> responses.created(crud.insert("analytics_widget", values), AnalyticsWidget.class));
    }

    @Override
    public ResponseEntity<AnalyticsWidget> analyticsWidgetsGet(UUID id) {
        TenantContext.require();
        JsonNode w = store.require("analytics_widget", id);
        dashboards.visible(UUID.fromString(w.get("analytics_dashboard_id").asString()));
        return responses.ok(w.toString(), AnalyticsWidget.class);
    }

    @Override
    public ResponseEntity<AnalyticsWidget> analyticsWidgetsUpdate(UUID id, String ifMatch, AnalyticsWidgetUpdate body) {
        TenantContext.require();
        int version = Fields.ifMatch(ifMatch);
        JsonNode existing = store.require("analytics_widget", id);
        ObjectNode values = crud.toColumns("analytics_widget", body);
        dashboards.owned(UUID.fromString(existing.get("analytics_dashboard_id").asString()));
        if (values.isEmpty()) {
            return responses.ok(existing.toString(), AnalyticsWidget.class);
        }
        dashboards.checkWidget(values, existing);
        String row = crud.updateRow("analytics_widget", Map.of("id", id), values, version).orElseThrow(() ->
                ApiException.conflict("version_conflict", "If-Match " + version + " does not match the current version; reload and retry"));
        return responses.ok(row, AnalyticsWidget.class);
    }

    @Override
    public ResponseEntity<Void> analyticsWidgetsDelete(UUID id) {
        TenantContext.require();
        JsonNode existing = store.require("analytics_widget", id);
        dashboards.owned(UUID.fromString(existing.get("analytics_dashboard_id").asString()));
        return crud.delete("analytics_widget", Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<Map<String, Object>> analyticsWidgetsData(UUID id) {
        TenantContext.require();
        return responses.object(dashboards.widgetData(id));
    }
}
