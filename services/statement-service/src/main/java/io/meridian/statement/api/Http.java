package io.meridian.statement.api;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Response and request-body helpers shared by the statement controllers. */
final class Http {
    private Http() {}

    /**
     * 200 with a generated file. Every rendered format is text (the PDF is 7-bit ASCII), so the content travels as a
     * UTF-8 string; text types without a charset get an explicit one so the bytes (and the checksum) never change.
     */
    static ResponseEntity<String> file(byte[] content, String contentType, String filename, String sha256) {
        MediaType type = MediaType.parseMediaType(contentType);
        if (type.getCharset() == null && !"pdf".equals(type.getSubtype())) {
            type = new MediaType(type, StandardCharsets.UTF_8);
        }
        Charset charset = type.getCharset() == null ? StandardCharsets.US_ASCII : type.getCharset();
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        if (sha256 != null) {
            b.header("X-Content-SHA256", sha256);
        }
        return b.body(new String(content, charset));
    }

    @SuppressWarnings("unchecked")
    static ResponseEntity<Map<String, Object>> object(JsonMapper json, JsonNode result) {
        return ResponseEntity.ok(json.treeToValue(result, Map.class));
    }

    static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }
}
