package io.meridian.collections.domain;

import java.math.BigDecimal;

/**
 * Rules-based priority (0-1000) for working queues: deeper delinquency, larger arrears, broken promises and no
 * active arrangement raise it; disputed cases (contact suppressed) sink.
 */
public final class Priority {
    private Priority() {}

    public static int score(int dpd, BigDecimal overdue, int brokenPromises, boolean arrangementActive, boolean disputed,
                            boolean vulnerable) {
        double s = Math.min(400, Math.max(0, dpd) * 2.0);
        double amt = overdue == null || overdue.signum() <= 0 ? 0 : Math.log10(overdue.doubleValue() + 1) * 75;
        s += Math.min(300, amt);
        s += Math.min(150, brokenPromises * 50.0);
        if (!arrangementActive) {
            s += 100;
        }
        if (vulnerable) {
            s += 50; // specialist handling sooner, not harsher treatment
        }
        if (disputed) {
            s -= 300;
        }
        return (int) Math.max(0, Math.min(1000, Math.round(s)));
    }
}
