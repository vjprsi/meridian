package io.meridian.aiplatform.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Maps row JSON to API models, adding the ETag (row_version) where the resource has one. */
@Component
public class Responses {
    private final JsonMapper json;

    public Responses(JsonMapper json) {
        this.json = json;
    }

    public <T> ResponseEntity<T> of(HttpStatus status, String row, Class<T> type) {
        JsonNode n = json.readTree(row);
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status);
        if (n.hasNonNull("row_version")) {
            b.eTag("\"" + n.get("row_version").asString() + "\"");
        }
        return b.body(json.treeToValue(n, type));
    }

    /** Deterministic idempotency fingerprint of an action call (action name, target id, request body). */
    public JsonNode action(String action, java.util.UUID id, JsonNode body) {
        var n = json.createObjectNode();
        n.put("action", action);
        n.put("id", id.toString());
        if (body != null) {
            n.set("body", body);
        }
        return n;
    }

    /** A computed JSON object result (operations declared with response {@code object}). */
    public ResponseEntity<Map<String, Object>> object(JsonNode result) {
        return ResponseEntity.ok(json.convertValue(result, new TypeReference<Map<String, Object>>() {}));
    }

    /** Same as {@link #object(JsonNode)} for a (possibly replayed) idempotent response, keeping status and headers. */
    public ResponseEntity<Map<String, Object>> object(ResponseEntity<JsonNode> r) {
        return ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders())
                .body(r.getBody() == null ? null : json.convertValue(r.getBody(), new TypeReference<Map<String, Object>>() {}));
    }

    /** A text file (XML, CSV, JSON) served inline with its content type and file name. */
    public ResponseEntity<String> file(String content, String contentType, String fileName) {
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(contentType + ";charset=UTF-8"))
                .header("Content-Disposition", "inline; filename=\"" + fileName.replace("\"", "") + "\"")
                .body(content);
    }

    public <T> ResponseEntity<T> ok(String row, Class<T> type) {
        return of(HttpStatus.OK, row, type);
    }

    public <T> ResponseEntity<T> created(String row, Class<T> type) {
        return of(HttpStatus.CREATED, row, type);
    }
}
