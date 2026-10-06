package io.meridian.collections.api;

import io.meridian.collections.api.generated.CollectionStrategiesApi;
import io.meridian.collections.api.generated.model.CollectionStrategy;
import io.meridian.collections.api.generated.model.CollectionStrategyCreate;
import io.meridian.collections.api.generated.model.CollectionStrategyUpdate;
import io.meridian.collections.application.StrategyAdmin;
import io.meridian.collections.infrastructure.Rows;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Collection strategies: generic CRUD with DPD range, segment rule and quiet-hours validation. */
@RestController
public class CollectionStrategiesController implements CollectionStrategiesApi {
    private final StrategyAdmin admin;
    private final CrudEngine crud;
    private final Actions actions;
    private final Rows rows;

    public CollectionStrategiesController(StrategyAdmin admin, CrudEngine crud, Actions actions, Rows rows) {
        this.admin = admin;
        this.crud = crud;
        this.actions = actions;
        this.rows = rows;
    }

    @Override
    public ResponseEntity<CollectionStrategy> collectionStrategiesCreate(String idempotencyKey, CollectionStrategyCreate body) {
        TenantContext.require();
        admin.validateStrategy(actions.tree(body));
        return crud.create("collection_strategy", CollectionStrategy.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<CollectionStrategy> collectionStrategiesUpdate(UUID id, String ifMatch, CollectionStrategyUpdate body) {
        TenantContext.require();
        ObjectNode merged = rows.get("collection_strategy", id);
        merged.setAll(rows.columns("collection_strategy", body));
        admin.validateStrategy(merged);
        return crud.update("collection_strategy", CollectionStrategy.class, body, ifMatch, Crud.params().with("id", id));
    }
}
