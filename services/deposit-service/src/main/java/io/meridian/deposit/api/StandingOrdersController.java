package io.meridian.deposit.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.api.generated.StandingOrdersApi;
import io.meridian.deposit.api.generated.model.ScheduledPaymentExecutionPage;
import io.meridian.deposit.api.generated.model.StandingOrder;
import io.meridian.deposit.api.generated.model.StandingOrderCreate;
import io.meridian.deposit.api.generated.model.StandingOrderUpdate;
import io.meridian.deposit.api.generated.model.StandingOrdersCancelRequest;
import io.meridian.deposit.api.generated.model.StandingOrdersPauseRequest;
import io.meridian.deposit.application.StandingOrderService;
import io.meridian.deposit.infrastructure.Rows;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Standing orders: scheduling, pause/resume/cancel/skip-next and execution history. */
@RestController
public class StandingOrdersController implements StandingOrdersApi {
    private final StandingOrderService orders;
    private final Actions actions;
    private final CrudEngine crud;
    private final Rows rows;

    public StandingOrdersController(StandingOrderService orders, Actions actions, CrudEngine crud, Rows rows) {
        this.orders = orders;
        this.actions = actions;
        this.crud = crud;
        this.rows = rows;
    }

    @Override
    public ResponseEntity<StandingOrder> standingOrdersCreate(String idempotencyKey, StandingOrderCreate body) {
        return actions.create(idempotencyKey, body, StandingOrder.class, () -> orders.create(body));
    }

    @Override
    public ResponseEntity<StandingOrder> standingOrdersUpdate(UUID id, String ifMatch, StandingOrderUpdate body) {
        return actions.guardedUpdate(crud, "standing_order", StandingOrder.class, id, ifMatch, body,
                Set.of("deposit_account_id", "currency", "frequency", "start_date", "day_of_period", "holiday_rule",
                        "payee_id", "beneficiary_account_id"),
                "cancel the order and create a new one to change its schedule or beneficiary");
    }

    @Override
    public ResponseEntity<StandingOrder> standingOrdersPause(UUID id, String idempotencyKey, StandingOrdersPauseRequest body) {
        return actions.action(idempotencyKey, id, "pause", body, StandingOrder.class,
                () -> orders.pause(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<StandingOrder> standingOrdersResume(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "resume", null, StandingOrder.class, () -> orders.resume(id));
    }

    @Override
    public ResponseEntity<StandingOrder> standingOrdersCancel(UUID id, String idempotencyKey, StandingOrdersCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, StandingOrder.class,
                () -> orders.cancel(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<StandingOrder> standingOrdersSkipNext(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "skip-next", null, StandingOrder.class, () -> orders.skipNext(id));
    }

    @Override
    public ResponseEntity<ScheduledPaymentExecutionPage> standingOrdersExecutions(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        rows.get("standing_order", id);
        return crud.list("scheduled_payment_execution", ScheduledPaymentExecutionPage.class,
                Crud.params().with("standing_order_id", id).with("limit", limit).with("cursor", cursor));
    }
}
