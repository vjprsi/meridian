package io.meridian.collections.api;

import io.meridian.collections.api.generated.CollectionStrategyStepsApi;
import io.meridian.collections.api.generated.model.CollectionStrategyStep;
import io.meridian.collections.api.generated.model.CollectionStrategyStepCreate;
import io.meridian.collections.api.generated.model.CollectionStrategyStepUpdate;
import io.meridian.collections.application.StrategyAdmin;
import io.meridian.collections.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Ordered strategy steps: unique step numbers, trigger DPD inside the strategy's range, existing queue. */
@RestController
public class CollectionStrategyStepsController implements CollectionStrategyStepsApi {
    private static final String TABLE = "collection_strategy_step";

    private final StrategyAdmin admin;
    private final CrudEngine crud;
    private final Actions actions;
    private final Rows rows;

    public CollectionStrategyStepsController(StrategyAdmin admin, CrudEngine crud, Actions actions, Rows rows) {
        this.admin = admin;
        this.crud = crud;
        this.actions = actions;
        this.rows = rows;
    }

    @Override
    public ResponseEntity<CollectionStrategyStep> collectionStrategyStepsCreate(UUID collectionStrategyId,
                                                                              String idempotencyKey,
                                                                              CollectionStrategyStepCreate body) {
        TenantContext.require();
        String parent = Http.text(actions.tree(body), "collection_strategy_id");
        if (parent != null && !parent.equals(collectionStrategyId.toString())) {
            throw ApiException.unprocessable("parent_mismatch",
                    "collection_strategy_id in the body must match the strategy in the path");
        }
        admin.validateStep(collectionStrategyId, null, actions.tree(body));
        return crud.create(TABLE, CollectionStrategyStep.class, body, idempotencyKey,
                Crud.params().with("collection_strategy_id", collectionStrategyId));
    }

    @Override
    public ResponseEntity<CollectionStrategyStep> collectionStrategyStepsUpdate(UUID collectionStrategyId, UUID id,
                                                                              String ifMatch,
                                                                              CollectionStrategyStepUpdate body) {
        TenantContext.require();
        ObjectNode current = crud.findOne(TABLE, Map.of("collection_strategy_id", collectionStrategyId, "id", id))
                .map(rows::parse).orElseThrow(() -> ApiException.notFound("collection strategy step", id));
        ObjectNode change = rows.columns(TABLE, body);
        // re-check only what changes (the step's own number is not a duplicate of itself)
        ObjectNode check = rows.object();
        for (String f : new String[] {"step_no", "trigger_dpd", "queue_id"}) {
            if (change.has(f)) {
                check.set(f, change.get(f));
            }
        }
        if (!check.isEmpty() || current.isEmpty()) {
            admin.validateStep(collectionStrategyId, id, check);
        }
        return crud.update(TABLE, CollectionStrategyStep.class, body, ifMatch,
                Crud.params().with("collection_strategy_id", collectionStrategyId).with("id", id));
    }
}
