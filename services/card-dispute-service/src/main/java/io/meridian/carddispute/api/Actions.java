package io.meridian.carddispute.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.idempotency.Idempotency;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Idempotent execution of POST creates/actions and row-to-model mapping for controllers. */
@Component
class Actions {
    private final Idempotency idempotency;
    private final JsonMapper json;

    Actions(Idempotency idempotency, JsonMapper json) {
        this.idempotency = idempotency;
        this.json = json;
    }

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
        return idempotency.execute(key, fingerprint, type, () -> ResponseEntity.ok(json.treeToValue(work.get(), type)));
    }

    <T> ResponseEntity<T> create(String key, Object body, Class<T> type, Supplier<JsonNode> work) {
        TenantContext.require();
        return idempotency.execute(key, body, type,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(json.treeToValue(work.get(), type)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    ResponseEntity<Map<String, Object>> objectAction(String key, String action, Object input, Supplier<JsonNode> work) {
        TenantContext.require();
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("action", action);
        fingerprint.put("input", input == null ? null : input.toString());
        ResponseEntity raw = idempotency.execute(key, fingerprint, Map.class,
                () -> ResponseEntity.ok(json.treeToValue(work.get(), Map.class)));
        return (ResponseEntity<Map<String, Object>>) raw;
    }

    JsonNode tree(Object body) {
        return body == null ? json.createObjectNode() : json.valueToTree(body);
    }

    static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    static BigDecimal decimal(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : new BigDecimal(s);
    }

    static Boolean bool(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? null : v.asBoolean();
    }
}
