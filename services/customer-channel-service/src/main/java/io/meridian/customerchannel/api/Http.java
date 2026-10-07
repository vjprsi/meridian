package io.meridian.customerchannel.api;

import io.meridian.web.client.ServiceClient;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Mapping between domain JSON and the generated models, and file responses. */
@Component
class Http {
    private final JsonMapper json;

    Http(JsonMapper json) {
        this.json = json;
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

    static ResponseEntity<Resource> binary(ServiceClient.Download d, String fallbackName) {
        return headers(d, fallbackName).body(new ByteArrayResource(d.content()));
    }

    static ResponseEntity<String> text(ServiceClient.Download d, String fallbackName) {
        MediaType type = MediaType.parseMediaType(d.contentType());
        Charset charset = type.getCharset() == null ? StandardCharsets.UTF_8 : type.getCharset();
        return headers(d, fallbackName).body(new String(d.content(), charset));
    }

    private static ResponseEntity.BodyBuilder headers(ServiceClient.Download d, String fallbackName) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(d.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, d.contentDisposition() != null ? d.contentDisposition()
                        : "attachment; filename=\"" + fallbackName + "\"");
    }
}
