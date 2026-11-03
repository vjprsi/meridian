package io.meridian.agentnetwork.api;

import io.meridian.agentnetwork.api.generated.StockSweepRunsApi;
import io.meridian.agentnetwork.api.generated.model.StockSweepLinePage;
import io.meridian.agentnetwork.api.generated.model.StockSweepRun;
import io.meridian.agentnetwork.api.generated.model.StockSweepRunsApproveRequest;
import io.meridian.agentnetwork.api.generated.model.StockSweepRunsPlanRequest;
import io.meridian.agentnetwork.api.generated.model.StockSweepRunsRejectRequest;
import io.meridian.agentnetwork.application.StockSweepService;
import io.meridian.common.error.ApiException;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** WE-FLOAT-1: auto-deallocation sweep runs: plan (maker), checker approval, execution, lines. */
@RestController
public class StockSweepRunsController implements StockSweepRunsApi {
    private final StockSweepService sweeps;
    private final Actions actions;
    private final CrudEngine crud;

    public StockSweepRunsController(StockSweepService sweeps, Actions actions, CrudEngine crud) {
        this.sweeps = sweeps;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<StockSweepRun> stockSweepRunsPlan(String idempotencyKey, StockSweepRunsPlanRequest body) {
        return actions.action(idempotencyKey, null, "plan", body, StockSweepRun.class, () -> {
            ObjectNode run = sweeps.plan(actions.tree(body), false);
            if (run == null) {
                throw ApiException.unprocessable("no_candidates", "No float wallet is above its minimum residual");
            }
            return run;
        });
    }

    @Override
    public ResponseEntity<StockSweepRun> stockSweepRunsApprove(UUID id, String idempotencyKey,
                                                               StockSweepRunsApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, StockSweepRun.class, () -> sweeps.approve(id));
    }

    @Override
    public ResponseEntity<StockSweepRun> stockSweepRunsReject(UUID id, String idempotencyKey,
                                                              StockSweepRunsRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, StockSweepRun.class,
                () -> sweeps.reject(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<StockSweepLinePage> stockSweepRunsLines(UUID id, Integer limit, String cursor) {
        crud.get("stock_sweep_run", StockSweepRun.class, Crud.params().with("id", id));
        return crud.list("stock_sweep_line", StockSweepLinePage.class,
                Crud.params().with("stock_sweep_run_id", id).with("limit", limit).with("cursor", cursor));
    }
}
