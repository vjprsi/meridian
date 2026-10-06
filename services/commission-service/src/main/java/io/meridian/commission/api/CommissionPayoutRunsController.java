package io.meridian.commission.api;

import io.meridian.commission.api.generated.CommissionPayoutRunsApi;
import io.meridian.commission.api.generated.model.CommissionPayoutLinePage;
import io.meridian.commission.api.generated.model.CommissionPayoutRun;
import io.meridian.commission.api.generated.model.CommissionPayoutRunCreate;
import io.meridian.commission.api.generated.model.CommissionPayoutRunsApproveRequest;
import io.meridian.commission.api.generated.model.CommissionPayoutRunsCancelRequest;
import io.meridian.commission.application.PayoutService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Commission payout runs: preview, checker approval, execution to commission wallets, retries. */
@RestController
public class CommissionPayoutRunsController implements CommissionPayoutRunsApi {
    private final PayoutService payouts;
    private final Actions actions;
    private final CrudEngine crud;

    public CommissionPayoutRunsController(PayoutService payouts, Actions actions, CrudEngine crud) {
        this.payouts = payouts;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<CommissionPayoutRun> commissionPayoutRunsCreate(String idempotencyKey, CommissionPayoutRunCreate body) {
        return actions.create(idempotencyKey, body, CommissionPayoutRun.class, () -> payouts.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<CommissionPayoutRun> commissionPayoutRunsApprove(UUID id, String idempotencyKey,
                                                                          CommissionPayoutRunsApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, CommissionPayoutRun.class, () -> payouts.approve(id));
    }

    @Override
    public ResponseEntity<CommissionPayoutRun> commissionPayoutRunsCancel(UUID id, String idempotencyKey,
                                                                         CommissionPayoutRunsCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, CommissionPayoutRun.class,
                () -> payouts.cancel(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<CommissionPayoutRun> commissionPayoutRunsRetryFailed(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "retry-failed", null, CommissionPayoutRun.class, () -> payouts.retryFailed(id));
    }

    @Override
    public ResponseEntity<CommissionPayoutLinePage> commissionPayoutRunsLines(UUID id, Integer limit, String cursor) {
        crud.get("commission_payout_run", CommissionPayoutRun.class, Crud.params().with("id", id));
        return crud.list("commission_payout_line", CommissionPayoutLinePage.class,
                Crud.params().with("commission_payout_run_id", id).with("limit", limit).with("cursor", cursor));
    }
}
