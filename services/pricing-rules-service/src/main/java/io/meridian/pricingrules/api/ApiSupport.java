package io.meridian.pricingrules.api;

import io.meridian.common.error.ApiException;
import io.meridian.web.RequestIdFilter;
import io.meridian.web.idempotency.Idempotency;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small helpers shared by the controllers. */
final class ApiSupport {
    private ApiSupport() {}

    /** Runs an idempotent action whose response is a computed JSON object (contract type {@code object}). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static ResponseEntity<Map<String, Object>> result(Idempotency idempotency, JsonMapper json, String key, Object request,
                                                      Supplier<JsonNode> run) {
        ResponseEntity r = idempotency.execute(key, request, Map.class,
                () -> ResponseEntity.ok(json.convertValue(run.get(), Map.class)));
        return r;
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
