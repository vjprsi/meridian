package io.meridian.audit.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic JSON serialisation used for hashing: object keys sorted (recursively), no whitespace,
 * strings escaped per RFC 8259 with lower-case \\u escapes for control characters, numbers in their plain
 * decimal form. Accepts the object graph a JSON parser yields (Map, List, String, Number, Boolean, null).
 */
public final class CanonicalJson {
    private CanonicalJson() {}

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object v) {
        switch (v) {
            case null -> sb.append("null");
            case String s -> string(sb, s);
            case Boolean b -> sb.append(b);
            case BigDecimal d -> sb.append(d.stripTrailingZeros().toPlainString());
            case Double d -> sb.append(BigDecimal.valueOf(d).stripTrailingZeros().toPlainString());
            case Float f -> sb.append(new BigDecimal(f.toString()).stripTrailingZeros().toPlainString());
            case Number n -> sb.append(new BigDecimal(n.toString()).stripTrailingZeros().toPlainString());
            case Map<?, ?> m -> {
                TreeMap<String, Object> sorted = new TreeMap<>();
                m.forEach((k, val) -> sorted.put(String.valueOf(k), val));
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> e : sorted.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    string(sb, e.getKey());
                    sb.append(':');
                    append(sb, e.getValue());
                }
                sb.append('}');
            }
            case List<?> l -> {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    append(sb, l.get(i));
                }
                sb.append(']');
            }
            default -> string(sb, v.toString());
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
