package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyWalletsApi;
import io.meridian.customerchannel.api.generated.model.AccountBalance;
import io.meridian.customerchannel.api.generated.model.MyWalletsFromBankRequest;
import io.meridian.customerchannel.api.generated.model.MyWalletsLookupRequest;
import io.meridian.customerchannel.api.generated.model.MyWalletsP2pQuoteRequest;
import io.meridian.customerchannel.api.generated.model.MyWalletsP2pRequest;
import io.meridian.customerchannel.api.generated.model.MyWalletsToBankRequest;
import io.meridian.customerchannel.api.generated.model.Wallet;
import io.meridian.customerchannel.api.generated.model.WalletPage;
import io.meridian.customerchannel.api.generated.model.WalletTransaction;
import io.meridian.customerchannel.api.generated.model.WalletTransactionPage;
import io.meridian.customerchannel.application.LendingWalletService;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The customer's wallets. */
@RestController
public class MyWalletsController implements MyWalletsApi {
    private final LendingWalletService wallets;
    private final Http http;

    public MyWalletsController(LendingWalletService wallets, Http http) {
        this.wallets = wallets;
        this.http = http;
    }

    @Override
    public ResponseEntity<WalletPage> myWalletsList(Integer limit, String cursor) {
        return http.ok(wallets.wallets(limit, cursor), WalletPage.class);
    }

    @Override
    public ResponseEntity<Wallet> myWalletsGet(UUID walletId) {
        return http.ok(wallets.wallet(walletId), Wallet.class);
    }

    @Override
    public ResponseEntity<AccountBalance> myWalletsBalance(UUID walletId) {
        return http.ok(wallets.walletBalance(walletId), AccountBalance.class);
    }

    @Override
    public ResponseEntity<WalletTransactionPage> myWalletsTransactions(UUID walletId, OffsetDateTime from,
                                                                       OffsetDateTime to, Integer limit, String cursor) {
        return http.ok(wallets.walletTransactions(walletId, from, to, limit, cursor), WalletTransactionPage.class);
    }

    @Override
    public ResponseEntity<Resource> myWalletsTransactionsReceipt(UUID walletId, UUID transactionId) {
        return Http.binary(wallets.walletTransactionReceipt(walletId, transactionId),
                "receipt-" + transactionId + ".pdf");
    }

    @Override
    public ResponseEntity<Map<String, Object>> myWalletsLookup(String idempotencyKey, MyWalletsLookupRequest body) {
        return http.object(wallets.lookup(http.body(body)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> myWalletsP2pQuote(UUID walletId, String idempotencyKey,
                                                                 MyWalletsP2pQuoteRequest body) {
        return http.object(wallets.p2pQuote(walletId, http.body(body)));
    }

    @Override
    public ResponseEntity<WalletTransaction> myWalletsP2p(UUID walletId, String idempotencyKey,
                                                          MyWalletsP2pRequest body) {
        return http.ok(wallets.p2p(walletId, http.body(body), idempotencyKey), WalletTransaction.class);
    }

    @Override
    public ResponseEntity<WalletTransaction> myWalletsToBank(UUID walletId, String idempotencyKey,
                                                             MyWalletsToBankRequest body) {
        return http.ok(wallets.toBank(walletId, http.body(body), idempotencyKey), WalletTransaction.class);
    }

    @Override
    public ResponseEntity<WalletTransaction> myWalletsFromBank(UUID walletId, String idempotencyKey,
                                                               MyWalletsFromBankRequest body) {
        return http.ok(wallets.fromBank(walletId, http.body(body), idempotencyKey), WalletTransaction.class);
    }
}
