package io.meridian.onboarding.application;

import io.meridian.common.time.Clocks;
import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;

/**
 * Server-generated application references: {@code APP-<yyyymmdd>-<8 random Crockford base32 characters>} (40 random
 * bits, unguessable and unique per day in practice; the column's unique constraint is the backstop). Clients never
 * choose the number.
 */
public final class ApplicationNumbers {
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private ApplicationNumbers() {}

    public static String next() {
        StringBuilder b = new StringBuilder("APP-").append(Clocks.now().format(DAY)).append('-');
        for (int i = 0; i < 8; i++) {
            b.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return b.toString();
    }
}
