package io.meridian.billing.application;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Small typed accessors over row / request JSON. */
final class Rows {
    private Rows() {}

    static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asString().trim();
    }

    static UUID uuid(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : UUID.fromString(s);
    }

    static BigDecimal decimal(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : new BigDecimal(s);
    }

    static BigDecimal decimalOr(JsonNode n, String f, BigDecimal dflt) {
        BigDecimal d = decimal(n, f);
        return d == null ? dflt : d;
    }

    static LocalDate date(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }

    static long longOr(JsonNode n, String f, long dflt) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? dflt : v.asLong();
    }

    static int int0(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? 0 : v.asInt();
    }

    /** Value equality tolerant of representation (numbers vs decimal strings, padded chars). */
    static boolean same(JsonNode a, JsonNode b) {
        if (a == null || a.isNull() || b == null || b.isNull()) {
            return (a == null || a.isNull()) && (b == null || b.isNull());
        }
        if (a.isValueNode() && b.isValueNode()) {
            String x = a.asString().trim();
            String y = b.asString().trim();
            if (x.equals(y)) {
                return true;
            }
            try {
                return new BigDecimal(x).compareTo(new BigDecimal(y)) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return a.equals(b);
    }

    static int parseIfMatch(String ifMatch) {
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
