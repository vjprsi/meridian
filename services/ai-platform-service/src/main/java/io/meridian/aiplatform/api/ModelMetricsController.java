package io.meridian.aiplatform.api;

import io.meridian.aiplatform.api.generated.ModelMetricsApi;
import io.meridian.aiplatform.api.generated.model.ModelMonitoringMetric;
import io.meridian.aiplatform.api.generated.model.ModelMonitoringMetricCreate;
import io.meridian.aiplatform.application.DeploymentService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.idempotency.Idempotency;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Monitoring metrics (append-only): thresholds defaulted, breach computed server-side, breaches alerted. */
@RestController
public class ModelMetricsController implements ModelMetricsApi {
    private final DeploymentService deployments;
    private final Idempotency idempotency;
    private final Responses responses;
    private final JsonMapper json;

    public ModelMetricsController(DeploymentService deployments, Idempotency idempotency, Responses responses, JsonMapper json) {
        this.deployments = deployments;
        this.idempotency = idempotency;
        this.responses = responses;
        this.json = json;
    }

    @Override
    public ResponseEntity<ModelMonitoringMetric> modelMetricsCreate(String idempotencyKey, ModelMonitoringMetricCreate body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, body, ModelMonitoringMetric.class,
                () -> responses.created(deployments.recordMetric(req), ModelMonitoringMetric.class));
    }
}
