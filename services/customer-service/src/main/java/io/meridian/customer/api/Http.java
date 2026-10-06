package io.meridian.customer.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.customer.infrastructure.Rows;
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
 * Exactly-once POST/DELETE handling for actions. The fingerprint of a request is the operation, the path ids and
 * the body, so reusing an Idempotency-Key for another resource is rejected instead of replaying the wrong answer.
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

    <T> ResponseEntity<T> created(String key, String op, Object id, Object body, Class<T> type, Supplier<ObjectNode> action) {
        return respond(key, op, id, body, type, HttpStatus.CREATED, action);
    }

    <T> ResponseEntity<T> respond(String key, String op, Object id, Object body, Class<T> type, HttpStatus status,
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

    /** Full control over the response (status decided by the use case). */
    <T> ResponseEntity<T> custom(String key, String op, Object id, Object body, Class<T> type,
                                 Supplier<ResponseEntity<T>> action) {
        TenantContext.require();
        return idempotency.execute(key, fingerprint(op, id, body), type, action);
    }

    <T> T read(ObjectNode row, Class<T> type) {
        return rows.read(row, type);
    }

    /** For POST actions whose answer is a computed JSON document (contract response type {@code object}). */
    @SuppressWarnings("unchecked")
    ResponseEntity<Map<String, Object>> document(String key, String op, Object id, Object body, HttpStatus status,
                                                 Supplier<JsonNode> action) {
        TenantContext.require();
        return idempotency.execute(key, fingerprint(op, id, body), (Class<Map<String, Object>>) (Class<?>) Map.class,
                () -> ResponseEntity.status(status).body(map(action.get())));
    }

    /** A computed JSON document as the generated {@code object} response type. */
    @SuppressWarnings("unchecked")
    Map<String, Object> map(JsonNode node) {
        return json.treeToValue(node, Map.class);
    }

    ResponseEntity<Void> noContent(String key, String op, Object id, Object body, Runnable action) {
        TenantContext.require();
        return idempotency.execute(key, fingerprint(op, id, body), Void.class, () -> {
            action.run();
            return ResponseEntity.noContent().build();
        });
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
