package io.meridian.rewards.api;

import io.meridian.rewards.api.generated.CashbackTransactionsApi;
import io.meridian.rewards.api.generated.model.CashbackTransaction;
import io.meridian.rewards.api.generated.model.CashbackTransactionsReverseRequest;
import io.meridian.rewards.application.CashbackService;
import io.meridian.rewards.application.Json;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Cashback awards: early confirmation, payout through the ledger, reversal. */
@RestController
public class CashbackTransactionsController implements CashbackTransactionsApi {
    private final CashbackService cashback;
    private final Http http;

    public CashbackTransactionsController(CashbackService cashback, Http http) {
        this.cashback = cashback;
        this.http = http;
    }

    @Override
    public ResponseEntity<CashbackTransaction> cashbackTransactionsConfirm(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "cashback.confirm", id, null, CashbackTransaction.class, () -> cashback.confirm(id));
    }

    @Override
    public ResponseEntity<CashbackTransaction> cashbackTransactionsPay(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "cashback.pay", id, null, CashbackTransaction.class, () -> cashback.pay(id));
    }

    @Override
    public ResponseEntity<CashbackTransaction> cashbackTransactionsReverse(UUID id, String idempotencyKey,
                                                                           CashbackTransactionsReverseRequest body) {
        String reason = Json.required(http.tree(body), "reversal_reason");
        return http.ok(idempotencyKey, "cashback.reverse", id, body, CashbackTransaction.class, () -> cashback.reverse(id, reason));
    }
}
