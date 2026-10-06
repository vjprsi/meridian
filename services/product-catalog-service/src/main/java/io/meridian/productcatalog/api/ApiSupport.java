package io.meridian.productcatalog.api;

import io.meridian.common.error.ApiException;
import io.meridian.web.idempotency.Idempotency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Helpers shared by the controllers. */
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

    /** Idempotency hash input that includes the operation and target, not just the body. */
    static Map<String, Object> idem(JsonMapper json, String op, UUID id, Object body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        m.put("id", id == null ? "" : id.toString());
        if (body != null) {
            m.put("body", body);
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
}
