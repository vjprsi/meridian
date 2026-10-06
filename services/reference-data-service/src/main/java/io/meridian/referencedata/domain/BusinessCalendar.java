package io.meridian.referencedata.domain;

import io.meridian.common.error.ApiException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A business calendar: weekend weekdays plus dated holidays. Half days count as business days (flagged).
 * Pure domain logic; the application layer loads the holidays for the window it needs.
 */
public final class BusinessCalendar {
    /** Upper bound of calendar days scanned when looking for business days (about ten years). */
    public static final int MAX_SCAN_DAYS = 3660;
    /** Largest business-day offset accepted by {@link #shift}. */
    public static final int MAX_OFFSET = 1000;

    public record HolidayDay(LocalDate date, String name, String type, boolean halfDay) {}

    public record DayStatus(LocalDate date, boolean businessDay, boolean halfDay, String reason, String holidayName) {}

    private final Set<DayOfWeek> weekend;
    private final Map<LocalDate, HolidayDay> holidays;

    public BusinessCalendar(Set<DayOfWeek> weekend, Collection<HolidayDay> holidays) {
        this.weekend = weekend.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(weekend);
        this.holidays = holidays.stream().collect(Collectors.toMap(HolidayDay::date, Function.identity(), (a, b) -> a));
    }

    /**
     * Parses weekday names (SATURDAY, sun, ...). Rejects unknown names and a calendar with no business weekday.
     */
    public static Set<DayOfWeek> parseWeekend(Collection<String> names) {
        Set<DayOfWeek> days = new LinkedHashSet<>();
        for (String n : names) {
            days.add(parseDay(n));
        }
        if (days.size() == 7) {
            throw ApiException.unprocessable("invalid_weekend_days", "A calendar needs at least one business weekday");
        }
        return days;
    }

    static DayOfWeek parseDay(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.unprocessable("invalid_weekend_days", "weekend_days contains an empty value");
        }
        String n = name.trim().toUpperCase(Locale.ROOT);
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.name().equals(n) || d.name().substring(0, 3).equals(n)) {
                return d;
            }
        }
        throw ApiException.unprocessable("invalid_weekend_days", "'" + name + "' is not a weekday name (MONDAY..SUNDAY)");
    }

    public static List<String> names(Set<DayOfWeek> days) {
        return days.stream().sorted().map(DayOfWeek::name).toList();
    }

    public DayStatus status(LocalDate date) {
        HolidayDay h = holidays.get(date);
        if (weekend.contains(date.getDayOfWeek())) {
            return new DayStatus(date, false, false, "WEEKEND", h == null ? null : h.name());
        }
        if (h != null) {
            return h.halfDay()
                    ? new DayStatus(date, true, true, "HALF_DAY_HOLIDAY", h.name())
                    : new DayStatus(date, false, false, "HOLIDAY", h.name());
        }
        return new DayStatus(date, true, false, null, null);
    }

    public boolean isBusinessDay(LocalDate date) {
        return status(date).businessDay();
    }

    /**
     * Moves {@code days} business days from {@code start}: positive = forward, negative = backward,
     * zero = {@code start} itself if a business day, otherwise the following business day.
     */
    public LocalDate shift(LocalDate start, int days) {
        if (Math.abs(days) > MAX_OFFSET) {
            throw ApiException.unprocessable("offset_too_large", "days must be between -" + MAX_OFFSET + " and " + MAX_OFFSET);
        }
        int step = days < 0 ? -1 : 1;
        int remaining = Math.abs(days);
        LocalDate d = start;
        if (remaining == 0) {
            for (int scanned = 0; scanned <= MAX_SCAN_DAYS; scanned++, d = d.plusDays(1)) {
                if (isBusinessDay(d)) {
                    return d;
                }
            }
            throw noBusinessDay();
        }
        for (int scanned = 0; scanned < MAX_SCAN_DAYS; scanned++) {
            d = d.plusDays(step);
            if (isBusinessDay(d) && --remaining == 0) {
                return d;
            }
        }
        throw noBusinessDay();
    }


    private static ApiException noBusinessDay() {
        return ApiException.unprocessable("no_business_day_found",
                "No business day found within " + MAX_SCAN_DAYS + " calendar days; check the calendar's holidays");
    }
}
