package io.meridian.agentnetwork.api;

import io.meridian.agentnetwork.api.generated.StockOperationsApi;
import io.meridian.agentnetwork.api.generated.model.StockOperation;
import io.meridian.agentnetwork.api.generated.model.StockOperationCreate;
import io.meridian.agentnetwork.api.generated.model.StockOperationsApproveRequest;
import io.meridian.agentnetwork.api.generated.model.StockOperationsCancelRequest;
import io.meridian.agentnetwork.api.generated.model.StockOperationsRejectRequest;
import io.meridian.agentnetwork.application.StockOperationService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** WE-FLOAT-1: operator stock creation and reimbursement with maker-checker approval. */
@RestController
public class StockOperationsController implements StockOperationsApi {
    private final StockOperationService stock;
    private final Actions actions;

    public StockOperationsController(StockOperationService stock, Actions actions) {
        this.stock = stock;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<StockOperation> stockOperationsCreate(String idempotencyKey, StockOperationCreate body) {
        return actions.create(idempotencyKey, body, StockOperation.class, () -> stock.create(actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<StockOperation> stockOperationsApprove(UUID id, String idempotencyKey,
                                                                 StockOperationsApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, StockOperation.class,
                () -> stock.approve(id, Http.text(actions.tree(body), "comment")));
    }

    @Override
    public ResponseEntity<StockOperation> stockOperationsReject(UUID id, String idempotencyKey,
                                                                StockOperationsRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, StockOperation.class,
                () -> stock.close(id, "REJECTED", Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<StockOperation> stockOperationsCancel(UUID id, String idempotencyKey,
                                                                StockOperationsCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, StockOperation.class,
                () -> stock.close(id, "CANCELLED", Http.text(actions.tree(body), "reason")));
    }
}
