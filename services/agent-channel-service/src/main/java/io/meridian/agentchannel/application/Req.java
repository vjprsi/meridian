package io.meridian.agentchannel.application;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Reading fields of a request body (already shape-validated by the generated model). */
final class Req {
    private Req() {}

    static String text(JsonNode req, String field) {
        JsonNode v = req.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    static String required(JsonNode req, String field) {
        String v = text(req, field);
        if (v == null || v.isBlank()) {
            throw ApiException.unprocessable("missing_field", field + " is required");
        }
        return v.trim();
    }

    static UUID uuid(JsonNode req, String field, boolean required) {
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

    /** A positive amount with at most 2 decimals, as plain text. */
    static String amount(JsonNode req, String field) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(required(req, field));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_value", field + " must be a decimal number");
        }
        if (amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", field + " must be greater than zero");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw ApiException.unprocessable("invalid_amount", field + " has at most 2 decimals");
        }
        return amount.toPlainString();
    }

    /** Copies a JSON value (object, array) when present. */
    static void copy(tools.jackson.databind.node.ObjectNode to, JsonNode from, String field) {
        JsonNode v = from.get(field);
        if (v != null && !v.isNull()) {
            to.set(field, v);
        }
    }
}
