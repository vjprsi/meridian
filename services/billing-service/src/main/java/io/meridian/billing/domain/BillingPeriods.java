package io.meridian.billing.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Billing period arithmetic anchored on the subscription start date. */
public final class BillingPeriods {
    private BillingPeriods() {}

    public record Period(LocalDate start, LocalDate end) {
        public long days() {
            return ChronoUnit.DAYS.between(start, end) + 1;
        }

        public boolean contains(LocalDate d) {
            return !d.isBefore(start) && !d.isAfter(end);
        }
    }

    public static int months(String billingPeriod) {
        return switch (billingPeriod) {
            case "MONTHLY" -> 1;
            case "QUARTERLY" -> 3;
            case "ANNUAL" -> 12;
            default -> throw new IllegalArgumentException("unknown billing period " + billingPeriod);
        };
    }

    /** The billing period (anchored on {@code anchor}) that contains {@code date}; dates before the anchor map to the first. */
    public static Period containing(LocalDate anchor, String billingPeriod, LocalDate date) {
        int m = months(billingPeriod);
        long k = 0;
        if (date.isAfter(anchor)) {
            k = Math.max(0, ChronoUnit.MONTHS.between(anchor, date) / m - 1);
            while (!anchor.plusMonths((k + 1) * m).isAfter(date)) {
                k++;
            }
        }
        // Always offset from the anchor (not the previous period) so month-end anchors do not drift.
        return new Period(anchor.plusMonths(k * m), anchor.plusMonths((k + 1) * m).minusDays(1));
    }

    /**
     * Proration of a fee change for the remaining days of a period, inclusive of the effective date:
     * (newFee - oldFee) x remainingDays / periodDays, rounded to 2 dp HALF_EVEN. Positive = charge, negative = credit.
     */
    public static BigDecimal proration(BigDecimal oldFee, BigDecimal newFee, Period period, LocalDate effective) {
        if (!period.contains(effective)) {
            throw new IllegalArgumentException("effective date outside period");
        }
        long remaining = ChronoUnit.DAYS.between(effective, period.end()) + 1;
        return newFee.subtract(oldFee)
                .multiply(BigDecimal.valueOf(remaining))
                .divide(BigDecimal.valueOf(period.days()), MathContext.DECIMAL128)
                .setScale(2, RoundingMode.HALF_EVEN);
    }

    /** Fraction of {@code period} covered by [from, to] (clipped), as covered/total days. */
    public static BigDecimal coverage(Period period, LocalDate from, LocalDate to) {
        LocalDate s = from.isAfter(period.start()) ? from : period.start();
        LocalDate e = to == null || to.isAfter(period.end()) ? period.end() : to;
        if (e.isBefore(s)) {
            return BigDecimal.ZERO;
        }
        long covered = ChronoUnit.DAYS.between(s, e) + 1;
        return BigDecimal.valueOf(covered).divide(BigDecimal.valueOf(period.days()), MathContext.DECIMAL128);
    }
}
