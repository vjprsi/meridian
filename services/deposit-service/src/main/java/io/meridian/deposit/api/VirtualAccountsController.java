package io.meridian.deposit.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.api.generated.VirtualAccountsApi;
import io.meridian.deposit.api.generated.model.AccountBalance;
import io.meridian.deposit.api.generated.model.LedgerEntryPage;
import io.meridian.deposit.api.generated.model.VirtualAccount;
import io.meridian.deposit.api.generated.model.VirtualAccountCreate;
import io.meridian.deposit.api.generated.model.VirtualAccountUpdate;
import io.meridian.deposit.api.generated.model.VirtualAccountsBulkRequest;
import io.meridian.deposit.api.generated.model.VirtualAccountsCloseRequest;
import io.meridian.deposit.application.VirtualAccountService;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Virtual accounts: creation (single and bulk) with ledger sub-accounts, closing, balance and transactions. */
@RestController
public class VirtualAccountsController implements VirtualAccountsApi {
    private final VirtualAccountService virtualAccounts;
    private final Actions actions;
    private final CrudEngine crud;

    public VirtualAccountsController(VirtualAccountService virtualAccounts, Actions actions, CrudEngine crud) {
        this.virtualAccounts = virtualAccounts;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<VirtualAccount> virtualAccountsCreate(String idempotencyKey, VirtualAccountCreate body) {
        return actions.create(idempotencyKey, body, VirtualAccount.class, () -> virtualAccounts.create(body));
    }

    @Override
    public ResponseEntity<VirtualAccount> virtualAccountsUpdate(UUID id, String ifMatch, VirtualAccountUpdate body) {
        JsonNode req = actions.tree(body);
        if ("CLOSED".equals(Http.text(req, "status"))) {
            throw io.meridian.common.error.ApiException.unprocessable("field_not_updatable", "use POST /close to close");
        }
        return actions.guardedUpdate(crud, "virtual_account", VirtualAccount.class, id, ifMatch, body,
                Set.of("master_account_id", "currency", "virtual_account_number", "iban"), null);
    }

    @Override
    public ResponseEntity<Void> virtualAccountsBulk(String idempotencyKey, VirtualAccountsBulkRequest body) {
        JsonNode req = actions.tree(body);
        JsonNode result = actions.document(idempotencyKey, "bulk", body, () -> virtualAccounts.bulk(req.path("items")));
        return Http.json(actions.json(), result);
    }

    @Override
    public ResponseEntity<VirtualAccount> virtualAccountsClose(UUID id, String idempotencyKey, VirtualAccountsCloseRequest body) {
        return actions.action(idempotencyKey, id, "close", body, VirtualAccount.class,
                () -> virtualAccounts.close(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<AccountBalance> virtualAccountsBalance(UUID id) {
        TenantContext.require();
        return ResponseEntity.ok(actions.model(virtualAccounts.balance(id), AccountBalance.class));
    }

    @Override
    public ResponseEntity<LedgerEntryPage> virtualAccountsTransactions(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(actions.model(virtualAccounts.transactions(id, limit, cursor), LedgerEntryPage.class));
    }
}
