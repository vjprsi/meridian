package io.meridian.commission.api;

import io.meridian.commission.api.generated.CommissionRulesApi;
import io.meridian.commission.api.generated.model.CommissionRule;
import io.meridian.commission.api.generated.model.CommissionRuleCreate;
import io.meridian.commission.api.generated.model.CommissionRuleUpdate;
import io.meridian.commission.application.PlanService;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Commission rules: editable only while their plan is a DRAFT; definitions validated by the engine. */
@RestController
public class CommissionRulesController implements CommissionRulesApi {
    private final PlanService plans;
    private final Actions actions;
    private final CrudEngine crud;

    public CommissionRulesController(PlanService plans, Actions actions, CrudEngine crud) {
        this.plans = plans;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<CommissionRule> commissionRulesCreate(String idempotencyKey, CommissionRuleCreate body) {
        TenantContext.require();
        plans.checkRule(actions.tree(body));
        return crud.create("commission_rule", CommissionRule.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<CommissionRule> commissionRulesUpdate(UUID id, String ifMatch, CommissionRuleUpdate body) {
        TenantContext.require();
        ObjectNode current = plans.ruleOfDraft(id);
        ObjectNode patch = (ObjectNode) actions.tree(body);
        if (patch.hasNonNull("commission_plan_id")
                && !patch.get("commission_plan_id").asString().equals(current.get("commission_plan_id").asString())) {
            throw ApiException.unprocessable("field_not_updatable", "A rule cannot move to another plan");
        }
        ObjectNode merged = current.deepCopy();
        patch.properties().forEach(e -> {
            if (!e.getValue().isNull()) {
                merged.set(e.getKey(), e.getValue());
            }
        });
        plans.checkRule(merged);
        return crud.update("commission_rule", CommissionRule.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<Void> commissionRulesDelete(UUID id) {
        TenantContext.require();
        plans.ruleOfDraft(id);
        return crud.delete("commission_rule", Crud.params().with("id", id));
    }
}
