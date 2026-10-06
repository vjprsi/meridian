package io.meridian.identity.api;

import io.meridian.web.idempotency.Idempotency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Response helpers shared by the identity controllers. */
final class Responses {
    private Responses() {}

    /**
     * Contract defect workaround: several operations are generated as {@code ResponseEntity<Void>} (or a model without
     * room for one-time secrets) although they return a JSON body. The servlet layer serialises the actual body.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> ResponseEntity<T> body(ResponseEntity<?> r) {
        return (ResponseEntity) r;
    }

    /** A response body plus plaintext secrets that must be shown exactly once. */
    record WithSecrets(JsonNode body, Map<String, String> secrets) {}

    /**
     * Idempotent POST whose response carries one-time secrets. The stored (replayable) response never contains the
     * secret: an Idempotent-Replayed response says the secret was already delivered instead of revealing it again.
     */
    static <T> ResponseEntity<T> secretOnce(Idempotency idempotency, String key, Object request, HttpStatus status,
                                            Supplier<WithSecrets> action) {
        AtomicReference<Map<String, String>> secrets = new AtomicReference<>();
        ResponseEntity<JsonNode> r = idempotency.execute(key, request, JsonNode.class, () -> {
            WithSecrets w = action.get();
            secrets.set(w.secrets());
            return ResponseEntity.status(status).body(w.body());
        });
        ObjectNode out = ((ObjectNode) r.getBody()).deepCopy();
        Map<String, String> s = secrets.get();
        if (s != null) {
            new LinkedHashMap<>(s).forEach(out::put);
        } else {
            out.put("secret_delivered", "the secret was returned only on the original response");
        }
        return body(ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders()).body(out));
    }
}
