package io.meridian.deposit.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.api.generated.AccountHoldersApi;
import io.meridian.deposit.api.generated.model.AccountHolder;
import io.meridian.deposit.api.generated.model.AccountHolderCreate;
import io.meridian.deposit.api.generated.model.AccountHolderUpdate;
import io.meridian.deposit.application.HolderService;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Account holders: adding (role rules, minors need a guardian) and removing (keeps an owner) with events. */
@RestController
public class AccountHoldersController implements AccountHoldersApi {
    private final HolderService holders;
    private final Actions actions;
    private final CrudEngine crud;

    public AccountHoldersController(HolderService holders, Actions actions, CrudEngine crud) {
        this.holders = holders;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<AccountHolder> accountHoldersCreate(String idempotencyKey, AccountHolderCreate body) {
        return actions.create(idempotencyKey, body, AccountHolder.class, () -> holders.add(body));
    }

    @Override
    public ResponseEntity<Void> accountHoldersDelete(UUID id) {
        TenantContext.require();
        holders.remove(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<AccountHolder> accountHoldersUpdate(UUID id, String ifMatch, AccountHolderUpdate body) {
        return actions.guardedUpdate(crud, "account_holder", AccountHolder.class, id, ifMatch, body,
                Set.of("deposit_account_id", "role", "status"), "remove the holder and add it with the new role");
    }
}
