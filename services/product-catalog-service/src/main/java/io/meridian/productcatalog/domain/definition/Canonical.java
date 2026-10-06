package io.meridian.productcatalog.domain.definition;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical JSON text of plain Java values (maps with sorted keys, numbers without trailing zeros), so that
 * checksums do not depend on key order or numeric scale introduced by storage.
 */
public final class Canonical {
    private Canonical() {}

    public static String of(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    public static String sha256(Object v) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(of(v).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(StringBuilder sb, Object v) {
        switch (v) {
            case null -> sb.append("null");
            case Map<?, ?> m -> {
                Map<String, Object> sorted = new TreeMap<>();
                m.forEach((k, val) -> sorted.put(String.valueOf(k), val));
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> e : sorted.entrySet()) {
                    if (e.getValue() == null) {
                        continue;
                    }
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    string(sb, e.getKey());
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
            }
            case List<?> l -> {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    write(sb, l.get(i));
                }
                sb.append(']');
            }
            case BigDecimal b -> sb.append(b.signum() == 0 ? "0" : b.stripTrailingZeros().toPlainString());
            case Number n -> sb.append(new BigDecimal(n.toString()).stripTrailingZeros().toPlainString());
            case Boolean b -> sb.append(b);
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
