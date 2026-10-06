package io.meridian.archive.api;

import io.meridian.archive.api.generated.DataClassificationsApi;
import io.meridian.archive.api.generated.model.DataClassification;
import io.meridian.archive.api.generated.model.DataClassificationCreate;
import io.meridian.archive.api.generated.model.DataClassificationUpdate;
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

/** Data classification catalogue with discovery from the spec catalogue ({@code /scan?apply=true} stores suggestions). */
@RestController
public class DataClassificationsController implements DataClassificationsApi {
    private static final String TABLE = "data_classification";

    private final CatalogService catalog;
    private final CrudEngine crud;
    private final Updates updates;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public DataClassificationsController(CatalogService catalog, CrudEngine crud, Updates updates, Idempotency idempotency,
                                         JsonMapper json) {
        this.catalog = catalog;
        this.crud = crud;
        this.updates = updates;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<DataClassification> dataClassificationsCreate(String idempotencyKey, DataClassificationCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, DataClassification.class, () -> {
            ObjectNode values = crud.toColumns(TABLE, body);
            catalog.validateClassification(values);
            return ResponseEntity.status(HttpStatus.CREATED).body(crud.read(crud.insert(TABLE, values), DataClassification.class));
        });
    }

    @Override
    public ResponseEntity<DataClassification> dataClassificationsUpdate(UUID id, String ifMatch, DataClassificationUpdate body) {
        TenantContext.require();
        return updates.apply(TABLE, id, ifMatch, crud.toColumns(TABLE, body), DataClassification.class,
                (merged, changes) -> catalog.validateClassification(merged));
    }

    @Override
    public ResponseEntity<Map<String, Object>> dataClassificationsScan(String idempotencyKey, Boolean applyParam) {
        TenantContext.require();
        boolean apply = Boolean.TRUE.equals(applyParam);
        return Http.object(json, idempotency.execute(idempotencyKey, json.createObjectNode().put("scan", apply), JsonNode.class,
                () -> ResponseEntity.ok(catalog.scan(apply))));
    }
}
