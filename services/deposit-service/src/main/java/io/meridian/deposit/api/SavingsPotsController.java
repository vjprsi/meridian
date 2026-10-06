package io.meridian.deposit.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.api.generated.SavingsPotsApi;
import io.meridian.deposit.api.generated.model.SavingsPot;
import io.meridian.deposit.api.generated.model.SavingsPotCreate;
import io.meridian.deposit.api.generated.model.SavingsPotRulePage;
import io.meridian.deposit.api.generated.model.SavingsPotUpdate;
import io.meridian.deposit.api.generated.model.SavingsPotsDepositRequest;
import io.meridian.deposit.api.generated.model.SavingsPotsWithdrawRequest;
import io.meridian.deposit.application.SavingsPotService;
import io.meridian.deposit.infrastructure.Rows;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Savings pots: open (ledger sub-account), deposit, withdraw, close, rules. */
@RestController
public class SavingsPotsController implements SavingsPotsApi {
    private final SavingsPotService pots;
    private final Actions actions;
    private final CrudEngine crud;
    private final Rows rows;

    public SavingsPotsController(SavingsPotService pots, Actions actions, CrudEngine crud, Rows rows) {
        this.pots = pots;
        this.actions = actions;
        this.crud = crud;
        this.rows = rows;
    }

    @Override
    public ResponseEntity<SavingsPot> savingsPotsCreate(String idempotencyKey, SavingsPotCreate body) {
        return actions.create(idempotencyKey, body, SavingsPot.class, () -> pots.create(body));
    }

    @Override
    public ResponseEntity<SavingsPot> savingsPotsUpdate(UUID id, String ifMatch, SavingsPotUpdate body) {
        return actions.guardedUpdate(crud, "savings_pot", SavingsPot.class, id, ifMatch, body,
                Set.of("currency", "deposit_account_id"), null);
    }

    @Override
    public ResponseEntity<SavingsPot> savingsPotsDeposit(UUID id, String idempotencyKey, SavingsPotsDepositRequest body) {
        return actions.action(idempotencyKey, id, "deposit", body, SavingsPot.class,
                () -> pots.deposit(id, Http.decimal(actions.tree(body), "amount"), idempotencyKey));
    }

    @Override
    public ResponseEntity<SavingsPot> savingsPotsWithdraw(UUID id, String idempotencyKey, SavingsPotsWithdrawRequest body) {
        return actions.action(idempotencyKey, id, "withdraw", body, SavingsPot.class,
                () -> pots.withdraw(id, Http.decimal(actions.tree(body), "amount"), idempotencyKey));
    }

    @Override
    public ResponseEntity<SavingsPot> savingsPotsClose(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "close", null, SavingsPot.class, () -> pots.close(id, idempotencyKey));
    }

    @Override
    public ResponseEntity<SavingsPotRulePage> savingsPotsRules(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        rows.get("savings_pot", id);
        return crud.list("savings_pot_rule", SavingsPotRulePage.class, Crud.params().with("savings_pot_id", id)
                .with("limit", limit).with("cursor", cursor));
    }
}
