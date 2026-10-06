package io.meridian.search.api;

import io.meridian.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Controller helpers shared by this service's API classes. */
final class Http {
    private Http() {}

    /** JSON body for operations the contract declares without a response schema ({@code ResponseEntity<Void>}). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static ResponseEntity<Void> json(HttpStatusCode status, JsonNode body) {
        return (ResponseEntity) ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body.toString());
    }

    static ResponseEntity<Void> ok(JsonNode body) {
        return json(HttpStatus.OK, body);
    }

    /** Re-wraps an idempotent (possibly replayed) JSON response as JSON text, keeping status and headers. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static ResponseEntity<Void> json(ResponseEntity<JsonNode> r) {
        return (ResponseEntity) ResponseEntity.status(r.getStatusCode()).headers(h -> {
            h.addAll(r.getHeaders());
            h.setContentType(MediaType.APPLICATION_JSON);
        }).body(r.getBody() == null ? null : r.getBody().toString());
    }

    /** The full JSON request body as sent (when the request was wrapped in a ContentCachingRequestWrapper); an empty object when there is none. */
    static JsonNode rawBody(JsonMapper json) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            HttpServletRequest req = a.getRequest();
            ContentCachingRequestWrapper w = WebUtils.getNativeRequest(req, ContentCachingRequestWrapper.class);
            if (w != null && w.getContentAsByteArray().length > 0) {
                return json.readTree(w.getContentAsByteArray());
            }
        }
        return json.createObjectNode();
    }

    static String query(String name) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            String v = a.getRequest().getParameter(name);
            return v == null || v.isBlank() ? null : v.trim();
        }
        return null;
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
