package io.meridian.tenant.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Response helpers shared by the controllers. */
final class Responses {
    private Responses() {}

    /** Maps a row to the API model with an ETag carrying row_version. */
    static <T> ResponseEntity<T> of(HttpStatus status, JsonNode row, Class<T> type, JsonMapper json) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status);
        JsonNode v = row.get("row_version");
        if (v != null && !v.isNull()) {
            b.eTag("\"" + v.asString() + "\"");
        }
        return b.body(json.treeToValue(row, type));
    }

    /**
     * Contract defect workaround: some operations are declared with no response schema (generated as
     * {@code ResponseEntity<Void>}) although they return a JSON document. The body is passed through untyped.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static ResponseEntity<Void> untyped(ResponseEntity<?> r) {
        return (ResponseEntity) r;
    }

    static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asString() : null;
    }
}
