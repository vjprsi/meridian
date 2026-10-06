package io.meridian.deposit.domain;

import io.meridian.common.error.ApiException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Recurring schedules (standing orders, savings rules) with business-day adjustment.
 * Occurrences are generated from the start date and frequency ("nominal" dates; month-end clamped, e.g. the 31st
 * becomes 30 April / 28 or 29 February) and then moved to a business day by the holiday rule:
 * NEXT (following), PREVIOUS (preceding), MODIFIED_FOLLOWING (following unless it changes month) or SKIP
 * (the occurrence is dropped).
 */
public final class Schedule {
    private static final int MAX_OCCURRENCES = 100_000;

    public enum Frequency { ONCE, DAILY, WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY, SEMI_ANNUAL, ANNUAL }

    public enum HolidayRule { NEXT, PREVIOUS, MODIFIED_FOLLOWING, SKIP;
        public static HolidayRule parse(String v) {
            if (v == null || v.isBlank()) {
                return NEXT;
            }
            try {
                return valueOf(v.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ApiException.unprocessable("invalid_holiday_rule", "holiday_rule must be NEXT, PREVIOUS, "
                        + "MODIFIED_FOLLOWING or SKIP");
            }
        }
    }

    /** Weekends plus a set of public holidays. */
    public record BusinessCalendar(Set<LocalDate> holidays) {
        public BusinessCalendar {
            holidays = holidays == null ? Set.of() : Set.copyOf(holidays);
        }

        public static BusinessCalendar weekendsOnly() {
            return new BusinessCalendar(Set.of());
        }

        public boolean isBusinessDay(LocalDate d) {
            return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(d);
        }

        public LocalDate following(LocalDate d) {
            LocalDate x = d;
            while (!isBusinessDay(x)) {
                x = x.plusDays(1);
            }
            return x;
        }

        public LocalDate preceding(LocalDate d) {
            LocalDate x = d;
            while (!isBusinessDay(x)) {
                x = x.minusDays(1);
            }
            return x;
        }

        /** Adjusted date, or empty when the rule is SKIP and the date is not a business day. */
        public Optional<LocalDate> adjust(LocalDate d, HolidayRule rule) {
            if (isBusinessDay(d)) {
                return Optional.of(d);
            }
            return switch (rule) {
                case NEXT -> Optional.of(following(d));
                case PREVIOUS -> Optional.of(preceding(d));
                case MODIFIED_FOLLOWING -> {
                    LocalDate f = following(d);
                    yield Optional.of(f.getMonth() == d.getMonth() ? f : preceding(d));
                }
                case SKIP -> Optional.empty();
            };
        }

        public LocalDate addBusinessDays(LocalDate d, int n) {
            LocalDate x = d;
            int left = n;
            while (left > 0) {
                x = x.plusDays(1);
                if (isBusinessDay(x)) {
                    left--;
                }
            }
            return x;
        }
    }

    /** A recurring instruction. {@code dayOfPeriod}: 1-7 (ISO weekday) for weekly, 1-31 for monthly and longer. */
    public record Plan(Frequency frequency, LocalDate start, Integer dayOfPeriod, LocalDate end, Integer maxExecutions,
                       HolidayRule holidayRule) {
        public Plan {
            if (frequency == null || start == null) {
                throw new IllegalArgumentException("frequency and start are required");
            }
            holidayRule = holidayRule == null ? HolidayRule.NEXT : holidayRule;
        }
    }

    private Schedule() {}

    public static Frequency frequency(String v) {
        try {
            return Frequency.valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw ApiException.unprocessable("invalid_frequency", "Unsupported frequency " + v);
        }
    }

    /** Validates plan parameters, throwing 422 with a stable code. */
    public static void validate(Plan p) {
        if (p.end() != null && p.end().isBefore(p.start())) {
            throw ApiException.unprocessable("invalid_end_date", "end_date must not be before start_date");
        }
        if (p.maxExecutions() != null && p.maxExecutions() < 1) {
            throw ApiException.unprocessable("invalid_max_executions", "max_executions must be at least 1");
        }
        Integer d = p.dayOfPeriod();
        if (d != null) {
            boolean weekly = p.frequency() == Frequency.WEEKLY || p.frequency() == Frequency.BIWEEKLY;
            if (weekly && (d < 1 || d > 7)) {
                throw ApiException.unprocessable("invalid_day_of_period", "day_of_period must be 1-7 (Mon-Sun) for weekly orders");
            }
            if (!weekly && (d < 1 || d > 31)) {
                throw ApiException.unprocessable("invalid_day_of_period", "day_of_period must be 1-31");
            }
        }
    }

    /** The n-th (0-based) nominal occurrence, before business-day adjustment. */
    public static LocalDate nominal(Plan p, int n) {
        LocalDate s = p.start();
        Integer dop = p.dayOfPeriod();
        return switch (p.frequency()) {
            case ONCE -> s;
            case DAILY -> s.plusDays(n);
            case WEEKLY, BIWEEKLY -> {
                LocalDate first = dop == null ? s : s.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(dop)));
                yield first.plusWeeks((long) n * (p.frequency() == Frequency.WEEKLY ? 1 : 2));
            }
            case MONTHLY -> monthly(s, dop, n, 1);
            case QUARTERLY -> monthly(s, dop, n, 3);
            case SEMI_ANNUAL -> monthly(s, dop, n, 6);
            case ANNUAL -> monthly(s, dop, n, 12);
        };
    }

    private static LocalDate monthly(LocalDate start, Integer dop, int n, int stepMonths) {
        int day = dop == null ? start.getDayOfMonth() : dop;
        YearMonth firstMonth = YearMonth.from(start);
        if (dop != null && clampDay(firstMonth, day).isBefore(start)) {
            firstMonth = firstMonth.plusMonths(1);
        }
        return clampDay(firstMonth.plusMonths((long) n * stepMonths), day);
    }

    private static LocalDate clampDay(YearMonth ym, int day) {
        return ym.atDay(Math.min(day, ym.lengthOfMonth()));
    }

    /**
     * First execution date strictly after {@code after} (null = from the start), honouring the holiday rule,
     * end date and remaining executions ({@code executionsDone} counts executions already made).
     */
    public static Optional<LocalDate> nextAfter(Plan p, BusinessCalendar cal, LocalDate after, int executionsDone) {
        if (p.maxExecutions() != null && executionsDone >= p.maxExecutions()) {
            return Optional.empty();
        }
        int limit = p.frequency() == Frequency.ONCE ? 1 : MAX_OCCURRENCES;
        for (int n = 0; n < limit; n++) {
            LocalDate nominal = nominal(p, n);
            if (p.end() != null && nominal.isAfter(p.end())) {
                return Optional.empty();
            }
            Optional<LocalDate> adjusted = cal.adjust(nominal, p.holidayRule());
            if (adjusted.isEmpty()) {
                continue;
            }
            LocalDate d = adjusted.get();
            if (d.isBefore(p.start())) {
                d = cal.following(p.start()); // never execute before the instruction starts
            }
            if (after == null || d.isAfter(after)) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    /** Next date after {@code from} for simple periodic jobs (e.g. capitalisation, pot rules), unadjusted. */
    public static LocalDate advance(LocalDate from, Frequency f) {
        return switch (f) {
            case ONCE, DAILY -> from.plusDays(1);
            case WEEKLY -> from.plusWeeks(1);
            case BIWEEKLY -> from.plusWeeks(2);
            case MONTHLY -> from.plusMonths(1);
            case QUARTERLY -> from.plusMonths(3);
            case SEMI_ANNUAL -> from.plusMonths(6);
            case ANNUAL -> from.plusYears(1);
        };
    }
}
