package io.meridian.config.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Response helpers shared by the controllers. */
final class Responses {
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private Responses() {}

    /** Model response with the row_version as ETag. */
    static <T> ResponseEntity<T> row(JsonMapper json, HttpStatus status, String rowJson, Class<T> type) {
        JsonNode node = json.readTree(rowJson);
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status);
        if (node.hasNonNull("row_version")) {
            b.eTag("\"" + node.get("row_version").asString() + "\"");
        }
        return b.body(json.treeToValue(node, type));
    }

    /** Free-form JSON object result (contract {@code object}); keeps status and headers (e.g. Idempotent-Replayed). */
    static ResponseEntity<Map<String, Object>> object(JsonMapper json, ResponseEntity<JsonNode> r) {
        JsonNode body = r.getBody();
        return ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders())
                .body(body == null ? null : json.convertValue(body, OBJECT));
    }

    static Object request(String op, Object id, Object body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        m.put("id", id == null ? null : id.toString());
        m.put("body", body);
        return m;
    }
}
