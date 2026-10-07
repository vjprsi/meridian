package io.meridian.notification.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;

/**
 * Signature of a provider delivery-receipt callback: {@code X-Provider-Signature: sha256=<hex>} where hex is
 * HMAC-SHA256, keyed with the provider's callback secret, over the canonical JSON of the callback {@code payload}
 * (object keys sorted, no insignificant whitespace). Canonicalising the parsed payload keeps the check independent
 * of the sender's formatting.
 */
public final class CallbackSignature {
    public static final String HEADER = "X-Provider-Signature";
    private static final String PREFIX = "sha256=";

    private CallbackSignature() {}

    /** The signature header value for a payload. */
    /** {@code sha256=<hex>} HMAC of a text (the v2 payload {@code <ts>.<nonce>.<tenant>.<canonical payload>}). */
    public static String signText(String secret, String text) {
        return PREFIX + HexFormat.of().formatHex(hmac(secret, text));
    }

    public static String sign(String secret, JsonNode payload) {
        return PREFIX + HexFormat.of().formatHex(hmac(secret, canonical(payload)));
    }

    /** Constant-time check of a received header value. */
    public static boolean verify(String secret, JsonNode payload, String header) {
        if (secret == null || secret.isBlank() || header == null || !header.startsWith(PREFIX)) {
            return false;
        }
        byte[] received;
        try {
            received = HexFormat.of().parseHex(header.substring(PREFIX.length()).trim().toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(hmac(secret, canonical(payload)), received);
    }

    /** Canonical JSON: object keys sorted, compact. */
    public static String canonical(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb);
        return sb.toString();
    }

    private static void write(JsonNode n, StringBuilder sb) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            sb.append("null");
        } else if (n.isObject()) {
            List<String> names = new ArrayList<>(n.propertyNames());
            names.sort(null);
            sb.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(JsonNodeText.quote(names.get(i))).append(':');
                write(n.get(names.get(i)), sb);
            }
            sb.append('}');
        } else if (n.isArray()) {
            sb.append('[');
            for (int i = 0; i < n.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(n.get(i), sb);
            }
            sb.append(']');
        } else {
            sb.append(n.toString());
        }
    }

    private static byte[] hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** JSON string quoting via a text node (same escaping as values). */
    private static final class JsonNodeText {
        static String quote(String s) {
            return tools.jackson.databind.node.StringNode.valueOf(s).toString();
        }
    }
}
