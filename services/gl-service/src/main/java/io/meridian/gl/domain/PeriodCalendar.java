package io.meridian.gl.domain;

import io.meridian.common.error.ApiException;
import java.time.LocalDate;

/** Calendar rules for accounting periods: boundaries must match the period type. */
public final class PeriodCalendar {
    public enum PeriodType { DAY, MONTH, QUARTER, YEAR }

    private PeriodCalendar() {}

    public static void validate(PeriodType type, LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw ApiException.unprocessable("invalid_period_range", "end_date must not be before start_date");
        }
        boolean ok = switch (type) {
            case DAY -> start.equals(end);
            case MONTH -> start.getDayOfMonth() == 1 && end.equals(start.plusMonths(1).minusDays(1));
            case QUARTER -> start.getDayOfMonth() == 1 && (start.getMonthValue() - 1) % 3 == 0
                    && end.equals(start.plusMonths(3).minusDays(1));
            // Fiscal years may start on the first day of any month.
            case YEAR -> start.getDayOfMonth() == 1 && end.equals(start.plusYears(1).minusDays(1));
        };
        if (!ok) {
            throw ApiException.unprocessable("invalid_period_boundaries",
                    "start_date " + start + " and end_date " + end + " do not form a calendar " + type);
        }
    }
}
