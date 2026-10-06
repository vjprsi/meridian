package io.meridian.inbox.application;

import io.meridian.common.error.ApiException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Null-safe readers for snake_case request bodies and row JSON. */
public final class Json {
    private Json() {}

    public static String text(JsonNode n, String field) {
        if (n == null) {
            return null;
        }
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    public static String required(JsonNode n, String field) {
        String v = text(n, field);
        if (v == null || v.isBlank()) {
            throw ApiException.badRequest("validation_failed", field + " is required");
        }
        return v.trim();
    }

    public static UUID uuid(JsonNode n, String field) {
        String v = text(n, field);
        return v == null ? null : UUID.fromString(v);
    }

    public static OffsetDateTime ts(JsonNode n, String field) {
        String v = text(n, field);
        return v == null ? null : OffsetDateTime.parse(v);
    }

    public static boolean bool(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v != null && !v.isNull() && v.asBoolean();
    }

    public static int integer(JsonNode n, String field, int dflt) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? dflt : v.asInt();
    }

    /** String items of a JSON array column (non-arrays give an empty list). */
    public static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(v -> {
                if (!v.isNull()) {
                    out.add(v.asString());
                }
            });
        }
        return out;
    }

    public static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    public static int parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.badRequest("if_match_required", "If-Match header with the current row_version is required");
        }
        try {
            return Integer.parseInt(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_if_match", "If-Match must be the resource's row_version");
        }
    }

    public static ApiException versionConflict(int expected) {
        return ApiException.conflict("version_conflict",
                "If-Match " + expected + " does not match the current version; reload and retry");
    }
}
