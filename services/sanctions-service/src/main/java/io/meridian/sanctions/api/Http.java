package io.meridian.sanctions.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Controller helpers: request bodies as JSON trees, typed responses with ETag, idempotent actions. */
@Component
class Http {
    private final JsonMapper json;
    private final Idempotency idempotency;

    Http(JsonMapper json, Idempotency idempotency) {
        this.json = json;
        this.idempotency = idempotency;
    }

    JsonNode tree(Object body) {
        return body == null ? json.createObjectNode() : json.valueToTree(body);
    }

    <T> ResponseEntity<T> ok(String row, Class<T> type) {
        return respond(HttpStatus.OK, row, type);
    }

    <T> ResponseEntity<T> respond(HttpStatusCode status, String row, Class<T> type) {
        JsonNode n = json.readTree(row);
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status);
        if (n.hasNonNull("row_version")) {
            b.eTag("\"" + n.get("row_version").asString() + "\"");
        }
        return b.body(json.readValue(row, type));
    }

    <T> T read(JsonNode n, Class<T> type) {
        return json.convertValue(n, type);
    }

    /** Runs a state-changing action exactly once per Idempotency-Key (replays return the stored response). */
    <T> ResponseEntity<T> once(String key, Object body, Class<T> type, Supplier<ResponseEntity<T>> action) {
        TenantContext.require();
        return idempotency.execute(key, body == null ? Map.of() : body, type, action);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    ResponseEntity<Map<String, Object>> onceMap(String key, Object body, Supplier<Map<String, Object>> action) {
        TenantContext.require();
        return (ResponseEntity) idempotency.execute(key, body == null ? Map.of() : body, Map.class,
                () -> (ResponseEntity) ResponseEntity.ok(action.get()));
    }

    static int ifMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.badRequest("if_match_required", "If-Match header with the current row_version is required");
        }
        try {
            return Integer.parseInt(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_if_match", "If-Match must be the resource's row_version");
        }
    }

    static String text(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) ? n.get(f).asString() : null;
    }
}
