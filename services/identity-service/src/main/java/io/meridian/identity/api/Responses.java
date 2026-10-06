package io.meridian.identity.api;

import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Response helpers shared by the identity controllers. */
final class Responses {
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private Responses() {}

    /** A computed JSON result as the free-form object the contract declares. */
    static Map<String, Object> object(JsonMapper json, JsonNode node) {
        return node == null ? null : json.convertValue(node, OBJECT);
    }

    /** Converts an idempotent JSON result (status and headers kept, e.g. Idempotent-Replayed). */
    static ResponseEntity<Map<String, Object>> object(JsonMapper json, ResponseEntity<JsonNode> r) {
        return ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders()).body(object(json, r.getBody()));
    }

    /** A response body plus plaintext secrets that must be shown exactly once. */
    record WithSecrets(JsonNode body, Map<String, String> secrets) {}

    /**
     * Idempotent POST whose response carries one-time secrets (response-only contract fields). The stored (replayable)
     * response never contains the secret: the secrets are set on the typed model only after the response was stored,
     * so a replay returns them as null.
     */
    static <T> ResponseEntity<T> secretOnce(Idempotency idempotency, JsonMapper json, String key, Object request,
                                            HttpStatus status, Class<T> type, Supplier<WithSecrets> action,
                                            BiConsumer<T, Map<String, String>> reveal) {
        AtomicReference<Map<String, String>> secrets = new AtomicReference<>();
        ResponseEntity<T> r = idempotency.execute(key, request, type, () -> {
            WithSecrets w = action.get();
            secrets.set(w.secrets());
            return ResponseEntity.status(status).body(json.treeToValue(w.body(), type));
        });
        Map<String, String> s = secrets.get();
        if (s != null && !s.isEmpty() && r.getBody() != null) {
            reveal.accept(r.getBody(), s);
        }
        return r;
    }
}
