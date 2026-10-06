package io.meridian.merchant.domain;

import io.meridian.common.error.ApiException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Set;

/** Merchant status machine and generated codes. */
public final class MerchantRules {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] LINK_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();

    private static final Map<String, Set<String>> MERCHANT = Map.of(
            "ONBOARDING", Set.of("ACTIVE", "TERMINATED"),
            "ACTIVE", Set.of("SUSPENDED", "TERMINATED"),
            "SUSPENDED", Set.of("ACTIVE", "TERMINATED"),
            "TERMINATED", Set.of());

    private MerchantRules() {}

    public static void requireTransition(String from, String to) {
        if (!MERCHANT.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.conflict("invalid_transition", "Merchant cannot move from " + from + " to " + to);
        }
    }

    /** Seven-digit till number: six random digits plus a Luhn check digit (first digit never 0). */
    public static String merchantCode() {
        StringBuilder sb = new StringBuilder().append(1 + RANDOM.nextInt(9));
        while (sb.length() < 6) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.append(luhn(sb.toString())).toString();
    }

    public static int luhn(String digits) {
        int sum = 0;
        boolean dbl = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return (10 - sum % 10) % 10;
    }

    public static String linkCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(LINK_ALPHABET[RANDOM.nextInt(LINK_ALPHABET.length)]);
        }
        return sb.toString();
    }
}
