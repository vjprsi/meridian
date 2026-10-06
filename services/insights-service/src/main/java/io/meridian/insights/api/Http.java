package io.meridian.insights.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.insights.infrastructure.Rows;
import io.meridian.web.idempotency.Idempotency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exactly-once POST handling for actions. The stored fingerprint covers the operation, the path id and the body, so
 * an Idempotency-Key reused for another resource is rejected instead of replaying the wrong answer.
 */
@Component
class Http {
    private final Idempotency idempotency;
    private final Rows rows;
    private final JsonMapper json;

    Http(Idempotency idempotency, Rows rows, JsonMapper json) {
        this.idempotency = idempotency;
        this.rows = rows;
        this.json = json;
    }

    <T> ResponseEntity<T> ok(String key, String op, Object id, Object body, Class<T> type, Supplier<ObjectNode> action) {
        return respond(key, op, id, body, type, HttpStatus.OK, action);
    }

    <T> ResponseEntity<T> created(String key, String op, Object body, Class<T> type, Supplier<ObjectNode> action) {
        return respond(key, op, null, body, type, HttpStatus.CREATED, action);
    }

    private <T> ResponseEntity<T> respond(String key, String op, Object id, Object body, Class<T> type, HttpStatus status,
                                          Supplier<ObjectNode> action) {
        TenantContext.require();
        return idempotency.execute(key, fingerprint(op, id, body), type, () -> {
            ObjectNode row = action.get();
            ResponseEntity.BodyBuilder b = ResponseEntity.status(status);
            if (row.hasNonNull("row_version")) {
                b.eTag("\"" + row.get("row_version").asString() + "\"");
            }
            return b.body(rows.read(row, type));
        });
    }

    /** Actions whose answer is a computed JSON document. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    ResponseEntity<Map<String, Object>> document(String key, String op, Object id, Object body, Supplier<JsonNode> action) {
        TenantContext.require();
        ResponseEntity<Map> r = idempotency.execute(key, fingerprint(op, id, body), Map.class,
                () -> ResponseEntity.ok(json.convertValue(action.get(), Map.class)));
        return (ResponseEntity<Map<String, Object>>) (ResponseEntity) r;
    }

    /** Read-only computed documents (GET). */
    @SuppressWarnings("unchecked")
    ResponseEntity<Map<String, Object>> query(JsonNode doc) {
        return ResponseEntity.ok(json.convertValue(doc, Map.class));
    }

    <T> ResponseEntity<T> withEtag(ObjectNode row, Class<T> type) {
        return ResponseEntity.ok().eTag("\"" + row.get("row_version").asString() + "\"").body(rows.read(row, type));
    }

    <T> T read(JsonNode node, Class<T> type) {
        return rows.read(node, type);
    }

    JsonNode tree(Object body) {
        return body == null ? json.createObjectNode() : json.valueToTree(body);
    }

    private static Map<String, Object> fingerprint(String op, Object id, Object body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        m.put("id", id == null ? null : id.toString());
        m.put("body", body);
        return m;
    }
}
