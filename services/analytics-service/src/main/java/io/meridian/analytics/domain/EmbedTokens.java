package io.meridian.analytics.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signed, expiring dashboard embed tokens: {@code base64url(claims).base64url(HMAC-SHA256(claims))} with claims
 * {@code {"v":1,"tid":tenant,"did":dashboard,"sub":principal,"iat":epoch,"exp":epoch,"jti":id}}. Verification is
 * constant-time and checks signature, expiry and (optionally) the tenant.
 */
public final class EmbedTokens {
    private static final Pattern CLAIMS = Pattern.compile(
            "\\{\"v\":1,\"tid\":\"([0-9a-f-]{36})\",\"did\":\"([0-9a-f-]{36})\",\"sub\":\"([^\"]{0,64})\",\"iat\":(\\d+),\"exp\":(\\d+),\"jti\":\"([0-9a-f-]{36})\"}");

    private EmbedTokens() {}

    public record Claims(String tenantId, String dashboardId, String subject, long issuedAt, long expiresAt, String jti) {}

    public record Verification(boolean valid, String reason, Claims claims) {}

    public static String issue(byte[] secret, Claims c) {
        String sub = c.subject().replaceAll("[^A-Za-z0-9_.@:\\-]", "_");
        String claims = "{\"v\":1,\"tid\":\"" + c.tenantId() + "\",\"did\":\"" + c.dashboardId() + "\",\"sub\":\""
                + (sub.length() > 64 ? sub.substring(0, 64) : sub) + "\",\"iat\":" + c.issuedAt() + ",\"exp\":"
                + c.expiresAt() + ",\"jti\":\"" + c.jti() + "\"}";
        String body = b64(claims.getBytes(StandardCharsets.UTF_8));
        return body + "." + b64(hmac(secret, body));
    }

    public static Verification verify(byte[] secret, String token, long nowEpoch, String expectedTenant) {
        if (token == null || token.length() > 2048) {
            return new Verification(false, "malformed", null);
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return new Verification(false, "malformed", null);
        }
        String body = token.substring(0, dot);
        byte[] sig;
        byte[] claimsBytes;
        try {
            sig = Base64.getUrlDecoder().decode(token.substring(dot + 1));
            claimsBytes = Base64.getUrlDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            return new Verification(false, "malformed", null);
        }
        if (!MessageDigest.isEqual(sig, hmac(secret, body))) {
            return new Verification(false, "bad_signature", null);
        }
        Matcher m = CLAIMS.matcher(new String(claimsBytes, StandardCharsets.UTF_8));
        if (!m.matches()) {
            return new Verification(false, "malformed", null);
        }
        Claims c = new Claims(m.group(1), m.group(2), m.group(3), Long.parseLong(m.group(4)), Long.parseLong(m.group(5)), m.group(6));
        if (nowEpoch >= c.expiresAt()) {
            return new Verification(false, "expired", c);
        }
        if (expectedTenant != null && !expectedTenant.equals(c.tenantId())) {
            return new Verification(false, "wrong_tenant", c);
        }
        return new Verification(true, null, c);
    }

    public static Map<String, Object> describe(Verification v) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", v.valid());
        if (v.reason() != null) {
            out.put("reason", v.reason());
        }
        if (v.claims() != null && v.valid()) {
            out.put("dashboard_id", v.claims().dashboardId());
            out.put("subject", v.claims().subject());
            out.put("expires_at", java.time.Instant.ofEpochSecond(v.claims().expiresAt()).toString());
        }
        return out;
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] hmac(byte[] secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
