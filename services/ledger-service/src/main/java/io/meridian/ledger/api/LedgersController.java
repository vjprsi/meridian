package io.meridian.ledger.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.ledger.api.generated.LedgersApi;
import io.meridian.ledger.api.generated.model.Ledger;
import io.meridian.ledger.api.generated.model.LedgerEodRun;
import io.meridian.ledger.api.generated.model.LedgerVerification;
import io.meridian.ledger.api.generated.model.LedgersEodRequest;
import io.meridian.ledger.api.generated.model.LedgersLockRequest;
import io.meridian.ledger.application.AccountService;
import io.meridian.ledger.application.LedgerOpsService;
import java.time.LocalDate;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Ledgers: CRUD is generic; lock, end-of-day processing and integrity verification. */
@RestController
public class LedgersController implements LedgersApi {
    private final AccountService accounts;
    private final LedgerOpsService ops;
    private final CrudEngine crud;
    private final JsonMapper json;

    public LedgersController(AccountService accounts, LedgerOpsService ops, CrudEngine crud, JsonMapper json) {
        this.accounts = accounts;
        this.ops = ops;
        this.crud = crud;
        this.json = json;
    }

    @Override
    public ResponseEntity<Ledger> ledgersLock(UUID id, String idempotencyKey, LedgersLockRequest body) {
        TenantContext.require();
        String reason = json.valueToTree(body).path("reason").asString();
        return ResponseEntity.ok(crud.read(accounts.lockLedger(id, reason), Ledger.class));
    }

    @Override
    public ResponseEntity<LedgerVerification> ledgersVerify(UUID id, String idempotencyKey) {
        TenantContext.require();
        return ResponseEntity.ok(crud.read(ops.verify(id), LedgerVerification.class));
    }

    @Override
    public ResponseEntity<LedgerEodRun> ledgersEod(UUID id, String idempotencyKey, LedgersEodRequest body) {
        TenantContext.require();
        LocalDate date = LocalDate.parse(json.valueToTree(body).get("business_date").asString());
        return ResponseEntity.status(HttpStatus.CREATED).body(crud.read(ops.endOfDay(id, date), LedgerEodRun.class));
    }
}
