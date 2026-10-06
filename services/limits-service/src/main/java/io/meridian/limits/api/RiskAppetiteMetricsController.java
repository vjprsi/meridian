package io.meridian.limits.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.limits.api.generated.RiskAppetiteMetricsApi;
import io.meridian.limits.api.generated.model.RiskAppetiteMeasurementPage;
import io.meridian.limits.api.generated.model.RiskAppetiteMetric;
import io.meridian.limits.api.generated.model.RiskAppetiteMetricCreate;
import io.meridian.limits.api.generated.model.RiskAppetiteMetricUpdate;
import io.meridian.limits.api.generated.model.RiskAppetiteMetricsMeasurementsRequest;
import io.meridian.limits.application.LimitAdminService;
import io.meridian.limits.infrastructure.Db;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Board-approved risk appetite metrics and their measurement history. */
@RestController
public class RiskAppetiteMetricsController implements RiskAppetiteMetricsApi {
    private final LimitAdminService admin;
    private final CrudEngine crud;
    private final Db db;
    private final Http http;

    public RiskAppetiteMetricsController(LimitAdminService admin, CrudEngine crud, Db db, Http http) {
        this.admin = admin;
        this.crud = crud;
        this.db = db;
        this.http = http;
    }

    @Override
    public ResponseEntity<RiskAppetiteMetric> riskAppetiteMetricsCreate(String idempotencyKey, RiskAppetiteMetricCreate body) {
        TenantContext.require();
        admin.validateMetric(null, http.tree(body));
        return crud.create("risk_appetite_metric", RiskAppetiteMetric.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<RiskAppetiteMetric> riskAppetiteMetricsUpdate(UUID id, String ifMatch, RiskAppetiteMetricUpdate body) {
        TenantContext.require();
        admin.validateMetric(db.require("risk_appetite_metric", id, false), http.tree(body));
        return crud.update("risk_appetite_metric", RiskAppetiteMetric.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<RiskAppetiteMetric> riskAppetiteMetricsMeasurements(UUID id, String idempotencyKey,
                                                                              RiskAppetiteMetricsMeasurementsRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, RiskAppetiteMetric.class, () -> http.ok(admin.measure(id,
                Db.dec(req, "current_value"), Http.text(req, "measured_at"), Http.text(req, "source")), RiskAppetiteMetric.class));
    }

    @Override
    public ResponseEntity<RiskAppetiteMeasurementPage> riskAppetiteMetricsGetMeasurements(UUID id, Integer limit, String cursor) {
        crud.get("risk_appetite_metric", Object.class, Crud.params().with("id", id));
        return crud.list("risk_appetite_measurement", RiskAppetiteMeasurementPage.class,
                Crud.params().with("risk_appetite_metric_id", id).with("limit", limit).with("cursor", cursor));
    }
}
