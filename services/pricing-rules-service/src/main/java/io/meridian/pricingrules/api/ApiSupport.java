package io.meridian.pricingrules.api;

import io.meridian.common.error.ApiException;
import io.meridian.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;

/** Small helpers shared by the controllers. */
final class ApiSupport {
    private ApiSupport() {}

    /**
     * The contract declares several result-returning operations as 204/no-content ({@code ResponseEntity<Void>}).
     * We still return the computed document: JSON (or XML) text with an explicit content type.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static ResponseEntity<Void> document(HttpStatus status, MediaType type, String body) {
        return (ResponseEntity) ResponseEntity.status(status).contentType(type).body(body);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static ResponseEntity<Void> asVoid(ResponseEntity<String> r) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(r.getStatusCode()).contentType(MediaType.APPLICATION_JSON);
        String replayed = r.getHeaders().getFirst("Idempotent-Replayed");
        if (replayed != null) {
            b.header("Idempotent-Replayed", replayed);
        }
        return (ResponseEntity) b.body(r.getBody());
    }

    /** A request descriptor for idempotency hashing that includes the operation and target id. */
    static Map<String, Object> idemRequest(String op, UUID id, Object body) {
        return body == null ? Map.of("op", op, "id", id == null ? "" : id.toString())
                : Map.of("op", op, "id", id == null ? "" : id.toString(), "body", body);
    }

    static String header(String name) {
        HttpServletRequest req = request();
        return req == null ? null : req.getHeader(name);
    }

    static String correlationId() {
        String c = header("X-Correlation-Id");
        if (c != null && !c.isBlank()) {
            return c;
        }
        String tp = header("traceparent");
        if (tp != null && tp.split("-").length >= 2) {
            return tp.split("-")[1];
        }
        HttpServletRequest req = request();
        Object rid = req == null ? null : req.getAttribute(RequestIdFilter.ATTRIBUTE);
        return rid == null ? header(RequestIdFilter.HEADER) : rid.toString();
    }

    static HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest() : null;
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

    static <T> ResponseEntity<T> withEtag(HttpStatus status, T body, JsonNode row) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status);
        if (row.hasNonNull("row_version")) {
            b.eTag("\"" + row.get("row_version").asString() + "\"");
        }
        return b.body(body);
    }
}
