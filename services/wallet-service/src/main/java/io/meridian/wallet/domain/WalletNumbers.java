package io.meridian.wallet.domain;

import java.security.SecureRandom;

/** Wallet numbers and transaction references. Wallet numbers are 12 digits ending in a Luhn check digit. */
public final class WalletNumbers {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] REF_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    public static final int LENGTH = 12;

    private WalletNumbers() {}

    /** A random wallet number with the given numeric prefix and a Luhn check digit (12 digits in total). */
    public static String generate(String prefix) {
        if (prefix == null || !prefix.matches("\\d{0,6}")) {
            throw new IllegalArgumentException("wallet number prefix must be 0-6 digits");
        }
        StringBuilder sb = new StringBuilder(prefix);
        while (sb.length() < LENGTH - 1) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.append(luhnDigit(sb.toString())).toString();
    }

    public static boolean isValid(String number) {
        return number != null && number.matches("\\d{2,}")
                && luhnDigit(number.substring(0, number.length() - 1)) == number.charAt(number.length() - 1) - '0';
    }

    /** Luhn check digit for the given digits. */
    public static int luhnDigit(String digits) {
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

    /** Human-readable transaction reference, e.g. {@code WT7KQ2M9XH4P}: unambiguous characters only. */
    public static String reference() {
        StringBuilder sb = new StringBuilder("WT");
        for (int i = 0; i < 10; i++) {
            sb.append(REF_ALPHABET[RANDOM.nextInt(REF_ALPHABET.length)]);
        }
        return sb.toString();
    }
}
