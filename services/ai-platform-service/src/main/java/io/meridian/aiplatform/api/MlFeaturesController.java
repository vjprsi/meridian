package io.meridian.aiplatform.api;

import io.meridian.aiplatform.api.generated.MlFeaturesApi;
import io.meridian.aiplatform.api.generated.model.MlFeatureDefinition;
import io.meridian.aiplatform.api.generated.model.MlFeatureDefinitionCreate;
import io.meridian.aiplatform.api.generated.model.MlFeatureDefinitionUpdate;
import io.meridian.aiplatform.application.Fields;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Feature catalogue: names, entities, types and freshness are validated. */
@RestController
public class MlFeaturesController implements MlFeaturesApi {
    static final Set<String> ENTITIES = Set.of("CUSTOMER", "ACCOUNT", "CARD", "DEVICE", "MERCHANT");
    static final Set<String> DTYPES = Set.of("INT", "FLOAT", "STRING", "BOOL", "LIST");

    private final CrudEngine crud;
    private final JsonMapper json;

    public MlFeaturesController(CrudEngine crud, JsonMapper json) {
        this.crud = crud;
        this.json = json;
    }

    @Override
    public ResponseEntity<MlFeatureDefinition> mlFeaturesCreate(String idempotencyKey, MlFeatureDefinitionCreate body) {
        TenantContext.require();
        check(json.valueToTree(body));
        return crud.create("ml_feature_definition", MlFeatureDefinition.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<MlFeatureDefinition> mlFeaturesUpdate(UUID id, String ifMatch, MlFeatureDefinitionUpdate body) {
        TenantContext.require();
        check(json.valueToTree(body));
        return crud.update("ml_feature_definition", MlFeatureDefinition.class, body, ifMatch, Crud.params().with("id", id));
    }

    static void check(JsonNode f) {
        String name = Fields.text(f, "name");
        if (name != null && !name.matches("[a-z][a-z0-9_]{1,127}")) {
            throw Fields.invalid("name", "invalid_name", "feature names are snake_case, e.g. customer_txn_count_24h");
        }
        String entity = Fields.text(f, "entity");
        if (entity != null && !ENTITIES.contains(entity)) {
            throw Fields.invalid("entity", "invalid_entity", "entity must be one of " + ENTITIES);
        }
        String dtype = Fields.text(f, "dtype");
        if (dtype != null && !DTYPES.contains(dtype)) {
            throw Fields.invalid("dtype", "invalid_dtype", "dtype must be one of " + DTYPES);
        }
        Integer sla = Fields.integer(f, "freshness_sla_seconds");
        if (sla != null && sla < 0) {
            throw Fields.invalid("freshness_sla_seconds", "out_of_range", "freshness_sla_seconds cannot be negative");
        }
    }
}
