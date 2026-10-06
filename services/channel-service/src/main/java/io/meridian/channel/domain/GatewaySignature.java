package io.meridian.channel.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Gateway callback signatures: lowercase hex HMAC-SHA256 over the canonical JSON of the request body (object keys
 * sorted recursively, null members omitted, no whitespace). Comparison is constant-time; a missing secret or signature never verifies.
 */
public final class GatewaySignature {
    public static final String HEADER = "X-Gateway-Signature";

    private GatewaySignature() {}

    public static String sign(String secret, Object body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical(body).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(String secret, Object body, String signature) {
        if (secret == null || secret.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(sign(secret, body).getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    /** Canonical JSON of a value made of maps, lists, strings, numbers, booleans and nulls. */
    @SuppressWarnings("unchecked")
    public static String canonical(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> sorted = new TreeMap<>();
            m.forEach((k, x) -> {
                if (x != null) {
                    sorted.put(String.valueOf(k), x);
                }
            });
            List<String> parts = new ArrayList<>();
            sorted.forEach((k, x) -> parts.add(quote(k) + ":" + canonical(x)));
            return "{" + String.join(",", parts) + "}";
        }
        if (v instanceof List<?> l) {
            List<String> parts = new ArrayList<>();
            l.forEach(x -> parts.add(canonical(x)));
            return "[" + String.join(",", parts) + "]";
        }
        if (v instanceof String s) {
            return quote(s);
        }
        return String.valueOf(v);
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
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
        return sb.append('"').toString();
    }
}
