package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyWalletsApi;
import io.meridian.customerchannel.api.generated.model.AccountBalance;
import io.meridian.customerchannel.api.generated.model.Wallet;
import io.meridian.customerchannel.api.generated.model.WalletPage;
import io.meridian.customerchannel.api.generated.model.WalletTransactionPage;
import io.meridian.customerchannel.application.LendingWalletService;
import java.time.OffsetDateTime;
import java.util.UUID;
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
}
