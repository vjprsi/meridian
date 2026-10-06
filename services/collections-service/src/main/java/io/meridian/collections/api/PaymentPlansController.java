package io.meridian.collections.api;

import io.meridian.collections.api.generated.PaymentPlansApi;
import io.meridian.collections.api.generated.model.PaymentPlan;
import io.meridian.collections.api.generated.model.PaymentPlanUpdate;
import io.meridian.collections.api.generated.model.PaymentPlansCancelRequest;
import io.meridian.collections.application.ArrangementService;
import io.meridian.collections.infrastructure.Rows;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Payment plans: activation (four-eyes outside policy) and cancellation; the schedule is fixed once proposed. */
@RestController
public class PaymentPlansController implements PaymentPlansApi {
    private static final Set<String> PROTECTED = Set.of("collection_case_id", "total_amount", "currency",
            "installment_count", "frequency", "start_date");

    private final ArrangementService arrangements;
    private final Actions actions;
    private final CrudEngine crud;
    private final Rows rows;

    public PaymentPlansController(ArrangementService arrangements, Actions actions, CrudEngine crud, Rows rows) {
        this.arrangements = arrangements;
        this.actions = actions;
        this.crud = crud;
        this.rows = rows;
    }

    @Override
    public ResponseEntity<PaymentPlan> paymentPlansUpdate(UUID id, String ifMatch, PaymentPlanUpdate body) {
        TenantContext.require();
        ObjectNodeGuard.notTerminal(rows, "payment_plan", id, Set.of("COMPLETED", "BROKEN", "CANCELLED"), "plan_closed");
        return actions.guardedUpdate(crud, "payment_plan", PaymentPlan.class, id, ifMatch, body, PROTECTED,
                "cancel the plan and propose a new one");
    }

    @Override
    public ResponseEntity<PaymentPlan> paymentPlansActivate(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "activate", null, PaymentPlan.class, () -> arrangements.activatePlan(id));
    }

    @Override
    public ResponseEntity<PaymentPlan> paymentPlansCancel(UUID id, String idempotencyKey, PaymentPlansCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, PaymentPlan.class,
                () -> arrangements.cancelPlan(id, Http.text(actions.tree(body), "reason")));
    }
}
