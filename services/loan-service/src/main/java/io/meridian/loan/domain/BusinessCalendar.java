package io.meridian.loan.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;

/** Weekends plus public holidays, with the ISDA business-day conventions used to adjust instalment due dates. */
public record BusinessCalendar(Set<LocalDate> holidays) {

    public enum Convention {
        NONE, FOLLOWING, MODIFIED_FOLLOWING, PRECEDING;

        public static Convention parse(String v) {
            if (v == null || v.isBlank()) {
                return MODIFIED_FOLLOWING;
            }
            return valueOf(v.trim().toUpperCase(Locale.ROOT));
        }
    }

    public BusinessCalendar {
        holidays = holidays == null ? Set.of() : Set.copyOf(holidays);
    }

    public static BusinessCalendar weekendsOnly() {
        return new BusinessCalendar(Set.of());
    }

    public boolean isBusinessDay(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(d);
    }

    public LocalDate adjust(LocalDate d, Convention convention) {
        if (convention == Convention.NONE || isBusinessDay(d)) {
            return d;
        }
        return switch (convention) {
            case FOLLOWING -> following(d);
            case PRECEDING -> preceding(d);
            case MODIFIED_FOLLOWING -> {
                LocalDate f = following(d);
                yield f.getMonth() == d.getMonth() ? f : preceding(d);
            }
            case NONE -> d;
        };
    }

    private LocalDate following(LocalDate d) {
        LocalDate x = d;
        while (!isBusinessDay(x)) {
            x = x.plusDays(1);
        }
        return x;
    }

    private LocalDate preceding(LocalDate d) {
        LocalDate x = d;
        while (!isBusinessDay(x)) {
            x = x.minusDays(1);
        }
        return x;
    }
}
