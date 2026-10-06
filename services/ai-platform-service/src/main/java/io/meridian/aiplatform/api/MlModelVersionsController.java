package io.meridian.aiplatform.api;

import io.meridian.aiplatform.api.generated.MlModelVersionsApi;
import io.meridian.aiplatform.api.generated.model.MlModelVersion;
import io.meridian.aiplatform.api.generated.model.MlModelVersionCreate;
import io.meridian.aiplatform.api.generated.model.MlModelVersionUpdate;
import io.meridian.aiplatform.api.generated.model.MlModelVersionsApproveRequest;
import io.meridian.aiplatform.api.generated.model.MlModelVersionsDeployRequest;
import io.meridian.aiplatform.api.generated.model.MlModelVersionsRejectRequest;
import io.meridian.aiplatform.api.generated.model.MlModelVersionsSubmitForApprovalRequest;
import io.meridian.aiplatform.api.generated.model.ModelDeployment;
import io.meridian.aiplatform.application.Fields;
import io.meridian.aiplatform.application.ModelService;
import io.meridian.aiplatform.infrastructure.Store;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Model versions: validated registration, submit / approve / reject (four eyes), deploy. */
@RestController
public class MlModelVersionsController implements MlModelVersionsApi {
    private final ModelService models;
    private final Idempotency idempotency;
    private final Responses responses;
    private final CrudEngine crud;
    private final Store store;
    private final JsonMapper json;

    public MlModelVersionsController(ModelService models, Idempotency idempotency, Responses responses, CrudEngine crud,
                                     Store store, JsonMapper json) {
        this.models = models;
        this.idempotency = idempotency;
        this.responses = responses;
        this.crud = crud;
        this.store = store;
        this.json = json;
    }

    @Override
    public ResponseEntity<MlModelVersion> mlModelVersionsCreate(String idempotencyKey, MlModelVersionCreate body) {
        TenantContext.require();
        ObjectNode values = crud.toColumns("ml_model_version", body);
        models.prepareVersion(values, null);
        values.put("status", "TRAINING");
        return idempotency.execute(idempotencyKey, body, MlModelVersion.class,
                () -> responses.created(crud.insert("ml_model_version", values), MlModelVersion.class));
    }

    @Override
    public ResponseEntity<MlModelVersion> mlModelVersionsUpdate(UUID id, String ifMatch, MlModelVersionUpdate body) {
        TenantContext.require();
        int version = Fields.ifMatch(ifMatch);
        JsonNode existing = store.require("ml_model_version", id);
        ObjectNode values = crud.toColumns("ml_model_version", body);
        if (values.isEmpty()) {
            return responses.ok(existing.toString(), MlModelVersion.class);
        }
        models.prepareVersion(values, existing);
        String row = crud.updateRow("ml_model_version", Map.of("id", id), values, version).orElseThrow(() ->
                ApiException.conflict("version_conflict", "If-Match " + version + " does not match the current version; reload and retry"));
        return responses.ok(row, MlModelVersion.class);
    }

    @Override
    public ResponseEntity<MlModelVersion> mlModelVersionsSubmitForApproval(UUID id, String idempotencyKey,
                                                                          MlModelVersionsSubmitForApprovalRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("submit", id, req), MlModelVersion.class,
                () -> responses.ok(models.submit(id, Fields.text(req, "comment")), MlModelVersion.class));
    }

    @Override
    public ResponseEntity<MlModelVersion> mlModelVersionsApprove(UUID id, String idempotencyKey, MlModelVersionsApproveRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("approve", id, req), MlModelVersion.class,
                () -> responses.ok(models.approve(id, Fields.text(req, "comment")), MlModelVersion.class));
    }

    @Override
    public ResponseEntity<MlModelVersion> mlModelVersionsReject(UUID id, String idempotencyKey, MlModelVersionsRejectRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String reason = Fields.requireText(req, "reason");
        return idempotency.execute(idempotencyKey, responses.action("reject", id, req), MlModelVersion.class,
                () -> responses.ok(models.reject(id, reason), MlModelVersion.class));
    }

    @Override
    public ResponseEntity<ModelDeployment> mlModelVersionsDeploy(UUID id, String idempotencyKey, MlModelVersionsDeployRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("deploy", id, req), ModelDeployment.class,
                () -> responses.created(models.deploy(id, req), ModelDeployment.class));
    }
}
