package io.meridian.businessday.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * A business calendar: weekend days plus dated holidays. Pure value object used for the date roll, period-end
 * flags (EOM / EOQ / EOY) and business-days-only schedules.
 */
public final class BusinessCalendar {
    /** Safety bound: no real calendar has more than a year of consecutive non-business days. */
    private static final int MAX_SCAN_DAYS = 366;

    private final Set<DayOfWeek> weekendDays;
    private final Set<LocalDate> holidays;

    public BusinessCalendar(Collection<DayOfWeek> weekendDays, Collection<LocalDate> holidays) {
        this.weekendDays = weekendDays == null || weekendDays.isEmpty()
                ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(weekendDays);
        this.holidays = new TreeSet<>(holidays == null ? Set.of() : holidays);
        if (this.weekendDays.size() == 7) {
            throw new IllegalArgumentException("a calendar cannot have seven weekend days");
        }
    }

    /** Saturday/Sunday weekend, no holidays. */
    public static BusinessCalendar standard() {
        return new BusinessCalendar(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), Set.of());
    }

    public boolean isBusinessDay(LocalDate date) {
        return !weekendDays.contains(date.getDayOfWeek()) && !holidays.contains(date);
    }

    /** The first business day strictly after {@code date}. */
    public LocalDate nextBusinessDay(LocalDate date) {
        LocalDate d = date.plusDays(1);
        for (int i = 0; i < MAX_SCAN_DAYS; i++) {
            if (isBusinessDay(d)) {
                return d;
            }
            d = d.plusDays(1);
        }
        throw new IllegalStateException("no business day within a year after " + date);
    }

    /** The last business day strictly before {@code date}. */
    public LocalDate previousBusinessDay(LocalDate date) {
        LocalDate d = date.minusDays(1);
        for (int i = 0; i < MAX_SCAN_DAYS; i++) {
            if (isBusinessDay(d)) {
                return d;
            }
            d = d.minusDays(1);
        }
        throw new IllegalStateException("no business day within a year before " + date);
    }

    /** True when {@code date} is the last business day of its month (the next business day is in another month). */
    public boolean isMonthEnd(LocalDate date) {
        LocalDate next = nextBusinessDay(date);
        return next.getMonthValue() != date.getMonthValue() || next.getYear() != date.getYear();
    }

    public boolean isQuarterEnd(LocalDate date) {
        LocalDate next = nextBusinessDay(date);
        return quarter(next) != quarter(date) || next.getYear() != date.getYear();
    }

    public boolean isYearEnd(LocalDate date) {
        return nextBusinessDay(date).getYear() != date.getYear();
    }

    /** Period-end flags of a business date. */
    public PeriodEnd periodEnd(LocalDate date) {
        return new PeriodEnd(isMonthEnd(date), isQuarterEnd(date), isYearEnd(date));
    }

    public record PeriodEnd(boolean monthEnd, boolean quarterEnd, boolean yearEnd) {
        /** The broadest end-of-period cycle that applies to the date. */
        public CycleType defaultCycle() {
            if (yearEnd) {
                return CycleType.EOY;
            }
            if (quarterEnd) {
                return CycleType.EOQ;
            }
            return monthEnd ? CycleType.EOM : CycleType.EOD;
        }
    }

    private static int quarter(LocalDate d) {
        return (d.getMonthValue() - 1) / 3;
    }
}
