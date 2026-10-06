package io.meridian.kyc.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.kyc.application.KycSupport;
import io.meridian.web.idempotency.Idempotency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Runs a POST exactly once per Idempotency-Key. The hashed request includes the operation and the path id, so the
 * same key reused on another resource or operation is rejected instead of replaying a foreign response.
 */
@Component
public class Actions {
    private final Idempotency idempotency;
    private final KycSupport kyc;

    public Actions(Idempotency idempotency, KycSupport kyc) {
        this.idempotency = idempotency;
        this.kyc = kyc;
    }

    public <T> ResponseEntity<T> run(String key, String op, Object id, Object body, Class<T> type, HttpStatus status,
                                     Supplier<JsonNode> action) {
        TenantContext.require();
        return idempotency.execute(key, request(op, id, body), type,
                () -> ResponseEntity.status(status).body(kyc.read(action.get(), type)));
    }

    public <T> ResponseEntity<T> ok(String key, String op, Object id, Object body, Class<T> type, Supplier<JsonNode> action) {
        return run(key, op, id, body, type, HttpStatus.OK, action);
    }

    public <T> ResponseEntity<T> created(String key, String op, Object body, Class<T> type, Supplier<JsonNode> action) {
        return run(key, op, null, body, type, HttpStatus.CREATED, action);
    }

    /** Idempotent action whose response is built by the caller (raw JSON or no body). */
    public <T> ResponseEntity<T> raw(String key, String op, Object id, Object body, Class<T> type,
                                     Supplier<ResponseEntity<T>> action) {
        TenantContext.require();
        return idempotency.execute(key, request(op, id, body), type, action);
    }

    private static Map<String, Object> request(String op, Object id, Object body) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("op", op);
        if (id != null) {
            req.put("id", id.toString());
        }
        if (body != null) {
            req.put("body", body);
        }
        return req;
    }
}
