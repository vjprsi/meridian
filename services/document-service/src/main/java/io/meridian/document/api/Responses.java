package io.meridian.document.api;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Response helpers shared by the controllers. */
final class Responses {
    private Responses() {}

    /**
     * The contract declares {@code 204 No Content} for operations that must return data (presigned URLs, previews,
     * callback acknowledgements). They answer 200 with a JSON body instead; the generic type is erased at runtime.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static ResponseEntity<Void> body(Object body) {
        return (ResponseEntity) ResponseEntity.ok(body);
    }

    /** Same as {@link #body(Object)} for an already built response (keeps status and headers, e.g. replays). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static ResponseEntity<Void> erase(ResponseEntity<?> response) {
        return (ResponseEntity) response;
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
