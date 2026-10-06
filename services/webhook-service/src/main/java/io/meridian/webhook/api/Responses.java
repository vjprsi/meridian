package io.meridian.webhook.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Response helpers shared by the controllers. */
final class Responses {
    private Responses() {}

    /**
     * Returns a JSON body from an operation whose generated signature is {@code ResponseEntity<Void>} or another
     * model type (the contract declares 204 / a model without the one-time secret). Generics are erased, and the
     * message converter serialises the runtime body.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> ResponseEntity<T> body(HttpStatus status, Object body) {
        return (ResponseEntity) ResponseEntity.status(status).body(body);
    }

    /** Idempotency fingerprint for bodiless actions: binds the key to the operation and target. */
    static Map<String, Object> op(String op, UUID id, Object body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        m.put("id", id.toString());
        if (body != null) {
            m.put("body", body);
        }
        return m;
    }
}
