package io.meridian.customer.application;

import io.meridian.common.error.ApiException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Null-safe readers for snake_case request bodies and row JSON. */
final class Json {
    private Json() {}

    static String text(JsonNode n, String field) {
        if (n == null) {
            return null;
        }
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    static String required(JsonNode n, String field) {
        String v = text(n, field);
        if (v == null || v.isBlank()) {
            throw ApiException.badRequest("validation_failed", field + " is required");
        }
        return v.trim();
    }

    static UUID uuid(JsonNode n, String field) {
        String v = text(n, field);
        return v == null ? null : UUID.fromString(v);
    }

    static LocalDate date(JsonNode n, String field) {
        String v = text(n, field);
        return v == null ? null : LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
    }

    static OffsetDateTime timestamp(JsonNode n, String field) {
        String v = text(n, field);
        return v == null ? null : OffsetDateTime.parse(v);
    }

    static boolean bool(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v != null && !v.isNull() && v.asBoolean();
    }

    static OffsetDateTime now() {
        return io.meridian.common.time.Clocks.now();
    }

    static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
