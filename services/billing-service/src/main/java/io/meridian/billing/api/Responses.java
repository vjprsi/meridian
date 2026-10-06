package io.meridian.billing.api;

import io.meridian.common.error.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Response and header helpers shared by the billing controllers. */
final class Responses {
    private Responses() {}

    /**
     * The contract declares some data-returning operations as 204 No Content ({@code ResponseEntity<Void>}); they
     * return their JSON body with a real status instead (spec defect).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static ResponseEntity<Void> untyped(ResponseEntity<?> r) {
        return (ResponseEntity) r;
    }

    static <T> ResponseEntity<T> withEtag(HttpStatus status, T body, Integer rowVersion) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status);
        if (rowVersion != null) {
            b.eTag("\"" + rowVersion + "\"");
        }
        return b.body(body);
    }

    /** Ordered request fingerprint for idempotency hashing (Map.of iteration order varies between JVM runs). */
    static Map<String, Object> fingerprint(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    static int version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.badRequest("if_match_required", "If-Match header with the current row_version is required");
        }
        try {
            return Integer.parseInt(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_if_match", "If-Match must be the resource's row_version");
        }
    }
}
