package io.meridian.merchantchannel.api;

import io.meridian.merchantchannel.api.generated.MerchantAppTransactionsApi;
import io.meridian.merchantchannel.api.generated.model.WalletTransaction;
import io.meridian.merchantchannel.api.generated.model.WalletTransactionPage;
import io.meridian.merchantchannel.application.TransactionService;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Wallet transactions of the merchant in the user's stores. */
@RestController
public class MerchantAppTransactionsController implements MerchantAppTransactionsApi {
    private final TransactionService transactions;
    private final Http http;

    public MerchantAppTransactionsController(TransactionService transactions, Http http) {
        this.transactions = transactions;
        this.http = http;
    }

    @Override
    public ResponseEntity<WalletTransactionPage> merchantAppTransactionsList(OffsetDateTime from, OffsetDateTime to,
            UUID storeId, String txnType, String status, Integer limit, String cursor) {
        return http.ok(transactions.list(from, to, storeId, txnType, status, limit, cursor), WalletTransactionPage.class);
    }

    @Override
    public ResponseEntity<WalletTransaction> merchantAppTransactionsGet(UUID transactionId) {
        return http.ok(transactions.get(transactionId), WalletTransaction.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> merchantAppTransactionsRefundable(UUID transactionId) {
        return http.object(transactions.refundable(transactionId));
    }
}
