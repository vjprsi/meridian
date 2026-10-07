package io.meridian.biller.domain;

import java.math.BigInteger;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates a biller account reference (meter, account or policy number) against the biller's pattern (full match)
 * and check-digit algorithm:
 * <ul>
 *   <li>LUHN - mod-10 Luhn over the digits, last digit is the check digit.</li>
 *   <li>MOD97 - ISO 7064 MOD 97-10 over the digits (as for IBANs): the whole number mod 97 must equal 1.</li>
 *   <li>MOD11 - weights 2,3,4,5,6,7 (repeating) applied from the rightmost payload digit leftwards; the check digit
 *       is {@code 11 - (sum mod 11)}, where a result of 11 means 0 and a result of 10 is never issued (invalid).</li>
 * </ul>
 * Checksum algorithms require an all-digit reference.
 */
public final class ReferenceValidator {
    private ReferenceValidator() {}

    /** Error message when the reference is invalid, empty when it is acceptable. */
    public static Optional<String> validate(String reference, String regex, String checksum) {
        if (reference == null || reference.isBlank()) {
            return Optional.of("The account reference is required");
        }
        String ref = reference.trim();
        if (regex != null && !regex.isBlank() && !io.meridian.common.text.SafeRegex.matches(regex, ref)) {
            return Optional.of("The account reference does not have the expected format");
        }
        String algo = checksum == null ? "NONE" : checksum;
        if ("NONE".equals(algo)) {
            return Optional.empty();
        }
        if (!ref.matches("\\d{2,}")) {
            return Optional.of("The account reference must be numeric for " + algo + " check digits");
        }
        boolean ok = switch (algo) {
            case "LUHN" -> luhn(ref);
            case "MOD97" -> mod97(ref);
            case "MOD11" -> mod11(ref);
            default -> throw new IllegalArgumentException("Unknown checksum " + algo);
        };
        return ok ? Optional.empty() : Optional.of("The account reference check digit is wrong");
    }

    /** True when the pattern is a valid, safe regular expression (SEC-30: no nested quantifiers, bounded length). */
    public static boolean compiles(String regex) {
        return io.meridian.common.text.SafeRegex.problem(regex).isEmpty();
    }

    public static boolean luhn(String digits) {
        int sum = 0;
        boolean dbl = false;
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
        return sum % 10 == 0;
    }

    public static boolean mod97(String digits) {
        return new BigInteger(digits).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    public static boolean mod11(String digits) {
        Integer expected = mod11CheckDigit(digits.substring(0, digits.length() - 1));
        return expected != null && expected == digits.charAt(digits.length() - 1) - '0';
    }

    /** The MOD11 check digit for a payload, or null when the payload cannot carry one (result 10). */
    public static Integer mod11CheckDigit(String payload) {
        int sum = 0;
        int weight = 2;
        for (int i = payload.length() - 1; i >= 0; i--) {
            sum += (payload.charAt(i) - '0') * weight;
            weight = weight == 7 ? 2 : weight + 1;
        }
        int r = 11 - sum % 11;
        if (r == 11) {
            return 0;
        }
        return r == 10 ? null : r;
    }
}
