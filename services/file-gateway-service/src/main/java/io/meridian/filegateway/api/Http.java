package io.meridian.filegateway.api;

import io.meridian.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Controller helpers shared by this service's API classes. */
final class Http {
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private Http() {}

    /** A computed JSON result for operations the contract declares with an {@code object} response. */
    static Map<String, Object> object(JsonMapper json, JsonNode body) {
        return body == null ? null : json.convertValue(body, OBJECT);
    }

    /** Converts an idempotent (possibly replayed) JSON result, keeping status and headers. */
    static ResponseEntity<Map<String, Object>> object(JsonMapper json, ResponseEntity<JsonNode> r) {
        return ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders()).body(object(json, r.getBody()));
    }

    /** Absolute URL for a path on this service (used for pre-signed download links). */
    static String absolute(String path) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            HttpServletRequest r = a.getRequest();
            return r.getScheme() + "://" + r.getServerName() + ":" + r.getServerPort() + r.getContextPath() + path;
        }
        return path;
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

    static String required(JsonNode n, String f) {
        String v = text(n, f);
        if (v == null || v.isBlank()) {
            throw ApiException.unprocessable(f + "_required", f + " is required");
        }
        return v;
    }
}
