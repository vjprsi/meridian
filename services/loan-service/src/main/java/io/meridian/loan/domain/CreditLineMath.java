package io.meridian.loan.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Revolving-credit statement arithmetic (pure): average daily balance interest and minimum payment. */
public final class CreditLineMath {
    static final MathContext MC = MathContext.DECIMAL128;

    /** A change of the drawn balance effective from a date (positive = draw, negative = principal repaid). */
    public record Movement(LocalDate date, BigDecimal delta) {}

    private CreditLineMath() {}

    /**
     * Average daily balance over [start, end] (inclusive): opening balance plus every movement from its date on.
     */
    public static BigDecimal averageDailyBalance(BigDecimal opening, List<Movement> movements, LocalDate start,
                                                 LocalDate end) {
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days <= 0) {
            return BigDecimal.ZERO;
        }
        List<Movement> sorted = new ArrayList<>(movements);
        sorted.sort(Comparator.comparing(Movement::date));
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal balance = opening;
        int idx = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            while (idx < sorted.size() && !sorted.get(idx).date().isAfter(d)) {
                balance = balance.add(sorted.get(idx).delta());
                idx++;
            }
            sum = sum.add(balance.max(BigDecimal.ZERO));
        }
        return sum.divide(BigDecimal.valueOf(days), MC);
    }

    /** Interest on an average balance for the cycle length with the day-count basis of the cycle end. */
    public static BigDecimal interest(BigDecimal adb, BigDecimal annualRate, long days, DayCount dayCount, LocalDate end,
                                      int scale) {
        if (annualRate == null || annualRate.signum() <= 0 || adb.signum() <= 0) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_EVEN);
        }
        return adb.multiply(annualRate, MC).multiply(BigDecimal.valueOf(days), MC)
                .divide(BigDecimal.valueOf(dayCount.basis(end)), MC).setScale(scale, RoundingMode.HALF_EVEN);
    }

    /** max(floor, pct x balance), never more than the balance; zero for a zero or credit balance. */
    public static BigDecimal minimumPayment(BigDecimal closing, BigDecimal pct, BigDecimal floor, int scale) {
        if (closing.signum() <= 0) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_EVEN);
        }
        BigDecimal m = closing.multiply(pct, MC).setScale(scale, RoundingMode.HALF_UP).max(floor == null ? BigDecimal.ZERO : floor);
        return m.min(closing).setScale(scale, RoundingMode.HALF_EVEN);
    }
}
