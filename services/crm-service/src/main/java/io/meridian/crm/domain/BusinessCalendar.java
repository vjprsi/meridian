package io.meridian.crm.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Business-hours arithmetic for SLA clocks: working days, a daily window [start, end) in a time zone and holidays.
 * With {@code businessHoursOnly = false} the clock runs 24x7 (holidays are ignored). All results are UTC instants.
 */
public final class BusinessCalendar {
    private static final int MAX_DAYS = 3660;
    public static final BusinessCalendar ALWAYS = new BusinessCalendar(false, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT,
            EnumSet.allOf(DayOfWeek.class), ZoneOffset.UTC, Set.of());

    private final boolean businessHoursOnly;
    private final LocalTime dayStart;
    private final LocalTime dayEnd;
    private final Set<DayOfWeek> workingDays;
    private final ZoneId zone;
    private final Set<LocalDate> holidays;

    public BusinessCalendar(boolean businessHoursOnly, LocalTime dayStart, LocalTime dayEnd, Set<DayOfWeek> workingDays,
                            ZoneId zone, Collection<LocalDate> holidays) {
        if (businessHoursOnly && !dayEnd.isAfter(dayStart)) {
            throw new IllegalArgumentException("business day end must be after its start");
        }
        if (workingDays.isEmpty()) {
            throw new IllegalArgumentException("at least one working day is required");
        }
        this.businessHoursOnly = businessHoursOnly;
        this.dayStart = dayStart;
        this.dayEnd = dayEnd;
        this.workingDays = EnumSet.copyOf(workingDays);
        this.zone = zone;
        this.holidays = Set.copyOf(holidays);
    }

    /** Monday to Friday, all day, no holidays: used to count "business days" when no policy applies. */
    public static BusinessCalendar weekdays(ZoneId zone, Collection<LocalDate> holidays) {
        return new BusinessCalendar(true, LocalTime.MIDNIGHT, LocalTime.of(23, 59, 59, 999_999_999),
                EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), zone, holidays);
    }

    public boolean isWorkingDay(LocalDate d) {
        return workingDays.contains(d.getDayOfWeek()) && !holidays.contains(d);
    }

    /** The instant {@code minutes} business minutes after {@code start}. */
    public OffsetDateTime addMinutes(OffsetDateTime start, long minutes) {
        if (minutes < 0) {
            throw new IllegalArgumentException("minutes must not be negative");
        }
        if (!businessHoursOnly) {
            return start.plusMinutes(minutes).withOffsetSameInstant(ZoneOffset.UTC);
        }
        ZonedDateTime t = start.atZoneSameInstant(zone);
        Duration left = Duration.ofMinutes(minutes);
        for (int i = 0; i < MAX_DAYS; i++) {
            LocalDate d = t.toLocalDate();
            ZonedDateTime open = d.atTime(dayStart).atZone(zone);
            ZonedDateTime close = d.atTime(dayEnd).atZone(zone);
            if (!isWorkingDay(d) || !t.isBefore(close)) {
                t = d.plusDays(1).atTime(dayStart).atZone(zone);
                continue;
            }
            if (t.isBefore(open)) {
                t = open;
            }
            Duration available = Duration.between(t, close);
            if (left.compareTo(available) <= 0) {
                return t.plus(left).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
            }
            left = left.minus(available);
            t = d.plusDays(1).atTime(dayStart).atZone(zone);
        }
        throw new IllegalStateException("no business time within " + MAX_DAYS + " days");
    }

    /** Business minutes elapsed between two instants (0 when {@code to} is not after {@code from}). */
    public long minutesBetween(OffsetDateTime from, OffsetDateTime to) {
        if (!to.isAfter(from)) {
            return 0;
        }
        if (!businessHoursOnly) {
            return Duration.between(from, to).toMinutes();
        }
        ZonedDateTime a = from.atZoneSameInstant(zone);
        ZonedDateTime b = to.atZoneSameInstant(zone);
        long seconds = 0;
        for (LocalDate d = a.toLocalDate(); !d.isAfter(b.toLocalDate()); d = d.plusDays(1)) {
            if (!isWorkingDay(d)) {
                continue;
            }
            ZonedDateTime open = d.atTime(dayStart).atZone(zone);
            ZonedDateTime close = d.atTime(dayEnd).atZone(zone);
            ZonedDateTime s = a.isAfter(open) ? a : open;
            ZonedDateTime e = b.isBefore(close) ? b : close;
            if (e.isAfter(s)) {
                seconds += Duration.between(s, e).toSeconds();
            }
        }
        return seconds / 60;
    }

    /** Same time of day, {@code days} working days later (calendar date arithmetic, used for regulatory deadlines). */
    public OffsetDateTime addWorkingDays(OffsetDateTime start, int days) {
        ZonedDateTime t = start.atZoneSameInstant(zone);
        int added = 0;
        while (added < days) {
            t = t.plusDays(1);
            if (isWorkingDay(t.toLocalDate())) {
                added++;
            }
        }
        return t.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }
}
