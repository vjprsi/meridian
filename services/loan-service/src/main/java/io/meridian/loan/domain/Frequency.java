package io.meridian.loan.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Repayment frequencies: period length, periods per year and nominal (unadjusted) due dates. */
public enum Frequency {
    DAILY(0, 1, 365), WEEKLY(0, 7, 52), FORTNIGHTLY(0, 14, 26), MONTHLY(1, 0, 12), QUARTERLY(3, 0, 4),
    SEMI_ANNUAL(6, 0, 2), ANNUAL(12, 0, 1), AT_MATURITY(0, 0, 1);

    private final int months;
    private final int days;
    private final int perYear;

    Frequency(int months, int days, int perYear) {
        this.months = months;
        this.days = days;
        this.perYear = perYear;
    }

    public static Frequency parse(String v) {
        if (v == null || v.isBlank()) {
            return MONTHLY;
        }
        return valueOf(v.trim().toUpperCase(Locale.ROOT));
    }

    public int periodsPerYear() {
        return perYear;
    }

    public boolean monthBased() {
        return months > 0;
    }

    /**
     * Nominal date of the k-th period after {@code anchor} (k = 0 is the anchor). Month-based dates are computed from
     * the anchor each time, so the 31st becomes 30 April and 31 May again (no end-of-month drift).
     */
    public LocalDate nth(LocalDate anchor, int k) {
        if (months > 0) {
            return anchor.plusMonths((long) months * k);
        }
        if (days > 0) {
            return anchor.plusDays((long) days * k);
        }
        return anchor;
    }

    /** One period after {@code d}; for AT_MATURITY the term end. */
    public LocalDate next(LocalDate d, int termMonths) {
        return this == AT_MATURITY ? d.plusMonths(Math.max(1, termMonths)) : nth(d, 1);
    }

    /** Number of instalments for a term in months starting at {@code start}. */
    public int periods(int termMonths, LocalDate start) {
        if (termMonths < 1) {
            throw new IllegalArgumentException("term must be at least one month");
        }
        return switch (this) {
            case AT_MATURITY -> 1;
            case DAILY -> (int) ChronoUnit.DAYS.between(start, start.plusMonths(termMonths));
            case WEEKLY, FORTNIGHTLY -> Math.max(1,
                    (int) Math.round(ChronoUnit.DAYS.between(start, start.plusMonths(termMonths)) / (double) days));
            default -> Math.max(1, termMonths / months);
        };
    }

    /** Approximate term in months covered by {@code n} periods (for loans defined by instalment count). */
    public int termMonths(int n) {
        return switch (this) {
            case AT_MATURITY -> 1;
            case DAILY, WEEKLY, FORTNIGHTLY -> Math.max(1, (int) Math.ceil(n * (days == 0 ? 1 : days) / 30.4375));
            default -> n * months;
        };
    }
}
