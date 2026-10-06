package io.meridian.collections.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.idempotency.Idempotency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Idempotent execution of POST actions/creates and row-to-model mapping for controllers. */
@Component
class Actions {
    private final Idempotency idempotency;
    private final JsonMapper json;

    Actions(Idempotency idempotency, JsonMapper json) {
        this.idempotency = idempotency;
        this.json = json;
    }

    /** An action on {@code id}: exactly-once per Idempotency-Key, 200 with the resulting row. */
    <T> ResponseEntity<T> action(String key, UUID id, String action, Object body, Class<T> type, Supplier<JsonNode> work) {
        TenantContext.require();
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("action", action);
        if (id != null) {
            fingerprint.put("id", id.toString());
        }
        if (body != null) {
            fingerprint.put("body", tree(body));
        }
        return idempotency.execute(key, fingerprint, type, () -> ResponseEntity.ok(model(work.get(), type)));
    }

    /** A create with business rules: exactly-once per Idempotency-Key, 201 with the new row. */
    <T> ResponseEntity<T> create(String key, Object body, Class<T> type, Supplier<JsonNode> work) {
        TenantContext.require();
        return idempotency.execute(key, body, type,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(model(work.get(), type)));
    }

    /** Idempotent POST action whose response is a computed JSON object ({@code object} in the contract). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    ResponseEntity<Map<String, Object>> objectAction(String key, String action, Object body, Supplier<JsonNode> work) {
        TenantContext.require();
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("action", action);
        fingerprint.put("body", tree(body));
        ResponseEntity raw = idempotency.execute(key, fingerprint, Map.class, () -> ResponseEntity.ok(map(work.get())));
        return (ResponseEntity<Map<String, Object>>) raw;
    }

    /** 200 with a computed JSON object. */
    ResponseEntity<Map<String, Object>> object(JsonNode result) {
        return ResponseEntity.ok(map(result));
    }

    /** 200 with a printable text document offered as a download. */
    static ResponseEntity<String> text(String content, String filename) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(content);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> map(JsonNode node) {
        return json.treeToValue(node, Map.class);
    }

    /**
     * PATCH that refuses changes to fields owned by business actions (money, currency, links, status), which the
     * generated update model would otherwise let through; everything else goes through generic CRUD.
     */
    <T> ResponseEntity<T> guardedUpdate(io.meridian.web.crud.CrudEngine crud, String table, Class<T> type, UUID id,
                                        String ifMatch, Object body, java.util.Set<String> protectedFields, String hint) {
        TenantContext.require();
        JsonNode req = tree(body);
        for (String f : protectedFields) {
            if (req.hasNonNull(f)) {
                throw io.meridian.common.error.ApiException.unprocessable("field_not_updatable",
                        f + " cannot be changed with PATCH" + (hint == null ? "" : "; " + hint));
            }
        }
        return crud.update(table, type, body, ifMatch, io.meridian.web.crud.Crud.params().with("id", id));
    }

    <T> T model(JsonNode row, Class<T> type) {
        return json.treeToValue(row, type);
    }

    JsonNode tree(Object body) {
        return body == null ? json.createObjectNode() : json.valueToTree(body);
    }

    JsonMapper json() {
        return json;
    }
}
