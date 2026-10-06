package io.meridian.archive.api;

import io.meridian.archive.api.generated.RetentionPoliciesApi;
import io.meridian.archive.api.generated.model.RetentionPolicy;
import io.meridian.archive.api.generated.model.RetentionPolicyCreate;
import io.meridian.archive.api.generated.model.RetentionPolicyUpdate;
import io.meridian.archive.application.CatalogService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Retention policies per data class and jurisdiction, with a preview of what they would archive / purge today. */
@RestController
public class RetentionPoliciesController implements RetentionPoliciesApi {
    private static final String TABLE = "retention_policy";

    private final CatalogService catalog;
    private final CrudEngine crud;
    private final Updates updates;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public RetentionPoliciesController(CatalogService catalog, CrudEngine crud, Updates updates, Idempotency idempotency,
                                       JsonMapper json) {
        this.catalog = catalog;
        this.crud = crud;
        this.updates = updates;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<RetentionPolicy> retentionPoliciesCreate(String idempotencyKey, RetentionPolicyCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, RetentionPolicy.class, () -> {
            ObjectNode values = crud.toColumns(TABLE, body);
            catalog.validatePolicy(values, null);
            return ResponseEntity.status(HttpStatus.CREATED).body(crud.read(crud.insert(TABLE, values), RetentionPolicy.class));
        });
    }

    @Override
    public ResponseEntity<RetentionPolicy> retentionPoliciesUpdate(UUID id, String ifMatch, RetentionPolicyUpdate body) {
        TenantContext.require();
        return updates.apply(TABLE, id, ifMatch, crud.toColumns(TABLE, body), RetentionPolicy.class,
                (merged, changes) -> catalog.validatePolicy(merged, id));
    }

    @Override
    public ResponseEntity<Map<String, Object>> retentionPoliciesPreview(UUID id, String idempotencyKey) {
        TenantContext.require();
        return Http.object(json, idempotency.execute(idempotencyKey, json.createObjectNode().put("preview", id.toString()),
                JsonNode.class, () -> ResponseEntity.ok(catalog.preview(id))));
    }
}
