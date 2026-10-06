package io.meridian.notification.api;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Response helpers shared by the controllers. */
final class Responses {
    private Responses() {}

    /**
     * The contract declares {@code 204 No Content} for some actions that must return data (preview, test-send,
     * provider test). Until the contract is fixed they answer 200 with a JSON body through the Void-typed method.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static ResponseEntity<Void> body(Object body) {
        return (ResponseEntity) ResponseEntity.ok(body);
    }

    static <T> ResponseEntity<T> created(T body, JsonNode row) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(HttpStatus.CREATED);
        if (row.hasNonNull("row_version")) {
            b.eTag("\"" + row.get("row_version").asString() + "\"");
        }
        return b.body(body);
    }

    static Map<String, Object> op(String op, UUID id) {
        return Map.of("op", op, "id", id.toString());
    }
}
