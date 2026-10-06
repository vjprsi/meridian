package io.meridian.card.domain;

/** ISO/IEC 7812 Luhn (mod 10) check digit used by every payment card number. */
public final class Luhn {
    private Luhn() {}

    /** Check digit to append to {@code payload} (digits only) so the whole number is Luhn-valid. */
    public static int checkDigit(CharSequence payload) {
        int sum = 0;
        boolean dbl = true; // rightmost payload digit is doubled (it becomes second from the right)
        for (int i = payload.length() - 1; i >= 0; i--) {
            int d = digit(payload.charAt(i));
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

    /** True when {@code number} is all digits, at least 2 long, and its last digit is the Luhn check digit. */
    public static boolean isValid(CharSequence number) {
        if (number == null || number.length() < 2) {
            return false;
        }
        for (int i = 0; i < number.length(); i++) {
            if (!Character.isDigit(number.charAt(i))) {
                return false;
            }
        }
        return checkDigit(number.subSequence(0, number.length() - 1)) == digit(number.charAt(number.length() - 1));
    }

    private static int digit(char c) {
        if (c < '0' || c > '9') {
            throw new IllegalArgumentException("not a digit");
        }
        return c - '0';
    }
}
