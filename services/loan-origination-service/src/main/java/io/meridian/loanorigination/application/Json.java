package io.meridian.loanorigination.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Field access on request bodies converted to JSON (snake_case names) and JSON-to-plain-Java conversion. */
public final class Json {
    private Json() {}

    public static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    public static BigDecimal decimal(JsonNode n, String f) {
        String s = text(n, f);
        return s == null || s.isBlank() ? null : new BigDecimal(s);
    }

    public static UUID uuid(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : UUID.fromString(s);
    }

    public static Integer integer(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? null : v.asInt();
    }

    /** JSON value as plain Java (Map, List, String, BigDecimal, Boolean, null). */
    public static Object plain(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.isObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            n.propertyNames().forEach(k -> m.put(k, plain(n.get(k))));
            return m;
        }
        if (n.isArray()) {
            List<Object> l = new ArrayList<>();
            n.forEach(e -> l.add(plain(e)));
            return l;
        }
        if (n.isNumber()) {
            return n.decimalValue();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        return n.asString();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(JsonNode n) {
        Object o = plain(n);
        return o instanceof List<?> l ? (List<Object>) l : null;
    }
}
