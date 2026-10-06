package io.meridian.gl.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
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

    public <T> ResponseEntity<T> ok(String row, Class<T> type) {
        return of(HttpStatus.OK, row, type);
    }

    public <T> ResponseEntity<T> created(String row, Class<T> type) {
        return of(HttpStatus.CREATED, row, type);
    }
}
