package io.meridian.cardvault.domain;

/** PIN policy: 4-12 digits, no trivially guessable PINs. */
public final class PinRules {
    /** Consecutive wrong PIN attempts after which the PIN is blocked until unblocked. */
    public static final int TRY_LIMIT = 3;

    private PinRules() {}

    public static boolean wellFormed(String pin) {
        return pin != null && pin.length() >= 4 && pin.length() <= 12 && pin.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /**
     * Every weak PIN of every allowed length (same digit, ascending or descending run): 146 values. The HSM mode
     * cannot look at a PIN, so a new PIN block is compared, encrypted, against these public reference values.
     */
    public static java.util.List<String> weakPins() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int len = 4; len <= 12; len++) {
            for (int d = 0; d <= 9; d++) {
                out.add(String.valueOf((char) ('0' + d)).repeat(len));
            }
            for (int start = 0; start + len - 1 <= 9; start++) {
                StringBuilder asc = new StringBuilder();
                StringBuilder desc = new StringBuilder();
                for (int i = 0; i < len; i++) {
                    asc.append((char) ('0' + start + i));
                    desc.append((char) ('9' - start - i));
                }
                out.add(asc.toString());
                out.add(desc.toString());
            }
        }
        return out;
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
