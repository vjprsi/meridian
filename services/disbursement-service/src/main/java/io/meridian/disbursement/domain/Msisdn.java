package io.meridian.disbursement.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/** E.164 normalisation of mobile numbers as they appear in beneficiary files ("+254 712-345 678", "00254...", "0712..."). */
public final class Msisdn {
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");

    private Msisdn() {}

    public static boolean isE164(String s) {
        return s != null && E164.matcher(s).matches();
    }

    /**
     * @param raw                number as written
     * @param defaultCallingCode country calling code (digits, e.g. "254") used for national numbers with a trunk
     *                           prefix 0; null = national numbers are rejected
     * @return the E.164 form, or empty when the value cannot be a valid mobile number
     */
    public static Optional<String> normalize(String raw, String defaultCallingCode) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.strip().replaceAll("[\\s\\-().]", "");
        if (s.isEmpty()) {
            return Optional.empty();
        }
        if (s.startsWith("00")) {
            s = "+" + s.substring(2);
        } else if (s.startsWith("0") && defaultCallingCode != null && !defaultCallingCode.isBlank()) {
            s = "+" + defaultCallingCode.replace("+", "") + s.substring(1);
        } else if (!s.startsWith("+") && defaultCallingCode != null && !defaultCallingCode.isBlank()
                && s.startsWith(defaultCallingCode.replace("+", "")) && s.length() >= 10) {
            s = "+" + s;
        }
        return isE164(s) ? Optional.of(s) : Optional.empty();
    }
}
