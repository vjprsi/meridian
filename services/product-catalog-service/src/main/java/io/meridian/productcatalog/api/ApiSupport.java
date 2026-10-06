package io.meridian.productcatalog.api;

import io.meridian.common.error.ApiException;
import io.meridian.productcatalog.infrastructure.CatalogStore;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Helpers shared by the controllers. */
final class ApiSupport {
    private ApiSupport() {}

    /**
     * Several result-returning operations are declared 204/no-content ({@code ResponseEntity<Void>}) by the
     * contract; the computed document is still returned, as text with an explicit content type.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static ResponseEntity<Void> document(MediaType type, String body) {
        return (ResponseEntity) ResponseEntity.status(HttpStatus.OK).contentType(type).body(body);
    }

    /** Converts an idempotent String-bodied JSON result to the contract's Void signature. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static ResponseEntity<Void> asVoid(ResponseEntity<String> r) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(r.getStatusCode()).contentType(MediaType.APPLICATION_JSON);
        String replayed = r.getHeaders().getFirst("Idempotent-Replayed");
        if (replayed != null) {
            b.header("Idempotent-Replayed", replayed);
        }
        return (ResponseEntity) b.body(r.getBody());
    }

    /** Idempotency hash input that includes the operation and target, not just the body. */
    static Map<String, Object> idem(JsonMapper json, String op, UUID id, Object body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        m.put("id", id == null ? "" : id.toString());
        if (body != null) {
            m.put("body", body);
        }
        Map<String, Object> extras = rawBodyExtras(json);
        if (!extras.isEmpty()) {
            m.put("extras", extras);
        }
        return m;
    }

    static <T> ResponseEntity<T> withEtag(HttpStatus status, T body, JsonNode row) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status);
        if (row.hasNonNull("row_version")) {
            b.eTag("\"" + row.get("row_version").asString() + "\"");
        }
        return b.body(body);
    }

    static Integer ifMatch(String ifMatch) {
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

    static HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest() : null;
    }

    static String param(String name) {
        HttpServletRequest r = request();
        String v = r == null ? null : r.getParameter(name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** All query parameters except paging ones, as a profile/context map (single values). */
    static Map<String, Object> queryParams() {
        Map<String, Object> m = new LinkedHashMap<>();
        HttpServletRequest r = request();
        if (r != null && r.getQueryString() != null) {
            r.getParameterMap().forEach((k, v) -> {
                if (v.length > 0 && !k.equals("limit") && !k.equals("cursor")) {
                    m.put(k, k.equals("existing_products") ? java.util.Arrays.asList(v[0].split("\\s*,\\s*")) : v[0]);
                }
            });
        }
        return m;
    }

    /** Fields of the raw JSON body that the generated model does not declare (e.g. applicant, context). */
    static Map<String, Object> rawBodyExtras(JsonMapper json) {
        HttpServletRequest r = request();
        ContentCachingRequestWrapper w = r == null ? null : WebUtils.getNativeRequest(r, ContentCachingRequestWrapper.class);
        if (w == null) {
            return Map.of();
        }
        byte[] bytes = w.getContentAsByteArray();
        if (bytes.length == 0) {
            return Map.of();
        }
        try {
            return CatalogStore.toMap(json.readTree(new String(bytes, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
