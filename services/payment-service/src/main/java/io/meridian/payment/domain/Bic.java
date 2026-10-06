package io.meridian.payment.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** ISO 9362 business identifier code: 4 institution letters, 2 country letters, 2 location, optional 3 branch. */
public final class Bic {
    private static final Pattern BIC = Pattern.compile("[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?");

    private Bic() {}

    public static String normalise(String raw) {
        return raw == null ? null : raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    public static boolean isValid(String raw) {
        String b = normalise(raw);
        return b != null && BIC.matcher(b).matches();
    }

    public static String country(String bic) {
        return normalise(bic).substring(4, 6);
    }

    /** BIC8 / BIC11 → BIC11 (primary office "XXX"). */
    public static String toBic11(String bic) {
        String b = normalise(bic);
        return b.length() == 8 ? b + "XXX" : b;
    }
}
