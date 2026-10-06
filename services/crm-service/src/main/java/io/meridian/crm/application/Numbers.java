package io.meridian.crm.application;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/** Human-readable references: PREFIX-yyMMdd-XXXXXX (Crockford base32, no ambiguous letters). */
final class Numbers {
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyMMdd");

    private Numbers() {}

    static String next(String prefix, OffsetDateTime now) {
        StringBuilder sb = new StringBuilder(prefix).append('-').append(DAY.format(now)).append('-');
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
