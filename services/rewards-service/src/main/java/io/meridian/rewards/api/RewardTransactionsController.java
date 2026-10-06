package io.meridian.rewards.api;

import io.meridian.rewards.api.generated.RewardTransactionsApi;
import io.meridian.rewards.api.generated.model.RewardTransaction;
import io.meridian.rewards.api.generated.model.RewardTransactionCreate;
import io.meridian.rewards.api.generated.model.RewardTransactionsIngestRequest;
import io.meridian.rewards.api.generated.model.RewardTransactionsReverseRequest;
import io.meridian.rewards.application.EarnService;
import io.meridian.rewards.application.Json;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Qualifying transactions: earn evaluation (one program or all), and reversal on refund or chargeback. */
@RestController
public class RewardTransactionsController implements RewardTransactionsApi {
    private final EarnService earn;
    private final Http http;

    public RewardTransactionsController(EarnService earn, Http http) {
        this.earn = earn;
        this.http = http;
    }

    @Override
    public ResponseEntity<RewardTransaction> rewardTransactionsCreate(String idempotencyKey, RewardTransactionCreate body) {
        return http.created(idempotencyKey, "transaction.create", body, RewardTransaction.class, () -> earn.create(http.tree(body)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> rewardTransactionsIngest(String idempotencyKey, RewardTransactionsIngestRequest body) {
        return http.document(idempotencyKey, "transaction.ingest", null, body, () -> earn.ingest(http.tree(body)));
    }

    @Override
    public ResponseEntity<RewardTransaction> rewardTransactionsReverse(UUID id, String idempotencyKey,
                                                                       RewardTransactionsReverseRequest body) {
        String reason = Json.required(http.tree(body), "reversal_reason");
        return http.ok(idempotencyKey, "transaction.reverse", id, body, RewardTransaction.class, () -> earn.reverse(id, reason));
    }
}
