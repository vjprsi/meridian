package io.meridian.transactionmonitoring.api;

import io.meridian.transactionmonitoring.api.generated.TmTransactionsApi;
import io.meridian.transactionmonitoring.api.generated.model.TmTransaction;
import io.meridian.transactionmonitoring.api.generated.model.TmTransactionCreate;
import io.meridian.transactionmonitoring.api.generated.model.TmTransactionsIngestRequest;
import io.meridian.transactionmonitoring.application.MonitoringService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Monitored transaction feed: single legs or batches, each evaluated by the streaming scenarios. */
@RestController
public class TmTransactionsController implements TmTransactionsApi {
    private final MonitoringService monitoring;
    private final Http http;

    public TmTransactionsController(MonitoringService monitoring, Http http) {
        this.monitoring = monitoring;
        this.http = http;
    }

    @Override
    public ResponseEntity<TmTransaction> tmTransactionsCreate(String idempotencyKey, TmTransactionCreate body) {
        return http.once(idempotencyKey, body, TmTransaction.class,
                () -> http.respond(HttpStatus.CREATED, monitoring.ingestOne(http.tree(body)), TmTransaction.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> tmTransactionsIngest(String idempotencyKey, TmTransactionsIngestRequest body) {
        return http.onceMap(idempotencyKey, body, () -> monitoring.ingest(http.tree(body).get("transactions")));
    }
}
