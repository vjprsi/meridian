package io.meridian.webhook.domain;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The endpoint's signing secrets: the current one and, during the 24 h rotation grace period, the previous one.
 * Serialised as plain text ({@code current \n previous \n previousExpiresEpochSeconds}) before encryption at rest.
 */
public record SigningSecrets(String current, String previous, Instant previousExpiresAt) {
    public static final Duration ROTATION_GRACE = Duration.ofHours(24);
    private static final SecureRandom RANDOM = new SecureRandom();

    public static String newSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static SigningSecrets fresh() {
        return new SigningSecrets(newSecret(), null, null);
    }

    public SigningSecrets rotate(Instant now) {
        return new SigningSecrets(newSecret(), current, now.plus(ROTATION_GRACE));
    }

    /** Secrets to sign with at {@code now}: current first, then the previous one while still in grace. */
    public List<String> active(Instant now) {
        List<String> out = new ArrayList<>();
        out.add(current);
        if (previous != null && previousExpiresAt != null && now.isBefore(previousExpiresAt)) {
            out.add(previous);
        }
        return out;
    }

    public String serialise() {
        return current + "\n" + (previous == null ? "" : previous) + "\n"
                + (previousExpiresAt == null ? "" : Long.toString(previousExpiresAt.getEpochSecond()));
    }

    public static SigningSecrets parse(String text) {
        String[] p = text.split("\n", -1);
        String prev = p.length > 1 && !p[1].isEmpty() ? p[1] : null;
        Instant exp = p.length > 2 && !p[2].isEmpty() ? Instant.ofEpochSecond(Long.parseLong(p[2])) : null;
        return new SigningSecrets(p[0], prev, exp);
    }
}
