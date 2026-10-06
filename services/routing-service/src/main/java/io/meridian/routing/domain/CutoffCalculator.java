package io.meridian.routing.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Next possible execution date for a rail: instant 24x7 rails execute immediately (any day, within operating
 * hours when the rail declares a window); other rails execute on the next business day of the rail calendar on
 * which the cut-off applies, rolling to the following business day once today's cut-off has passed.
 */
public final class CutoffCalculator {

    /** Business-day source (reference-data calendar or weekends-only). */
    public interface BusinessCalendar {
        boolean isBusinessDay(LocalDate date);

        default LocalDate nextBusinessDay(LocalDate date) {
            LocalDate d = date.plusDays(1);
            for (int i = 0; i < 31 && !isBusinessDay(d); i++) {
                d = d.plusDays(1);
            }
            return d;
        }
    }

    /** Saturday and Sunday are non-business days. */
    public static final BusinessCalendar WEEKENDS_ONLY = d -> d.getDayOfWeek() != DayOfWeek.SATURDAY
            && d.getDayOfWeek() != DayOfWeek.SUNDAY;

    /** Applicable cut-off (null fields allowed when the rail has none). */
    public record Cutoff(String time, String timezone, List<String> daysOfWeek, boolean nextDayRollover) {}

    /** Operating window of an instant rail ("HH:MM" local); both null = 24x7. */
    public record Window(String open, String close, String timezone) {}

    public record Result(LocalDate executionDate, OffsetDateTime cutoffAt, boolean afterCutoff,
                         boolean todayIsBusinessDay, String timezone, boolean immediate) {}

    private CutoffCalculator() {}

    public static Result compute(Instant now, boolean instant, Window window, Cutoff cutoff, LocalDate requested,
                                 BusinessCalendar calendar) {
        if (instant) {
            String tz = window != null && window.timezone() != null ? window.timezone()
                    : cutoff != null && cutoff.timezone() != null ? cutoff.timezone() : "UTC";
            ZoneId zone = zone(tz);
            ZonedDateTime local = now.atZone(zone);
            LocalDate today = local.toLocalDate();
            boolean bd = calendar.isBusinessDay(today);
            if (requested != null && requested.isAfter(today)) {
                return new Result(requested, null, false, bd, tz, false);
            }
            if (window != null && window.open() != null && window.close() != null) {
                LocalTime open = time(window.open());
                LocalTime close = time(window.close());
                LocalTime t = local.toLocalTime();
                if (t.isBefore(open)) {
                    return new Result(today, null, false, bd, tz, false);
                }
                if (!t.isBefore(close)) {
                    return new Result(today.plusDays(1), today.atTime(close).atZone(zone).toOffsetDateTime(), true,
                            bd, tz, false);
                }
            }
            return new Result(today, null, false, bd, tz, true);
        }
        String tz = cutoff != null && cutoff.timezone() != null ? cutoff.timezone() : "UTC";
        ZoneId zone = zone(tz);
        ZonedDateTime local = now.atZone(zone);
        LocalDate today = local.toLocalDate();
        boolean todayBd = calendar.isBusinessDay(today);
        Set<DayOfWeek> days = cutoff == null || cutoff.daysOfWeek() == null || cutoff.daysOfWeek().isEmpty() ? null
                : cutoff.daysOfWeek().stream().map(s -> DayOfWeek.valueOf(s.trim().toUpperCase(Locale.ROOT)))
                        .collect(Collectors.toSet());
        LocalDate start = requested != null && requested.isAfter(today) ? requested : today;
        LocalDate candidate = applicable(start, calendar, days, false);
        boolean after = false;
        LocalTime cut = cutoff == null || cutoff.time() == null ? null : time(cutoff.time());
        if (candidate.equals(today) && cut != null && !local.toLocalTime().isBefore(cut)) {
            after = true;
            if (cutoff.nextDayRollover()) {
                candidate = applicable(today, calendar, days, true);
            }
        }
        OffsetDateTime cutoffAt = cut == null ? null : candidate.atTime(cut).atZone(zone).toOffsetDateTime();
        return new Result(candidate, cutoffAt, after, todayBd, tz, false);
    }

    /** First business day on/after {@code from} (strictly after when {@code strictlyAfter}) whose weekday applies. */
    static LocalDate applicable(LocalDate from, BusinessCalendar cal, Set<DayOfWeek> days, boolean strictlyAfter) {
        LocalDate d = strictlyAfter ? cal.nextBusinessDay(from) : cal.isBusinessDay(from) ? from : cal.nextBusinessDay(from);
        for (int i = 0; i < 31 && days != null && !days.contains(d.getDayOfWeek()); i++) {
            d = cal.nextBusinessDay(d);
        }
        return d;
    }

    public static LocalTime time(String hhmm) {
        try {
            return LocalTime.parse(hhmm.trim().length() == 4 ? "0" + hhmm.trim() : hhmm.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("time must be HH:MM: " + hhmm);
        }
    }

    public static ZoneId zone(String tz) {
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            throw new IllegalArgumentException("unknown timezone " + tz);
        }
    }
}
