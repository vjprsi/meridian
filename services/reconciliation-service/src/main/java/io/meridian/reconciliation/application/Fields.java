package io.meridian.reconciliation.application;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Typed, validated reads of snake_case request / row fields; failures become 422 validation_failed. */
public final class Fields {
    private Fields() {}

    public static String text(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) ? n.get(f).asString() : null;
    }

    public static String requireText(JsonNode n, String f) {
        String v = text(n, f);
        if (v == null || v.isBlank()) {
            throw invalid(f, "required", f + " is required");
        }
        return v;
    }

    public static UUID uuid(JsonNode n, String f) {
        String v = text(n, f);
        if (v == null) {
            return null;
        }
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            throw invalid(f, "invalid_uuid", f + " must be a UUID");
        }
    }

    public static UUID requireUuid(JsonNode n, String f) {
        UUID v = uuid(n, f);
        if (v == null) {
            throw invalid(f, "required", f + " is required");
        }
        return v;
    }

    public static LocalDate date(JsonNode n, String f) {
        String v = text(n, f);
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw invalid(f, "invalid_date", f + " must be an ISO date");
        }
    }

    public static LocalDate requireDate(JsonNode n, String f) {
        LocalDate v = date(n, f);
        if (v == null) {
            throw invalid(f, "required", f + " is required");
        }
        return v;
    }

    public static BigDecimal decimal(JsonNode n, String f) {
        String v = text(n, f);
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            throw invalid(f, "invalid_decimal", f + " must be a decimal number");
        }
    }

    public static BigDecimal requireDecimal(JsonNode n, String f) {
        BigDecimal v = decimal(n, f);
        if (v == null) {
            throw invalid(f, "required", f + " is required");
        }
        return v;
    }

    public static <E extends Enum<E>> E enumValue(JsonNode n, String f, Class<E> type) {
        String v = text(n, f);
        if (v == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, v);
        } catch (IllegalArgumentException e) {
            throw invalid(f, "invalid_enum", f + " has an unsupported value " + v);
        }
    }

    public static ApiException invalid(String field, String code, String message) {
        return new ApiException(422, "validation_failed", message,
                List.of(new ApiException.FieldError(field, code, message)));
    }

    /** Parses an If-Match header carrying the row_version. */
    public static int ifMatch(String header) {
        if (header == null || header.isBlank()) {
            throw ApiException.badRequest("if_match_required", "If-Match header with the current row_version is required");
        }
        try {
            return Integer.parseInt(header.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_if_match", "If-Match must be the resource's row_version");
        }
    }
}
