package io.meridian.limits.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.limits.api.generated.LimitChecksApi;
import io.meridian.limits.api.generated.model.LimitCheck;
import io.meridian.limits.api.generated.model.LimitCheckCreate;
import io.meridian.limits.api.generated.model.LimitChecksCommitRequest;
import io.meridian.limits.api.generated.model.LimitChecksEvaluateRequest;
import io.meridian.limits.api.generated.model.LimitChecksExpireReservationsRequest;
import io.meridian.limits.api.generated.model.LimitChecksReleaseRequest;
import io.meridian.limits.application.LimitCheckService;
import io.meridian.limits.infrastructure.Db;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Check-and-reserve, commit / release, dry-run evaluation and reservation expiry. */
@RestController
public class LimitChecksController implements LimitChecksApi {
    private final LimitCheckService checks;
    private final Http http;

    public LimitChecksController(LimitCheckService checks, Http http) {
        this.checks = checks;
        this.http = http;
    }

    /** Idempotent through the unique idempotency_key of limit_check (single transaction, no extra round trips). */
    @Override
    public ResponseEntity<LimitCheck> limitChecksCreate(String idempotencyKey, LimitCheckCreate body) {
        LimitCheckService.CheckResult r = checks.check(http.tree(body), idempotencyKey);
        ResponseEntity<LimitCheck> resp = http.respond(r.replayed() ? HttpStatus.OK : HttpStatus.CREATED, r.row(), LimitCheck.class);
        return r.replayed() ? ResponseEntity.status(resp.getStatusCode()).headers(resp.getHeaders())
                .header("Idempotent-Replayed", "true").body(resp.getBody()) : resp;
    }

    @Override
    public ResponseEntity<LimitCheck> limitChecksCommit(UUID id, String idempotencyKey, LimitChecksCommitRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, LimitCheck.class,
                () -> http.ok(checks.commit(id, Db.dec(req, "amount")), LimitCheck.class));
    }

    @Override
    public ResponseEntity<LimitCheck> limitChecksRelease(UUID id, String idempotencyKey, LimitChecksReleaseRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, LimitCheck.class,
                () -> http.ok(checks.release(id, Http.text(req, "reason")), LimitCheck.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> limitChecksEvaluate(String idempotencyKey, LimitChecksEvaluateRequest body) {
        TenantContext.require();
        return ResponseEntity.ok(checks.evaluate(http.tree(body)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> limitChecksExpireReservations(String idempotencyKey,
                                                                             LimitChecksExpireReservationsRequest body) {
        JsonNode req = http.tree(body);
        int max = req.hasNonNull("max_checks") ? req.get("max_checks").asInt() : 500;
        return http.onceMap(idempotencyKey, body, () -> checks.expire(max));
    }
}
