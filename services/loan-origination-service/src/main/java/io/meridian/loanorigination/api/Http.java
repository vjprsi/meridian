package io.meridian.loanorigination.api;

import java.math.BigDecimal;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Field access on request bodies converted to JSON (snake_case names, decoupled from generated getter names). */
final class Http {
    private Http() {}

    static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    static BigDecimal decimal(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : new BigDecimal(s);
    }

    static UUID uuid(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : UUID.fromString(s);
    }
}
