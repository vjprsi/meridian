package io.meridian.commission.api;

import io.meridian.commission.api.generated.CommissionPlansApi;
import io.meridian.commission.api.generated.model.CommissionPlan;
import io.meridian.commission.api.generated.model.CommissionPlanCreate;
import io.meridian.commission.api.generated.model.CommissionPlanUpdate;
import io.meridian.commission.api.generated.model.CommissionPlansApproveRequest;
import io.meridian.commission.api.generated.model.CommissionPlansRejectRequest;
import io.meridian.commission.api.generated.model.CommissionPlansRetireRequest;
import io.meridian.commission.api.generated.model.CommissionPlansSimulateRequest;
import io.meridian.commission.api.generated.model.CommissionRulePage;
import io.meridian.commission.application.PlanService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Commission plans: versioned, maker-checker activated, simulated. */
@RestController
public class CommissionPlansController implements CommissionPlansApi {
    private final PlanService plans;
    private final Actions actions;
    private final CrudEngine crud;

    public CommissionPlansController(PlanService plans, Actions actions, CrudEngine crud) {
        this.plans = plans;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<CommissionPlan> commissionPlansCreate(String idempotencyKey, CommissionPlanCreate body) {
        return actions.create(idempotencyKey, body, CommissionPlan.class, () -> plans.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<CommissionPlan> commissionPlansUpdate(UUID id, String ifMatch, CommissionPlanUpdate body) {
        TenantContext.require();
        plans.checkEditable(id, actions.tree(body));
        return crud.update("commission_plan", CommissionPlan.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<CommissionRulePage> commissionPlansRules(UUID id, Integer limit, String cursor) {
        crud.get("commission_plan", CommissionPlan.class, Crud.params().with("id", id));
        return crud.list("commission_rule", CommissionRulePage.class,
                Crud.params().with("commission_plan_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<CommissionPlan> commissionPlansSubmit(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "submit", null, CommissionPlan.class, () -> plans.submit(id));
    }

    @Override
    public ResponseEntity<CommissionPlan> commissionPlansApprove(UUID id, String idempotencyKey, CommissionPlansApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, CommissionPlan.class, () -> plans.approve(id));
    }

    @Override
    public ResponseEntity<CommissionPlan> commissionPlansReject(UUID id, String idempotencyKey, CommissionPlansRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, CommissionPlan.class,
                () -> plans.reject(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<CommissionPlan> commissionPlansRetire(UUID id, String idempotencyKey, CommissionPlansRetireRequest body) {
        String date = Http.text(actions.tree(body), "effective_date");
        return actions.action(idempotencyKey, id, "retire", body, CommissionPlan.class,
                () -> plans.retire(id, date == null ? null : LocalDate.parse(date)));
    }

    @Override
    public ResponseEntity<CommissionPlan> commissionPlansClone(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "clone", null, CommissionPlan.class, () -> plans.clone(id));
    }

    @Override
    public ResponseEntity<Map<String, Object>> commissionPlansSimulate(UUID id, String idempotencyKey,
                                                                       CommissionPlansSimulateRequest body) {
        return actions.objectAction(idempotencyKey, "simulate:" + id, body, () -> plans.simulate(id, actions.tree(body)));
    }
}
