package io.meridian.deposit.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.api.generated.SweepRulesApi;
import io.meridian.deposit.api.generated.model.SweepExecution;
import io.meridian.deposit.api.generated.model.SweepExecutionPage;
import io.meridian.deposit.api.generated.model.SweepRule;
import io.meridian.deposit.api.generated.model.SweepRuleCreate;
import io.meridian.deposit.api.generated.model.SweepRuleUpdate;
import io.meridian.deposit.application.SweepService;
import io.meridian.deposit.infrastructure.Rows;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Sweep rules: validated creation, immediate evaluation and execution history. */
@RestController
public class SweepRulesController implements SweepRulesApi {
    private final SweepService sweeps;
    private final Actions actions;
    private final CrudEngine crud;
    private final Rows rows;

    public SweepRulesController(SweepService sweeps, Actions actions, CrudEngine crud, Rows rows) {
        this.sweeps = sweeps;
        this.actions = actions;
        this.crud = crud;
        this.rows = rows;
    }

    @Override
    public ResponseEntity<SweepRule> sweepRulesCreate(String idempotencyKey, SweepRuleCreate body) {
        return actions.create(idempotencyKey, body, SweepRule.class, () -> sweeps.create(body));
    }

    @Override
    public ResponseEntity<SweepRule> sweepRulesUpdate(UUID id, String ifMatch, SweepRuleUpdate body) {
        return actions.guardedUpdate(crud, "sweep_rule", SweepRule.class, id, ifMatch, body,
                Set.of("source_account_id", "target_account_id", "sweep_type"), "create a new rule");
    }

    @Override
    public ResponseEntity<SweepExecution> sweepRulesRunNow(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "run-now", null, SweepExecution.class, () -> sweeps.run(id, idempotencyKey));
    }

    @Override
    public ResponseEntity<SweepExecutionPage> sweepRulesExecutions(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        rows.get("sweep_rule", id);
        return crud.list("sweep_execution", SweepExecutionPage.class,
                Crud.params().with("sweep_rule_id", id).with("limit", limit).with("cursor", cursor));
    }
}
