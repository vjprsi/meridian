package io.meridian.deposit.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.api.generated.SavingsPotRulesApi;
import io.meridian.deposit.api.generated.model.SavingsPotRule;
import io.meridian.deposit.api.generated.model.SavingsPotRuleCreate;
import io.meridian.deposit.api.generated.model.SavingsPotRuleUpdate;
import io.meridian.deposit.application.SavingsPotService;
import io.meridian.deposit.infrastructure.Rows;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Automatic saving rules: validated per rule type; FIXED_SCHEDULE rules get a next run date. */
@RestController
public class SavingsPotRulesController implements SavingsPotRulesApi {
    private final SavingsPotService pots;
    private final Actions actions;
    private final CrudEngine crud;
    private final Rows rows;

    public SavingsPotRulesController(SavingsPotService pots, Actions actions, CrudEngine crud, Rows rows) {
        this.pots = pots;
        this.actions = actions;
        this.crud = crud;
        this.rows = rows;
    }

    @Override
    public ResponseEntity<SavingsPotRule> savingsPotRulesCreate(String idempotencyKey, SavingsPotRuleCreate body) {
        return actions.create(idempotencyKey, body, SavingsPotRule.class, () -> pots.createRule(body));
    }

    @Override
    public ResponseEntity<SavingsPotRule> savingsPotRulesUpdate(UUID id, String ifMatch, SavingsPotRuleUpdate body) {
        TenantContext.require();
        ObjectNode merged = rows.get("savings_pot_rule", id);
        ObjectNode changes = rows.columns("savings_pot_rule", body);
        if (changes.has("savings_pot_id")) {
            throw io.meridian.common.error.ApiException.unprocessable("field_not_updatable", "savings_pot_id cannot change");
        }
        merged.setAll(changes);
        ObjectNode pot = rows.get("savings_pot", Rows.uuid(merged, "savings_pot_id"));
        SavingsPotService.validateRule(merged, Rows.str(pot, "currency"));
        return crud.update("savings_pot_rule", SavingsPotRule.class, body, ifMatch, Crud.params().with("id", id));
    }
}
