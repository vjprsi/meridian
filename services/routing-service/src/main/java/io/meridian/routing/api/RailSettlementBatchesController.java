package io.meridian.routing.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.routing.api.generated.RailSettlementBatchesApi;
import io.meridian.routing.api.generated.model.RailMessagePage;
import io.meridian.routing.api.generated.model.RailSettlementBatch;
import io.meridian.routing.application.SettlementService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Settlement batches: close (settlement file), settle (net position posting), messages. */
@RestController
public class RailSettlementBatchesController implements RailSettlementBatchesApi {
    private final SettlementService settlement;
    private final Actions actions;
    private final CrudEngine crud;

    public RailSettlementBatchesController(SettlementService settlement, Actions actions, CrudEngine crud) {
        this.settlement = settlement;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<RailSettlementBatch> railSettlementBatchesClose(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "close", null, RailSettlementBatch.class, () -> settlement.close(id));
    }

    @Override
    public ResponseEntity<RailSettlementBatch> railSettlementBatchesSettle(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "settle", null, RailSettlementBatch.class, () -> settlement.settle(id));
    }

    @Override
    public ResponseEntity<RailMessagePage> railSettlementBatchesMessages(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        crud.findOne("rail_settlement_batch", Map.of("id", id))
                .orElseThrow(() -> ApiException.notFound("rail settlement batch", id));
        return crud.list("rail_message", RailMessagePage.class, Crud.params().with("rail_settlement_batch_id", id)
                .with("limit", limit).with("cursor", cursor));
    }
}
