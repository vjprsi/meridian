package io.meridian.treasurydeal.domain;

import java.math.BigInteger;
import java.util.regex.Pattern;

/** Validation of market identifiers: ISIN (ISO 6166), BIC (ISO 9362) and LEI (ISO 17442). */
public final class Identifiers {
    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");
    private static final Pattern BIC = Pattern.compile("[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?");
    private static final Pattern LEI = Pattern.compile("[A-Z0-9]{18}[0-9]{2}");

    private Identifiers() {}

    /** True when {@code isin} has the ISO 6166 shape and a correct check digit. */
    public static boolean isValidIsin(String isin) {
        return isin != null && ISIN.matcher(isin).matches() && isinCheckDigit(isin.substring(0, 11)) == isin.charAt(11) - '0';
    }

    /**
     * ISIN check digit of the first 11 characters: letters become two digits (A=10 .. Z=35), then the Luhn
     * algorithm runs over the digit string, doubling every second digit starting from the rightmost.
     */
    public static int isinCheckDigit(String first11) {
        StringBuilder digits = new StringBuilder();
        for (char c : first11.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                digits.append(c - 'A' + 10);
            } else {
                throw new IllegalArgumentException("invalid ISIN character " + c);
            }
        }
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

    public static boolean isValidBic(String bic) {
        return bic != null && BIC.matcher(bic).matches();
    }

    /** ISO 17442: 20 characters, ISO 7064 MOD 97-10 over the code with letters as numbers equals 1. */
    public static boolean isValidLei(String lei) {
        if (lei == null || !LEI.matcher(lei).matches()) {
            return false;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : lei.toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }
}
