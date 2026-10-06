package io.meridian.document.api;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Response helpers shared by the controllers. */
final class Responses {
    private Responses() {}

    /** Response type of operations returning a free-form JSON object (contract response {@code object}). */
    static final Class<Map<String, Object>> JSON_OBJECT = jsonObjectType();

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> jsonObjectType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    static <T> ResponseEntity<T> created(T body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** Request fingerprint for bodiless actions, so a key reused on another resource or action is rejected. */
    static Map<String, Object> op(String op, UUID id) {
        return Map.of("op", op, "id", id.toString());
    }

    static Map<String, Object> op(String op, UUID id, Object body) {
        return body == null ? op(op, id) : Map.of("op", op, "id", id.toString(), "body", body);
    }
}
