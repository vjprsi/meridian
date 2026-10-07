package io.meridian.businesschannel.application;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Reading fields of a request body or a stored row (already shape-validated by the generated model). */
public final class Req {
    private Req() {}

    public static String text(JsonNode req, String field) {
        JsonNode v = req == null ? null : req.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    public static String required(JsonNode req, String field) {
        String v = text(req, field);
        if (v == null || v.isBlank()) {
            throw ApiException.unprocessable("missing_field", field + " is required");
        }
        return v.trim();
    }

    public static UUID uuid(JsonNode req, String field, boolean required) {
        String v = text(req, field);
        if (v == null || v.isBlank()) {
            if (required) {
                throw ApiException.unprocessable("missing_field", field + " is required");
            }
            return null;
        }
        try {
            return UUID.fromString(v.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_value", field + " must be a UUID");
        }
    }

    /** A positive amount with at most 2 decimals. */
    public static BigDecimal amount(JsonNode req, String field) {
        BigDecimal amount = decimal(req, field);
        if (amount == null) {
            throw ApiException.unprocessable("missing_field", field + " is required");
        }
        if (amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", field + " must be greater than zero");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw ApiException.unprocessable("invalid_amount", field + " has at most 2 decimals");
        }
        return amount;
    }

    /** An optional non-negative decimal (null when absent). */
    public static BigDecimal decimal(JsonNode req, String field) {
        String v = text(req, field);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            BigDecimal d = new BigDecimal(v.trim());
            if (d.signum() < 0) {
                throw ApiException.unprocessable("invalid_amount", field + " must not be negative");
            }
            return d;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_value", field + " must be a decimal number");
        }
    }

    public static LocalDate date(JsonNode req, String field) {
        String v = text(req, field);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(v.trim().length() > 10 ? v.trim().substring(0, 10) : v.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("invalid_value", field + " must be a date (yyyy-mm-dd)");
        }
    }

    public static Integer integer(JsonNode req, String field) {
        JsonNode v = req == null ? null : req.get(field);
        return v == null || v.isNull() ? null : v.asInt();
    }

    public static boolean bool(JsonNode req, String field, boolean fallback) {
        JsonNode v = req == null ? null : req.get(field);
        return v == null || v.isNull() ? fallback : v.asBoolean(fallback);
    }

    /** Text of a stored row field as UUID (null when absent). */
    public static UUID rowUuid(JsonNode row, String field) {
        String v = text(row, field);
        return v == null || v.isBlank() ? null : UUID.fromString(v);
    }

    /** Decimal of a stored row field (numbers or strings). */
    public static BigDecimal rowDecimal(JsonNode row, String field) {
        JsonNode v = row == null ? null : row.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.isNumber() ? v.decimalValue() : new BigDecimal(v.asString());
    }
}
