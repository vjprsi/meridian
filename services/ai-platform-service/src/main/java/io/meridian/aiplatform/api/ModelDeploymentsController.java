package io.meridian.aiplatform.api;

import io.meridian.aiplatform.api.generated.ModelDeploymentsApi;
import io.meridian.aiplatform.api.generated.model.ModelDeployment;
import io.meridian.aiplatform.api.generated.model.ModelDeploymentUpdate;
import io.meridian.aiplatform.api.generated.model.ModelDeploymentsDriftRequest;
import io.meridian.aiplatform.api.generated.model.ModelDeploymentsPromoteRequest;
import io.meridian.aiplatform.api.generated.model.ModelDeploymentsRetireRequest;
import io.meridian.aiplatform.api.generated.model.ModelDeploymentsRollbackRequest;
import io.meridian.aiplatform.api.generated.model.ModelMonitoringMetricPage;
import io.meridian.aiplatform.application.DeploymentService;
import io.meridian.aiplatform.application.Fields;
import io.meridian.aiplatform.application.ScheduledJobs;
import io.meridian.aiplatform.infrastructure.Store;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Deployments: promote / rollback / retire, metrics, PSI drift, the per-tenant monitoring job. */
@RestController
public class ModelDeploymentsController implements ModelDeploymentsApi {
    private final DeploymentService deployments;
    private final ScheduledJobs jobs;
    private final Idempotency idempotency;
    private final Responses responses;
    private final CrudEngine crud;
    private final Store store;
    private final JsonMapper json;

    public ModelDeploymentsController(DeploymentService deployments, ScheduledJobs jobs, Idempotency idempotency,
                                      Responses responses, CrudEngine crud, Store store, JsonMapper json) {
        this.deployments = deployments;
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.responses = responses;
        this.crud = crud;
        this.store = store;
        this.json = json;
    }

    @Override
    public ResponseEntity<ModelDeployment> modelDeploymentsUpdate(UUID id, String ifMatch, ModelDeploymentUpdate body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        if (req.hasNonNull("thresholds")) {
            JsonNode t = req.get("thresholds");
            if (!t.isObject()) {
                throw Fields.invalid("thresholds", "invalid_type", "thresholds must be an object");
            }
            t.propertyNames().forEach(k -> {
                if (!io.meridian.aiplatform.domain.Drift.METRICS.contains(k) || !Fields.numeric(t.get(k))) {
                    throw Fields.invalid("thresholds." + k, "invalid_threshold", "thresholds map known metric names to numbers");
                }
            });
        }
        return crud.update("model_deployment", ModelDeployment.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<ModelDeployment> modelDeploymentsPromote(UUID id, String idempotencyKey, ModelDeploymentsPromoteRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("promote", id, req), ModelDeployment.class,
                () -> responses.ok(deployments.promote(id, Fields.integer(req, "traffic_percent"), Fields.text(req, "comment")),
                        ModelDeployment.class));
    }

    @Override
    public ResponseEntity<ModelDeployment> modelDeploymentsRollback(UUID id, String idempotencyKey, ModelDeploymentsRollbackRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String reason = Fields.requireText(req, "reason");
        return idempotency.execute(idempotencyKey, responses.action("rollback", id, req), ModelDeployment.class,
                () -> responses.ok(deployments.rollback(id, reason), ModelDeployment.class));
    }

    @Override
    public ResponseEntity<ModelDeployment> modelDeploymentsRetire(UUID id, String idempotencyKey, ModelDeploymentsRetireRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("retire", id, req), ModelDeployment.class,
                () -> responses.ok(deployments.retire(id, Fields.text(req, "reason")), ModelDeployment.class));
    }

    @Override
    public ResponseEntity<ModelMonitoringMetricPage> modelDeploymentsMetrics(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        store.require("model_deployment", id);
        return crud.list("model_monitoring_metric", ModelMonitoringMetricPage.class,
                Crud.params().with("model_deployment_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<Map<String, Object>> modelDeploymentsDrift(UUID id, String idempotencyKey, ModelDeploymentsDriftRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return responses.object(idempotency.execute(idempotencyKey, responses.action("drift", id, req), JsonNode.class,
                () -> ResponseEntity.ok(deployments.drift(id, req))));
    }

    @Override
    public ResponseEntity<Map<String, Object>> modelDeploymentsMonitor(LocalDate businessDate, String idempotencyKey) {
        TenantContext.require();
        JsonNode fp = json.createObjectNode().put("job", "MODEL_MONITORING").put("business_date", String.valueOf(businessDate));
        return responses.object(idempotency.execute(idempotencyKey, fp, JsonNode.class,
                () -> ResponseEntity.ok(jobs.run("MODEL_MONITORING", businessDate,
                        (counts, errors) -> deployments.monitor(businessDate, counts, errors)))));
    }
}
