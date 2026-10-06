package io.meridian.cardvault.domain;

/**
 * Validation of sensitive card data (PAN, CVV2, expiry). Pure functions; error messages never echo the input value.
 */
public final class CardData {
    private CardData() {}

    /** Luhn (mod 10) check over a digit string. */
    public static boolean luhnValid(String digits) {
        if (digits == null || digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return false;
        }
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

    /** Check digit that makes {@code payload + digit} Luhn-valid. */
    public static int luhnCheckDigit(String payload) {
        for (int d = 0; d < 10; d++) {
            if (luhnValid(payload + d)) {
                return d;
            }
        }
        throw new IllegalArgumentException("payload must be digits");
    }

    /** A PAN is 13-19 digits and Luhn-valid. */
    public static boolean panValid(String pan) {
        return pan != null && pan.length() >= 13 && pan.length() <= 19 && luhnValid(pan);
    }

    public static boolean cvvValid(String cvv) {
        return cvv != null && (cvv.length() == 3 || cvv.length() == 4) && cvv.chars().allMatch(Character::isDigit);
    }

    public static boolean expiryValid(Integer month, Integer year) {
        return month != null && year != null && month >= 1 && month <= 12 && year >= 1000 && year <= 9999;
    }

    /** Expiry as stored (encrypted): MM/YYYY. */
    public static String expiry(int month, int year) {
        return String.format("%02d/%04d", month, year);
    }

    public static int expiryMonth(String stored) {
        return Integer.parseInt(stored.substring(0, 2));
    }

    public static int expiryYear(String stored) {
        return Integer.parseInt(stored.substring(3));
    }

    public static String bin(String pan) {
        return pan.substring(0, 6);
    }

    public static String last4(String pan) {
        return pan.substring(pan.length() - 4);
    }
}
