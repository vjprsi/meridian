package io.meridian.agentchannel.api;

import io.meridian.agentchannel.api.generated.AgentMeTransactionsApi;
import io.meridian.agentchannel.api.generated.model.AgentMeTransactionsQuoteRequest;
import io.meridian.agentchannel.api.generated.model.WalletTransaction;
import io.meridian.agentchannel.api.generated.model.WalletTransactionPage;
import io.meridian.agentchannel.application.MoneyService;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The agent's wallet transactions, quotes and receipts. */
@RestController
public class AgentMeTransactionsController implements AgentMeTransactionsApi {
    private final MoneyService money;
    private final Http http;

    public AgentMeTransactionsController(MoneyService money, Http http) {
        this.money = money;
        this.http = http;
    }

    @Override
    public ResponseEntity<WalletTransactionPage> agentMeTransactionsList(OffsetDateTime from, OffsetDateTime to,
                                                                         String txnType, String status,
                                                                         String idempotencyKey, Integer limit,
                                                                         String cursor) {
        return http.ok(money.transactions(from, to, txnType, status, idempotencyKey, limit, cursor),
                WalletTransactionPage.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> agentMeTransactionsQuote(String idempotencyKey,
                                                                        AgentMeTransactionsQuoteRequest body) {
        return http.object(money.quote(http.body(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<WalletTransaction> agentMeTransactionsGet(UUID transactionId) {
        return http.ok(money.transaction(transactionId), WalletTransaction.class);
    }

    @Override
    public ResponseEntity<String> agentMeTransactionsReceipt(UUID transactionId) {
        return Http.text(money.receipt(transactionId), "receipt-" + transactionId + ".txt");
    }
}
