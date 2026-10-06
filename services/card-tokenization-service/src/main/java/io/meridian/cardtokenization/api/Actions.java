package io.meridian.cardtokenization.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.idempotency.Idempotency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Idempotent execution of POST actions, public callback tenant binding and row-to-model mapping. */
@Component
class Actions {
    private final Idempotency idempotency;
    private final JsonMapper json;

    Actions(Idempotency idempotency, JsonMapper json) {
        this.idempotency = idempotency;
        this.json = json;
    }

    /** An action: exactly-once per Idempotency-Key, 200 with the resulting row mapped to {@code type}. */
    <T> ResponseEntity<T> action(String key, UUID id, String action, Object body, Class<T> type, Supplier<JsonNode> work) {
        TenantContext.require();
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("action", action);
        if (id != null) {
            fingerprint.put("id", id.toString());
        }
        fingerprint.put("body", tree(body));
        return idempotency.execute(key, fingerprint, type, () -> ResponseEntity.ok(model(work.get(), type)));
    }

    /** Runs a verified public callback as the tenant named in the callback URL. */
    static <T> T asTenant(UUID tenant, String principal, Callable<T> work) {
        try {
            return TenantContext.callAs(tenant, principal, work);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static ApiException invalidSignature() {
        return new ApiException(401, "invalid_signature", "X-Signature is missing or does not match the request body");
    }

    <T> T model(JsonNode row, Class<T> type) {
        return json.treeToValue(row, type);
    }

    JsonNode tree(Object body) {
        return body == null ? json.createObjectNode() : json.valueToTree(body);
    }
}
