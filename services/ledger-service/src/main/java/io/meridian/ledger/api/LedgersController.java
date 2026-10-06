package io.meridian.ledger.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.ledger.api.generated.LedgersApi;
import io.meridian.ledger.api.generated.model.Ledger;
import io.meridian.ledger.api.generated.model.LedgersLockRequest;
import io.meridian.ledger.application.AccountService;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Ledgers: CRUD is generic; locking stops all new postings. */
@RestController
public class LedgersController implements LedgersApi {
    private final AccountService accounts;
    private final CrudEngine crud;
    private final JsonMapper json;

    public LedgersController(AccountService accounts, CrudEngine crud, JsonMapper json) {
        this.accounts = accounts;
        this.crud = crud;
        this.json = json;
    }

    @Override
    public ResponseEntity<Ledger> ledgersLock(UUID id, String idempotencyKey, LedgersLockRequest body) {
        TenantContext.require();
        String reason = json.valueToTree(body).path("reason").asString();
        return ResponseEntity.ok(crud.read(accounts.lockLedger(id, reason), Ledger.class));
    }
}
