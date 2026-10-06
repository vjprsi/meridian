package io.meridian.tenant.domain;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure format validators used by tenant-service (no Spring, no SQL). */
public final class Validators {
    private Validators() {}

    private static final Pattern LEI = Pattern.compile("[A-Z0-9]{18}[0-9]{2}");
    private static final Pattern BIC = Pattern.compile("[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?");
    private static final Pattern HEX_COLOR = Pattern.compile("#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})");
    private static final Pattern SETTING_KEY = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+");
    private static final Pattern FLAG_KEY = Pattern.compile("[a-z][a-z0-9_.-]{1,127}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    /** ISO 17442 LEI: 18 alphanumerics + 2 check digits, ISO 7064 MOD 97-10 over the letter-expanded string == 1. */
    public static boolean isValidLei(String lei) {
        if (lei == null || !LEI.matcher(lei).matches()) {
            return false;
        }
        return mod97(lei) == 1;
    }

    /** ISO 9362 BIC (8 or 11 characters). */
    public static boolean isValidBic(String bic) {
        return bic != null && BIC.matcher(bic).matches();
    }

    public static boolean isHexColor(String c) {
        return c != null && HEX_COLOR.matcher(c).matches();
    }

    public static boolean isSettingKey(String k) {
        return k != null && k.length() <= 128 && SETTING_KEY.matcher(k).matches();
    }

    public static boolean isFlagKey(String k) {
        return k != null && FLAG_KEY.matcher(k).matches();
    }

    public static boolean isCurrency(String c) {
        return c != null && CURRENCY.matcher(c).matches();
    }

    /** ISO 8601 duration (PT30M, P1D, P1DT2H). */
    public static boolean isIsoDuration(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        try {
            Duration.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            try {
                Period.parse(s);
                return true;
            } catch (DateTimeParseException e2) {
                return false;
            }
        }
    }

    static int mod97(String s) {
        StringBuilder digits = new StringBuilder();
        for (char ch : s.toUpperCase(Locale.ROOT).toCharArray()) {
            if (Character.isDigit(ch)) {
                digits.append(ch);
            } else {
                digits.append(ch - 'A' + 10);
            }
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue();
    }

    /** WCAG 2.x contrast ratio between two hex colours (alpha ignored). */
    public static double contrastRatio(String hexA, String hexB) {
        double la = luminance(hexA);
        double lb = luminance(hexB);
        double hi = Math.max(la, lb);
        double lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    static double luminance(String hex) {
        String h = hex.substring(1);
        if (h.length() == 3) {
            h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        }
        double r = channel(Integer.parseInt(h.substring(0, 2), 16));
        double g = channel(Integer.parseInt(h.substring(2, 4), 16));
        double b = channel(Integer.parseInt(h.substring(4, 6), 16));
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
