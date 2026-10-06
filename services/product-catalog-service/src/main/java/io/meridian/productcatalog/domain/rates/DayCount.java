package io.meridian.productcatalog.domain.rates;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;

/** Day-count conventions (DayCountBasis) as year fractions between a start date (inclusive) and end (exclusive). */
public enum DayCount {
    ACT_360, ACT_365, ACT_365L, ACT_ACT_ISDA, THIRTY_360, THIRTY_E_360;

    static final MathContext MC = MathContext.DECIMAL128;

    public static DayCount of(String s) {
        return s == null ? ACT_365 : valueOf(s);
    }

    /** Year fraction for the period [start, end). Zero or negative periods give zero. */
    public BigDecimal yearFraction(LocalDate start, LocalDate end) {
        if (!end.isAfter(start)) {
            return BigDecimal.ZERO;
        }
        long days = ChronoUnit.DAYS.between(start, end);
        return switch (this) {
            case ACT_360 -> BigDecimal.valueOf(days).divide(BigDecimal.valueOf(360), MC);
            case ACT_365 -> BigDecimal.valueOf(days).divide(BigDecimal.valueOf(365), MC);
            case ACT_365L -> BigDecimal.valueOf(days).divide(BigDecimal.valueOf(containsLeapDay(start, end) ? 366 : 365), MC);
            case ACT_ACT_ISDA -> actActIsda(start, end);
            case THIRTY_360 -> thirty(start, end, false);
            case THIRTY_E_360 -> thirty(start, end, true);
        };
    }

    /** Days in the year used to turn an annual rate into a daily one at {@code date}. */
    public int daysInYear(LocalDate date) {
        return switch (this) {
            case ACT_360, THIRTY_360, THIRTY_E_360 -> 360;
            case ACT_365 -> 365;
            case ACT_365L, ACT_ACT_ISDA -> Year.isLeap(date.getYear()) ? 366 : 365;
        };
    }

    private static BigDecimal actActIsda(LocalDate start, LocalDate end) {
        BigDecimal total = BigDecimal.ZERO;
        LocalDate cur = start;
        while (cur.isBefore(end)) {
            LocalDate yearEnd = LocalDate.of(cur.getYear() + 1, 1, 1);
            LocalDate segEnd = yearEnd.isBefore(end) ? yearEnd : end;
            long d = ChronoUnit.DAYS.between(cur, segEnd);
            total = total.add(BigDecimal.valueOf(d).divide(BigDecimal.valueOf(Year.of(cur.getYear()).length()), MC), MC);
            cur = segEnd;
        }
        return total;
    }

    private static BigDecimal thirty(LocalDate s, LocalDate e, boolean european) {
        int d1 = Math.min(s.getDayOfMonth(), 30);
        int d2 = e.getDayOfMonth();
        if (european) {
            d2 = Math.min(d2, 30);
        } else if (d1 == 30) {
            d2 = Math.min(d2, 30);
        }
        long days = 360L * (e.getYear() - s.getYear()) + 30L * (e.getMonthValue() - s.getMonthValue()) + (d2 - d1);
        return BigDecimal.valueOf(Math.max(0, days)).divide(BigDecimal.valueOf(360), MC);
    }

    private static boolean containsLeapDay(LocalDate start, LocalDate end) {
        for (int y = start.getYear(); y <= end.getYear(); y++) {
            if (Year.isLeap(y)) {
                LocalDate feb29 = LocalDate.of(y, 2, 29);
                if (!feb29.isBefore(start) && feb29.isBefore(end)) {
                    return true;
                }
            }
        }
        return false;
    }
}
