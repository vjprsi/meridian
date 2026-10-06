package io.meridian.referencedata.api;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Response helpers shared by the controllers. */
final class Responses {
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private Responses() {}

    /** Free-form JSON object result (contract {@code object}); keeps status and headers (e.g. Idempotent-Replayed). */
    static ResponseEntity<Map<String, Object>> object(JsonMapper json, ResponseEntity<JsonNode> r) {
        JsonNode body = r.getBody();
        return ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders())
                .body(body == null ? null : json.convertValue(body, OBJECT));
    }

    static ResponseEntity<Map<String, Object>> ok(JsonMapper json, JsonNode body) {
        return object(json, ResponseEntity.ok(body));
    }
}
