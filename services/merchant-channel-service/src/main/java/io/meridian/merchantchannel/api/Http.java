package io.meridian.merchantchannel.api;

import io.meridian.web.client.ServiceClient;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mapping between domain JSON and the generated models, idempotent writes on the channel's own tables and file
 * responses. Writes that only call a downstream service are idempotent through the derived downstream key; writes on
 * the channel's own tables (users, sales, refund requests, payout changes) go through {@link Idempotency}, whose
 * fingerprint covers method, path, caller and body (a key reused by another user or for another object is refused).
 */
@Component
class Http {
    private final JsonMapper json;
    private final Idempotency idempotency;

    Http(JsonMapper json, Idempotency idempotency) {
        this.json = json;
        this.idempotency = idempotency;
    }

    <T> ResponseEntity<T> ok(JsonNode node, Class<T> type) {
        return ResponseEntity.ok(json.treeToValue(node, type));
    }

    @SuppressWarnings("unchecked")
    ResponseEntity<Map<String, Object>> object(JsonNode node) {
        return ResponseEntity.ok(json.treeToValue(node, Map.class));
    }

    /** The request model as JSON (snake_case fields); an empty object when there is no body. */
    JsonNode body(Object model) {
        return model == null ? json.createObjectNode() : json.valueToTree(model);
    }

    /** A write on the channel's own tables, replayed for a repeated Idempotency-Key. */
    <T> ResponseEntity<T> once(String key, Object request, Class<T> type, Supplier<JsonNode> action) {
        return idempotency.execute(key, request == null ? Map.of() : request, type,
                () -> ResponseEntity.ok(json.treeToValue(action.get(), type)));
    }

    static ResponseEntity<Resource> binary(ServiceClient.Download d, String fallbackName) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(d.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, d.contentDisposition() != null ? d.contentDisposition()
                        : "attachment; filename=\"" + fallbackName + "\"")
                .body(new ByteArrayResource(d.content()));
    }

    static ResponseEntity<String> file(String content, String contentType, String fileName) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName.replaceAll("[^A-Za-z0-9._-]", "_")
                        + "\"")
                .body(content);
    }
}
