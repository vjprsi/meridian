package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessVirtualAccountsApi;
import io.meridian.businesschannel.api.generated.model.BusinessVirtualAccountsCloseRequest;
import io.meridian.businesschannel.api.generated.model.BusinessVirtualAccountsCreateRequest;
import io.meridian.businesschannel.api.generated.model.LedgerEntryPage;
import io.meridian.businesschannel.api.generated.model.VirtualAccount;
import io.meridian.businesschannel.api.generated.model.VirtualAccountPage;
import io.meridian.businesschannel.application.CollectionService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Virtual accounts on the business's accounts. */
@RestController
public class BusinessVirtualAccountsController implements BusinessVirtualAccountsApi {
    private final CollectionService collections;
    private final Http http;

    public BusinessVirtualAccountsController(CollectionService collections, Http http) {
        this.collections = collections;
        this.http = http;
    }

    @Override
    public ResponseEntity<VirtualAccountPage> businessVirtualAccountsList(UUID masterAccountId, Integer limit,
                                                                          String cursor) {
        return http.ok(collections.virtualAccounts(masterAccountId), VirtualAccountPage.class);
    }

    @Override
    public ResponseEntity<java.util.Map<String, Object>> businessVirtualAccountsCreate(String idempotencyKey,
                                                                       BusinessVirtualAccountsCreateRequest body) {
        return http.outcome(idempotencyKey, body, () -> collections.createVirtualAccount(http.body(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<VirtualAccount> businessVirtualAccountsGet(UUID virtualAccountId) {
        return http.ok(collections.virtualAccount(virtualAccountId), VirtualAccount.class);
    }

    @Override
    public ResponseEntity<LedgerEntryPage> businessVirtualAccountsTransactions(UUID virtualAccountId, Integer limit,
                                                                              String cursor) {
        return http.ok(collections.virtualAccountTransactions(virtualAccountId, limit, cursor), LedgerEntryPage.class);
    }

    @Override
    public ResponseEntity<VirtualAccount> businessVirtualAccountsClose(UUID virtualAccountId, String idempotencyKey,
                                                                      BusinessVirtualAccountsCloseRequest body) {
        return http.ok(collections.closeVirtualAccount(virtualAccountId, http.body(body), idempotencyKey),
                VirtualAccount.class);
    }
}
