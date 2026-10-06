package io.meridian.identity.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (RFC 6238 over RFC 4226 HOTP): HMAC-SHA1, 30 second steps, 6 digits.
 * Verification accepts the current step and +/- {@code window} steps to tolerate clock drift.
 */
public final class Totp {
    public static final int DIGITS = 6;
    public static final long STEP_SECONDS = 30;
    public static final int DEFAULT_WINDOW = 1;
    private static final int[] POW10 = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000};

    private Totp() {}

    /** HOTP value (RFC 4226 section 5.3) for a counter, zero-padded to {@code digits}. */
    public static String hotp(byte[] key, long counter, int digits) {
        if (digits < 6 || digits > 8) {
            throw new IllegalArgumentException("digits must be 6..8");
        }
        byte[] msg = new byte[8];
        for (int i = 7; i >= 0; i--) {
            msg[i] = (byte) (counter & 0xff);
            counter >>>= 8;
        }
        byte[] h;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            h = mac.doFinal(msg);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        int offset = h[h.length - 1] & 0x0f;
        int binary = ((h[offset] & 0x7f) << 24) | ((h[offset + 1] & 0xff) << 16)
                | ((h[offset + 2] & 0xff) << 8) | (h[offset + 3] & 0xff);
        String code = Integer.toString(binary % POW10[digits]);
        return "0".repeat(digits - code.length()) + code;
    }

    public static long counterAt(Instant t) {
        return Math.floorDiv(t.getEpochSecond(), STEP_SECONDS);
    }

    public static String at(byte[] key, Instant t, int digits) {
        return hotp(key, counterAt(t), digits);
    }

    /**
     * Returns the matching time-step counter if {@code code} is valid at {@code now} within the window, compared in
     * constant time; empty otherwise.
     */
    public static OptionalLong verify(byte[] key, String code, Instant now, int window) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        long current = counterAt(now);
        byte[] given = code.getBytes(StandardCharsets.US_ASCII);
        for (long c = current - window; c <= current + window; c++) {
            byte[] expected = hotp(key, c, DIGITS).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, given)) {
                return OptionalLong.of(c);
            }
        }
        return OptionalLong.empty();
    }

    /** Key URI understood by authenticator apps (Google Authenticator key-uri format). */
    public static String otpauthUri(String issuer, String account, String base32Secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
