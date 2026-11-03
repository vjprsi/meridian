package io.meridian.agentnetwork.api;

import io.meridian.agentnetwork.api.generated.UnclaimedSettlementsApi;
import io.meridian.agentnetwork.api.generated.model.UnclaimedSettlementLinePage;
import io.meridian.agentnetwork.api.generated.model.UnclaimedSettlementRun;
import io.meridian.agentnetwork.api.generated.model.UnclaimedSettlementsApproveRequest;
import io.meridian.agentnetwork.api.generated.model.UnclaimedSettlementsPlanRequest;
import io.meridian.agentnetwork.api.generated.model.UnclaimedSettlementsRejectRequest;
import io.meridian.agentnetwork.application.UnclaimedSettlementService;
import io.meridian.common.error.ApiException;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** WE-FLOAT-1: unclaimed-money settlement runs: plan (maker), checker approval, GL journals, lines. */
@RestController
public class UnclaimedSettlementsController implements UnclaimedSettlementsApi {
    private final UnclaimedSettlementService settlements;
    private final Actions actions;
    private final CrudEngine crud;

    public UnclaimedSettlementsController(UnclaimedSettlementService settlements, Actions actions, CrudEngine crud) {
        this.settlements = settlements;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<UnclaimedSettlementRun> unclaimedSettlementsPlan(String idempotencyKey,
                                                                           UnclaimedSettlementsPlanRequest body) {
        return actions.action(idempotencyKey, null, "plan", body, UnclaimedSettlementRun.class, () -> {
            ObjectNode run = settlements.plan(actions.tree(body));
            if (run == null) {
                throw ApiException.unprocessable("nothing_aged", "No suspense or unclaimed funds have aged for settlement");
            }
            return run;
        });
    }

    @Override
    public ResponseEntity<UnclaimedSettlementRun> unclaimedSettlementsApprove(UUID id, String idempotencyKey,
                                                                              UnclaimedSettlementsApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, UnclaimedSettlementRun.class, () -> settlements.approve(id));
    }

    @Override
    public ResponseEntity<UnclaimedSettlementRun> unclaimedSettlementsReject(UUID id, String idempotencyKey,
                                                                             UnclaimedSettlementsRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, UnclaimedSettlementRun.class,
                () -> settlements.reject(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<UnclaimedSettlementLinePage> unclaimedSettlementsLines(UUID id, Integer limit, String cursor) {
        crud.get("unclaimed_settlement_run", UnclaimedSettlementRun.class, Crud.params().with("id", id));
        return crud.list("unclaimed_settlement_line", UnclaimedSettlementLinePage.class,
                Crud.params().with("unclaimed_settlement_run_id", id).with("limit", limit).with("cursor", cursor));
    }
}
