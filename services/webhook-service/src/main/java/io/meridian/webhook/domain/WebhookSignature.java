package io.meridian.webhook.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@code Meridian-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}. During a secret
 * rotation grace period one {@code v1} is sent per valid secret (newest first), so receivers can roll keys.
 */
public final class WebhookSignature {
    public static final String HEADER = "Meridian-Signature";

    private WebhookSignature() {}

    public static String sign(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static String header(List<String> secrets, long timestamp, String body) {
        StringBuilder sb = new StringBuilder("t=").append(timestamp);
        for (String s : secrets) {
            sb.append(",v1=").append(sign(s, timestamp, body));
        }
        return sb.toString();
    }

    /** Receiver-side verification (constant time), with a tolerance on the timestamp. */
    public static boolean verify(String secret, String header, String body, long nowSeconds, long toleranceSeconds) {
        if (header == null) {
            return false;
        }
        Long t = null;
        for (String part : header.split(",")) {
            if (part.startsWith("t=")) {
                try {
                    t = Long.parseLong(part.substring(2));
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        if (t == null || Math.abs(nowSeconds - t) > toleranceSeconds) {
            return false;
        }
        byte[] expected = sign(secret, t, body).getBytes(StandardCharsets.US_ASCII);
        for (String part : header.split(",")) {
            if (part.startsWith("v1=")
                    && MessageDigest.isEqual(expected, part.substring(3).getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }
}
