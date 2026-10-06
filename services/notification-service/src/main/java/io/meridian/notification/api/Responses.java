package io.meridian.notification.api;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Response helpers shared by the controllers. */
final class Responses {
    private Responses() {}

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
