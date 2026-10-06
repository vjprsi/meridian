package io.meridian.liquidity.application;

import io.meridian.common.error.ApiException;
import io.meridian.liquidity.domain.DomainException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/**
 * Typed reads of JSON request items (list entries of json[] fields) with stable 422 codes, and translation of
 * domain rule violations to problem responses.
 */
final class Inputs {
    private Inputs() {}

    static <T> T domain(Supplier<T> body) {
        try {
            return body.get();
        } catch (DomainException e) {
            throw ApiException.unprocessable(e.code(), e.getMessage());
        }
    }

    static List<JsonNode> list(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        List<JsonNode> out = new ArrayList<>();
        if (v == null || v.isNull()) {
            return out;
        }
        if (!v.isArray()) {
            throw ApiException.unprocessable("invalid_" + field, field + " must be an array");
        }
        v.forEach(out::add);
        return out;
    }

    static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.isString() ? v.asString() : v.toString();
        return s.isBlank() ? null : s.trim();
    }

    static String requiredText(JsonNode n, String f, String ctx) {
        String s = text(n, f);
        if (s == null) {
            throw ApiException.unprocessable("missing_field", ctx + ": " + f + " is required");
        }
        return s;
    }

    static BigDecimal decimal(JsonNode n, String f, String ctx, boolean required) {
        JsonNode v = n == null ? null : n.get(f);
        if (v == null || v.isNull()) {
            if (required) {
                throw ApiException.unprocessable("missing_field", ctx + ": " + f + " is required");
            }
            return null;
        }
        try {
            return v.isNumber() ? v.decimalValue() : new BigDecimal(v.asString().trim());
        } catch (NumberFormatException e) {
            throw ApiException.unprocessable("invalid_" + f, ctx + ": " + f + " must be a decimal number");
        }
    }

    static Integer integer(JsonNode n, String f, String ctx) {
        BigDecimal d = decimal(n, f, ctx, false);
        if (d == null) {
            return null;
        }
        if (d.stripTrailingZeros().scale() > 0) {
            throw ApiException.unprocessable("invalid_" + f, ctx + ": " + f + " must be a whole number");
        }
        return d.intValueExact();
    }

    static LocalDate date(JsonNode n, String f, String ctx) {
        String s = text(n, f);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            throw ApiException.unprocessable("invalid_" + f, ctx + ": " + f + " must be an ISO date (YYYY-MM-DD)");
        }
    }

    static OffsetDateTime timestamp(JsonNode n, String f, String ctx) {
        String s = text(n, f);
        if (s == null) {
            throw ApiException.unprocessable("missing_field", ctx + ": " + f + " is required");
        }
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException e) {
            throw ApiException.unprocessable("invalid_" + f, ctx + ": " + f + " must be an ISO date-time with offset");
        }
    }

    static UUID uuid(JsonNode n, String f, String ctx) {
        String s = text(n, f);
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_" + f, ctx + ": " + f + " must be a UUID");
        }
    }

    static String currency(JsonNode n, String f, String ctx, boolean required) {
        String s = required ? requiredText(n, f, ctx) : text(n, f);
        if (s != null && !s.matches("[A-Z]{3}")) {
            throw ApiException.unprocessable("invalid_currency", ctx + ": " + f + " must be an ISO 4217 code");
        }
        return s;
    }
}
