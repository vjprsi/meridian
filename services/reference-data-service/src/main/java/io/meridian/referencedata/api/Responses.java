package io.meridian.referencedata.api;

import io.meridian.common.error.ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Response helpers for contract operations the generator typed as {@code ResponseEntity<Void>}. */
final class Responses {
    private Responses() {}

    /**
     * Contract defect workaround: actions documented with response "none" are generated as
     * {@code ResponseEntity<Void>} although they return a JSON result. Generics are erased, so the body is
     * still serialised by Spring MVC.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static ResponseEntity<Void> body(ResponseEntity<?> r) {
        return (ResponseEntity) r;
    }

    static ResponseEntity<Void> ok(JsonNode node) {
        return body(ResponseEntity.ok(node));
    }

    static LocalDate date(String value, String param) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("missing_parameter", "Query parameter '" + param + "' (YYYY-MM-DD) is required");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("invalid_parameter", "Query parameter '" + param + "' must be YYYY-MM-DD");
        }
    }

    static int integer(String value, String param, int dflt) {
        if (value == null || value.isBlank()) {
            return dflt;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_parameter", "Query parameter '" + param + "' must be an integer");
        }
    }
}
