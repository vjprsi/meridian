package io.meridian.aiplatform.api;

import io.meridian.aiplatform.api.generated.MlModelsApi;
import io.meridian.aiplatform.api.generated.model.MlModel;
import io.meridian.aiplatform.api.generated.model.MlModelUpdate;
import io.meridian.aiplatform.api.generated.model.MlModelVersionPage;
import io.meridian.aiplatform.api.generated.model.ModelDeploymentPage;
import io.meridian.aiplatform.infrastructure.Store;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Model registry: a model with active deployments cannot be retired; versions and deployments per model. */
@RestController
public class MlModelsController implements MlModelsApi {
    private final CrudEngine crud;
    private final Store store;
    private final JsonMapper json;

    public MlModelsController(CrudEngine crud, Store store, JsonMapper json) {
        this.crud = crud;
        this.store = store;
        this.json = json;
    }

    @Override
    public ResponseEntity<MlModel> mlModelsUpdate(UUID id, String ifMatch, MlModelUpdate body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        if ("RETIRED".equals(req.path("status").asString(""))
                && store.count("select count(*) from model_deployment where ml_model_id = ? and status = 'ACTIVE'", id) > 0) {
            throw ApiException.conflict("model_in_service", "Retire or roll back the model's active deployments first");
        }
        return crud.update("ml_model", MlModel.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<MlModelVersionPage> mlModelsVersions(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        store.require("ml_model", id);
        return crud.list("ml_model_version", MlModelVersionPage.class,
                Crud.params().with("ml_model_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<ModelDeploymentPage> mlModelsDeployments(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        store.require("ml_model", id);
        return crud.list("model_deployment", ModelDeploymentPage.class,
                Crud.params().with("ml_model_id", id).with("limit", limit).with("cursor", cursor));
    }
}
