package io.meridian.businessday.api;

import io.meridian.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;

/** Controller helpers shared by this service's API classes. */
final class Http {
    private Http() {}

    /**
     * A JSON body for operations the contract declares without a response schema ({@code ResponseEntity<Void>}).
     * The body is written as pre-serialised JSON text, which Spring emits verbatim with the JSON content type.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static ResponseEntity<Void> json(HttpStatusCode status, JsonNode body) {
        return (ResponseEntity) ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body.toString());
    }

    static ResponseEntity<Void> ok(JsonNode body) {
        return json(HttpStatus.OK, body);
    }

    /** Re-wraps an idempotent replay (stored as JSON) as a JSON-text response, keeping status and headers. */
    static ResponseEntity<Void> json(ResponseEntity<JsonNode> r) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ResponseEntity<Void> out = (ResponseEntity) ResponseEntity.status(r.getStatusCode()).headers(h -> {
            h.addAll(r.getHeaders());
            h.setContentType(MediaType.APPLICATION_JSON);
        }).body(r.getBody() == null ? null : r.getBody().toString());
        return out;
    }

    /** A query parameter the contract does not declare (e.g. {@code ?cycle_type=} on the graph). */
    static String query(String name) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            String v = a.getRequest().getParameter(name);
            return v == null || v.isBlank() ? null : v.trim();
        }
        return null;
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
