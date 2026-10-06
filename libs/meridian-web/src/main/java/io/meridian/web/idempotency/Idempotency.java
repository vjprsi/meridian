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
 * A replay with the same key and body returns the stored response; the same key with a different body is
 * rejected; a replay while the first request is still running gets 409.
 */
public class Idempotency {
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    public Idempotency(JdbcClient jdbc, JsonMapper json, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = tx;
    }

    public <T> ResponseEntity<T> execute(String key, Object request, Class<T> responseType,
                                         Supplier<ResponseEntity<T>> action) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw ApiException.badRequest("idempotency_key_required", "Idempotency-Key header (1-128 chars) is required");
        }
        String tenant = TenantContext.require().toString();
        String hash = sha256(json.writeValueAsString(request));
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
                            "Idempotency-Key was already used with a different request body");
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
