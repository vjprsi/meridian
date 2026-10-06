package io.meridian.deposit.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Day-count conventions (ISDA naming) used to turn an annual rate into interest for a period. */
public enum DayCount {
    ACT_365, ACT_360, ACT_365L, ACT_ACT_ISDA, THIRTY_360, THIRTY_E_360;

    static final MathContext MC = MathContext.DECIMAL128;

    public static DayCount parse(String value) {
        if (value == null || value.isBlank()) {
            return ACT_365;
        }
        String v = value.trim().toUpperCase(Locale.ROOT).replace('/', '_').replace('-', '_').replace(' ', '_');
        return switch (v) {
            case "ACT_365", "ACT_365F", "ACT_365_FIXED", "ACTUAL_365" -> ACT_365;
            case "ACT_360", "ACTUAL_360" -> ACT_360;
            case "ACT_365L" -> ACT_365L;
            case "ACT_ACT", "ACT_ACT_ISDA", "ACTUAL_ACTUAL" -> ACT_ACT_ISDA;
            case "30_360", "THIRTY_360" -> THIRTY_360;
            case "30E_360", "THIRTY_E_360" -> THIRTY_E_360;
            default -> throw new IllegalArgumentException("unsupported day count " + value);
        };
    }

    /** Year fraction between {@code from} (inclusive) and {@code to} (exclusive). */
    public BigDecimal yearFraction(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return yearFraction(to, from).negate();
        }
        return switch (this) {
            case ACT_365 -> days(from, to).divide(BigDecimal.valueOf(365), MC);
            case ACT_360 -> days(from, to).divide(BigDecimal.valueOf(360), MC);
            case ACT_365L -> days(from, to).divide(BigDecimal.valueOf(Year.isLeap(to.minusDays(1).getYear()) ? 366 : 365), MC);
            case ACT_ACT_ISDA -> actAct(from, to);
            case THIRTY_360 -> BigDecimal.valueOf(thirty360(from, to, false)).divide(BigDecimal.valueOf(360), MC);
            case THIRTY_E_360 -> BigDecimal.valueOf(thirty360(from, to, true)).divide(BigDecimal.valueOf(360), MC);
        };
    }

    /** The day-count denominator used for a single calendar day. */
    public int basis(LocalDate day) {
        return switch (this) {
            case ACT_360, THIRTY_360, THIRTY_E_360 -> 360;
            case ACT_365 -> 365;
            case ACT_365L, ACT_ACT_ISDA -> Year.isLeap(day.getYear()) ? 366 : 365;
        };
    }

    private static BigDecimal days(LocalDate from, LocalDate to) {
        return BigDecimal.valueOf(ChronoUnit.DAYS.between(from, to));
    }

    private static BigDecimal actAct(LocalDate from, LocalDate to) {
        BigDecimal total = BigDecimal.ZERO;
        LocalDate cursor = from;
        while (cursor.isBefore(to)) {
            LocalDate yearEnd = LocalDate.of(cursor.getYear() + 1, 1, 1);
            LocalDate end = yearEnd.isBefore(to) ? yearEnd : to;
            int basis = Year.isLeap(cursor.getYear()) ? 366 : 365;
            total = total.add(days(cursor, end).divide(BigDecimal.valueOf(basis), MC), MC);
            cursor = end;
        }
        return total;
    }

    static long thirty360(LocalDate from, LocalDate to, boolean european) {
        int d1 = from.getDayOfMonth();
        int d2 = to.getDayOfMonth();
        if (european) {
            d1 = Math.min(d1, 30);
            d2 = Math.min(d2, 30);
        } else {
            if (d1 == 31) {
                d1 = 30;
            }
            if (d2 == 31 && d1 >= 30) {
                d2 = 30;
            }
        }
        return 360L * (to.getYear() - from.getYear()) + 30L * (to.getMonthValue() - from.getMonthValue()) + (d2 - d1);
    }
}
