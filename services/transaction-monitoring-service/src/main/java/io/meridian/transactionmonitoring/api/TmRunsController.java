package io.meridian.transactionmonitoring.api;

import io.meridian.transactionmonitoring.api.generated.TmRunsApi;
import io.meridian.transactionmonitoring.api.generated.model.TmRun;
import io.meridian.transactionmonitoring.api.generated.model.TmRunCreate;
import io.meridian.transactionmonitoring.api.generated.model.TmRunsCancelRequest;
import io.meridian.transactionmonitoring.application.RunService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Batch monitoring runs and backtests (processed asynchronously; poll the run). */
@RestController
public class TmRunsController implements TmRunsApi {
    private final RunService runs;
    private final Http http;

    public TmRunsController(RunService runs, Http http) {
        this.runs = runs;
        this.http = http;
    }

    @Override
    public ResponseEntity<TmRun> tmRunsCreate(String idempotencyKey, TmRunCreate body) {
        return http.once(idempotencyKey, body, TmRun.class,
                () -> http.respond(HttpStatus.CREATED, runs.create(http.tree(body)), TmRun.class));
    }

    @Override
    public ResponseEntity<TmRun> tmRunsCancel(UUID id, String idempotencyKey, TmRunsCancelRequest body) {
        String reason = Http.text(http.tree(body), "reason");
        return http.once(idempotencyKey, body, TmRun.class, () -> http.ok(runs.cancel(id, reason), TmRun.class));
    }
}
