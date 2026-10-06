package io.meridian.analytics.api;

import io.meridian.analytics.api.generated.AnalyticsKpisApi;
import io.meridian.analytics.api.generated.model.AnalyticsKpi;
import io.meridian.analytics.api.generated.model.AnalyticsKpiCreate;
import io.meridian.analytics.api.generated.model.AnalyticsKpiUpdate;
import io.meridian.analytics.api.generated.model.AnalyticsKpisCertifyRequest;
import io.meridian.analytics.api.generated.model.AnalyticsKpisEvaluateRequest;
import io.meridian.analytics.application.Fields;
import io.meridian.analytics.application.KpiService;
import io.meridian.analytics.infrastructure.Store;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Governed KPIs: validated metric specs, certification (four eyes), evaluation and time series. */
@RestController
public class AnalyticsKpisController implements AnalyticsKpisApi {
    private final KpiService kpis;
    private final Idempotency idempotency;
    private final Responses responses;
    private final CrudEngine crud;
    private final Store store;
    private final JsonMapper json;

    public AnalyticsKpisController(KpiService kpis, Idempotency idempotency, Responses responses, CrudEngine crud,
                                   Store store, JsonMapper json) {
        this.kpis = kpis;
        this.idempotency = idempotency;
        this.responses = responses;
        this.crud = crud;
        this.store = store;
        this.json = json;
    }

    @Override
    public ResponseEntity<AnalyticsKpi> analyticsKpisCreate(String idempotencyKey, AnalyticsKpiCreate body) {
        TenantContext.require();
        ObjectNode values = crud.toColumns("analytics_kpi", body);
        kpis.prepare(values, null);
        return idempotency.execute(idempotencyKey, body, AnalyticsKpi.class,
                () -> responses.created(kpis.create(values), AnalyticsKpi.class));
    }

    @Override
    public ResponseEntity<AnalyticsKpi> analyticsKpisUpdate(UUID id, String ifMatch, AnalyticsKpiUpdate body) {
        TenantContext.require();
        int version = Fields.ifMatch(ifMatch);
        JsonNode existing = store.require("analytics_kpi", id);
        ObjectNode values = crud.toColumns("analytics_kpi", body);
        if (values.isEmpty()) {
            return responses.ok(existing.toString(), AnalyticsKpi.class);
        }
        kpis.prepare(values, existing);
        String row = kpis.update(id, values, version, existing).orElseThrow(() ->
                ApiException.conflict("version_conflict", "If-Match " + version + " does not match the current version; reload and retry"));
        return responses.ok(row, AnalyticsKpi.class);
    }

    @Override
    public ResponseEntity<AnalyticsKpi> analyticsKpisCertify(UUID id, String idempotencyKey, AnalyticsKpisCertifyRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("certify", id, req), AnalyticsKpi.class,
                () -> responses.ok(kpis.certify(id, Fields.text(req, "comment")), AnalyticsKpi.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> analyticsKpisEvaluate(UUID id, String idempotencyKey,
                                                                    AnalyticsKpisEvaluateRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return responses.object(idempotency.execute(idempotencyKey, responses.action("evaluate", id, req), JsonNode.class,
                () -> ResponseEntity.ok(kpis.evaluate(id, req.get("rows"), req.get("filters")))));
    }

    @Override
    public ResponseEntity<Map<String, Object>> analyticsKpisValues(UUID id, LocalDate from, LocalDate to, String granularity) {
        TenantContext.require();
        return responses.object(kpis.values(id, from, to, granularity));
    }
}
