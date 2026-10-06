package io.meridian.collections.api;

import io.meridian.collections.api.generated.CollectionQueuesApi;
import io.meridian.collections.api.generated.model.CollectionCasePage;
import io.meridian.collections.api.generated.model.CollectionQueue;
import io.meridian.collections.api.generated.model.CollectionQueueCreate;
import io.meridian.collections.api.generated.model.CollectionQueueUpdate;
import io.meridian.collections.application.QueueService;
import io.meridian.collections.application.StrategyAdmin;
import io.meridian.collections.infrastructure.Rows;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Collector work queues: prioritised case list and rebalancing. */
@RestController
public class CollectionQueuesController implements CollectionQueuesApi {
    private final QueueService queues;
    private final StrategyAdmin admin;
    private final CrudEngine crud;
    private final Actions actions;
    private final Rows rows;

    public CollectionQueuesController(QueueService queues, StrategyAdmin admin, CrudEngine crud, Actions actions, Rows rows) {
        this.queues = queues;
        this.admin = admin;
        this.crud = crud;
        this.actions = actions;
        this.rows = rows;
    }

    @Override
    public ResponseEntity<CollectionQueue> collectionQueuesCreate(String idempotencyKey, CollectionQueueCreate body) {
        TenantContext.require();
        admin.validateQueue(actions.tree(body));
        return crud.create("collection_queue", CollectionQueue.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<CollectionQueue> collectionQueuesUpdate(UUID id, String ifMatch, CollectionQueueUpdate body) {
        TenantContext.require();
        ObjectNode merged = rows.get("collection_queue", id);
        merged.setAll(rows.columns("collection_queue", body));
        admin.validateQueue(merged);
        return crud.update("collection_queue", CollectionQueue.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<CollectionCasePage> collectionQueuesCases(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(actions.model(queues.cases(id, limit, cursor), CollectionCasePage.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> collectionQueuesRebalance(UUID id, String idempotencyKey) {
        return actions.objectAction(idempotencyKey, "rebalance", Map.of("id", id.toString()), () -> queues.rebalance(id));
    }
}
