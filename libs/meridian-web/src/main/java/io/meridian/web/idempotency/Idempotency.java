package io.meridian.web.idempotency;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exactly-once POST handling backed by the service's {@code idempotency_key} table.
 * A replay with the same key and request returns the stored response; the same key with a different request is
 * rejected (422 {@code idempotency_key_reused}); a replay while the first request is still running gets 409.
 *
 * <p>SEC-19: the request fingerprint covers the HTTP method, the request path (route plus path ids), the caller
 * (principal) and the body, so a key reused on another resource, operation or by another caller never replays a
 * stored response (it is refused instead of silently returning someone else's result).
 * SEC-34: a claim left "in progress" by a crashed request expires after {@code meridian.idempotency.stale-after}
 * (default 10 minutes) and can be taken over; completed keys are purged after
 * {@code meridian.idempotency.retention} (default 30 days) by {@link #purgeExpired()}.
 */
public class Idempotency {
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final java.time.Duration staleAfter;
    private final java.time.Duration retention;

    public Idempotency(JdbcClient jdbc, JsonMapper json, TransactionTemplate tx) {
        this(jdbc, json, tx, java.time.Duration.ofMinutes(10), java.time.Duration.ofDays(30));
    }

    public Idempotency(JdbcClient jdbc, JsonMapper json, TransactionTemplate tx, java.time.Duration staleAfter,
                       java.time.Duration retention) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = tx;
        this.staleAfter = staleAfter;
        this.retention = retention;
    }

    /**
     * What the stored request hash covers: method, path, principal and body (SEC-19). Outside an HTTP request
     * (jobs, consumers) the principal and the body.
     */
    String fingerprint(Object request) {
        StringBuilder f = new StringBuilder();
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes a) {
            var r = a.getRequest();
            f.append(r.getMethod()).append(' ').append(r.getRequestURI());
        }
        f.append('\n').append(TenantContext.principal()).append('\n').append(json.writeValueAsString(request));
        return sha256(f.toString());
    }

    /** SEC-34: deletes completed keys older than the retention period (all tenants; run from the EOD pack / a job). */
    public int purgeExpired() {
        return jdbc.sql("delete from idempotency_key where created_at < now() - make_interval(secs => ?)")
                .param((double) retention.toSeconds()).update();
    }

    public <T> ResponseEntity<T> execute(String key, Object request, Class<T> responseType,
                                         Supplier<ResponseEntity<T>> action) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw ApiException.badRequest("idempotency_key_required", "Idempotency-Key header (1-128 chars) is required");
        }
        String tenant = TenantContext.require().toString();
        String hash = fingerprint(request);
        // SEC-34: a claim abandoned by a crashed request (no response after stale-after) is released first
        tx.execute(s -> jdbc.sql("""
                delete from idempotency_key where tenant_id = ?::uuid and key = ? and response_status is null
                and created_at < now() - make_interval(secs => ?)""")
                .params(tenant, key, (double) staleAfter.toSeconds()).update());
        Integer claimed = tx.execute(s -> jdbc.sql("""
                insert into idempotency_key (tenant_id, key, request_hash) values (?::uuid, ?, ?)
                on conflict do nothing""").params(tenant, key, hash).update());
        if (claimed != null && claimed == 0) {
            Optional<Stored> prior = jdbc.sql("""
                    select request_hash, response_status, response_body::text as body
                    from idempotency_key where tenant_id = ?::uuid and key = ?""")
                    .params(tenant, key)
                    .query((rs, i) -> new Stored(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3)))
                    .optional();
            if (prior.isPresent()) {
                Stored p = prior.get();
                if (!p.hash().equals(hash)) {
                    throw ApiException.unprocessable("idempotency_key_reused",
                            "Idempotency-Key was already used for a different request (operation, resource, caller or body)");
                }
                if (p.status() == null) {
                    throw ApiException.conflict("request_in_progress", "A request with this Idempotency-Key is in progress");
                }
                T body = p.body() == null ? null : json.readValue(p.body(), responseType);
                return ResponseEntity.status(p.status()).header("Idempotent-Replayed", "true").body(body);
            }
        }
        try {
            ResponseEntity<T> result = action.get();
            String body = result.getBody() == null ? null : json.writeValueAsString(result.getBody());
            jdbc.sql("""
                    update idempotency_key set response_status = ?, response_body = ?::jsonb
                    where tenant_id = ?::uuid and key = ?""")
                    .params(result.getStatusCode().value(), body, tenant, key).update();
            return result;
        } catch (RuntimeException e) {
            jdbc.sql("delete from idempotency_key where tenant_id = ?::uuid and key = ? and response_status is null")
                    .params(tenant, key).update();
            throw e;
        }
    }

    private record Stored(String hash, Integer status, String body) {}

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
