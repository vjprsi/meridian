package io.meridian.config.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Response helpers shared by the controllers. */
final class Responses {
    private Responses() {}

    /**
     * Contract defect workaround: several actions are declared with no response schema, so the generated method
     * returns {@code ResponseEntity<Void>}; the body is still serialised by Spring at runtime.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static ResponseEntity<Void> body(ResponseEntity<?> r) {
        return (ResponseEntity) r;
    }

    /** Model response with the row_version as ETag. */
    static <T> ResponseEntity<T> row(JsonMapper json, HttpStatus status, String rowJson, Class<T> type) {
        JsonNode node = json.readTree(rowJson);
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status);
        if (node.hasNonNull("row_version")) {
            b.eTag("\"" + node.get("row_version").asString() + "\"");
        }
        return b.body(json.treeToValue(node, type));
    }

    static Object request(String op, Object id, Object body) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("op", op);
        m.put("id", id == null ? null : id.toString());
        m.put("body", body);
        return m;
    }
}
