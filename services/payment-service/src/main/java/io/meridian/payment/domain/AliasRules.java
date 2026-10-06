package io.meridian.payment.domain;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Normalisation and syntax validation of proxy aliases: E.164 phones, lower-case e-mails, UPI VPAs, PIX keys
 * (CPF / CNPJ with check digits, random EVP keys) and national / business ids.
 */
public final class AliasRules {
    private static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{6,14}");
    private static final Pattern EMAIL = Pattern.compile("[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}");
    private static final Pattern VPA = Pattern.compile("[a-z0-9.\\-_]{2,256}@[a-z][a-z0-9]{1,63}");
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._\\-]{3,64}");

    /** Thrown with a stable error code when an alias is malformed. */
    public static final class InvalidAlias extends RuntimeException {
        public InvalidAlias(String message) {
            super(message);
        }
    }

    private AliasRules() {}

    public static String normalise(String type, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidAlias("alias_value is required");
        }
        String v = raw.trim();
        switch (type) {
            case "PHONE" -> {
                String p = v.replaceAll("[\\s().\\-]", "");
                if (p.startsWith("00")) {
                    p = "+" + p.substring(2);
                }
                if (!E164.matcher(p).matches()) {
                    throw new InvalidAlias("PHONE aliases must be E.164 numbers, e.g. +447700900123");
                }
                return p;
            }
            case "EMAIL" -> {
                String e = v.toLowerCase(Locale.ROOT);
                if (!EMAIL.matcher(e).matches()) {
                    throw new InvalidAlias("EMAIL alias is not a valid address");
                }
                return e;
            }
            case "UPI_VPA" -> {
                String e = v.toLowerCase(Locale.ROOT);
                if (!VPA.matcher(e).matches()) {
                    throw new InvalidAlias("UPI_VPA must look like name@psp");
                }
                return e;
            }
            case "PIX_KEY_CPF" -> {
                String d = v.replaceAll("[.\\-]", "");
                if (!cpfValid(d)) {
                    throw new InvalidAlias("PIX_KEY_CPF is not a valid CPF");
                }
                return d;
            }
            case "PIX_KEY_CNPJ" -> {
                String d = v.replaceAll("[./\\-]", "");
                if (!cnpjValid(d)) {
                    throw new InvalidAlias("PIX_KEY_CNPJ is not a valid CNPJ");
                }
                return d;
            }
            case "PIX_KEY_RANDOM" -> {
                try {
                    return UUID.fromString(v).toString();
                } catch (IllegalArgumentException e) {
                    throw new InvalidAlias("PIX_KEY_RANDOM must be a UUID (EVP key)");
                }
            }
            case "USERNAME" -> {
                String u = v.toLowerCase(Locale.ROOT).replaceFirst("^@", "");
                if (!USERNAME.matcher(u).matches()) {
                    throw new InvalidAlias("USERNAME must be 3-64 characters of a-z, 0-9, '.', '_' or '-'");
                }
                return u;
            }
            default -> {
                String id = v.toUpperCase(Locale.ROOT).replaceAll("[\\s\\-]", "");
                if (id.length() < 4 || id.length() > 64 || !id.matches("[A-Z0-9/]+")) {
                    throw new InvalidAlias(type + " must be 4-64 letters or digits");
                }
                return id;
            }
        }
    }

    /** Brazilian CPF: 11 digits, two mod-11 check digits, not all digits equal. */
    public static boolean cpfValid(String d) {
        if (d == null || !d.matches("\\d{11}") || d.chars().distinct().count() == 1) {
            return false;
        }
        int[] n = d.chars().map(c -> c - '0').toArray();
        for (int k = 9; k <= 10; k++) {
            int sum = 0;
            for (int i = 0; i < k; i++) {
                sum += n[i] * (k + 1 - i);
            }
            int check = (sum * 10) % 11 % 10;
            if (check != n[k]) {
                return false;
            }
        }
        return true;
    }

    /** Brazilian CNPJ: 14 digits, two weighted mod-11 check digits. */
    public static boolean cnpjValid(String d) {
        if (d == null || !d.matches("\\d{14}") || d.chars().distinct().count() == 1) {
            return false;
        }
        int[] n = d.chars().map(c -> c - '0').toArray();
        int[] w1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] w2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        return n[12] == cnpjDigit(n, w1) && n[13] == cnpjDigit(n, w2);
    }

    private static int cnpjDigit(int[] n, int[] w) {
        int sum = 0;
        for (int i = 0; i < w.length; i++) {
            sum += n[i] * w[i];
        }
        int r = sum % 11;
        return r < 2 ? 0 : 11 - r;
    }

    /** Masked alias for display, e.g. +44*******123, j***@example.com. */
    public static String mask(String type, String value) {
        if (value == null || value.length() < 4) {
            return value;
        }
        if ("EMAIL".equals(type) || "UPI_VPA".equals(type)) {
            int at = value.indexOf('@');
            return value.charAt(0) + "***" + value.substring(at);
        }
        return value.substring(0, 3) + "*".repeat(value.length() - 6 > 0 ? value.length() - 6 : 1)
                + value.substring(value.length() - 3);
    }
}
