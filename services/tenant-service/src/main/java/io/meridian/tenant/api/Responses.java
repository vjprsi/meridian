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


    static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asString() : null;
    }
}
