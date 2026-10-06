package io.meridian.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Generation and hashing of credentials. Plaintext secrets are only ever returned once and never stored. */
public final class Secrets {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {}

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    /** URL-safe random token with {@code bytes} bytes of entropy. */
    public static String token(int bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(bytes));
    }

    public static String hex(int bytes) {
        return HexFormat.of().formatHex(randomBytes(bytes));
    }

    public static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Client identifier: {@code cli_} + 24 hex chars. */
    public static String clientId() {
        return "cli_" + hex(12);
    }

    /** Client secret: {@code cs_} + 256 bits of entropy. */
    public static String clientSecret() {
        return "cs_" + token(32);
    }

    /** API key prefix (16 chars, shown in consoles): {@code mk_live_xxxxxxxx} / {@code mk_test_xxxxxxxx}. */
    public static String keyPrefix(boolean production) {
        return (production ? "mk_live_" : "mk_test_") + hex(4);
    }

    /** Full API key = prefix + "." + 256-bit secret. */
    public static String apiKey(String prefix) {
        return prefix + "." + token(32);
    }
}
