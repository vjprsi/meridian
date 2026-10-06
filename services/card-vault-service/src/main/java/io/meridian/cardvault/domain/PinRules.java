package io.meridian.cardvault.domain;

/** PIN policy: 4-12 digits, no trivially guessable PINs. */
public final class PinRules {
    /** Consecutive wrong PIN attempts after which the PIN is blocked until unblocked. */
    public static final int TRY_LIMIT = 3;

    private PinRules() {}

    public static boolean wellFormed(String pin) {
        return pin != null && pin.length() >= 4 && pin.length() <= 12 && pin.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /** All the same digit (1111), or a run ascending (1234, 7890 not wrapping) / descending (4321). */
    public static boolean weak(String pin) {
        if (!wellFormed(pin)) {
            return true;
        }
        boolean same = true;
        boolean asc = true;
        boolean desc = true;
        for (int i = 1; i < pin.length(); i++) {
            int step = pin.charAt(i) - pin.charAt(i - 1);
            same &= step == 0;
            asc &= step == 1;
            desc &= step == -1;
        }
        return same || asc || desc;
    }
}
