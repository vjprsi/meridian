package io.meridian.crm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BusinessCalendarTest {
    static final ZoneId LONDON = ZoneId.of("Europe/London");
    final BusinessCalendar cal = new BusinessCalendar(true, LocalTime.of(9, 0), LocalTime.of(17, 0),
            EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), LONDON, Set.of(LocalDate.of(2026, 12, 25)));

    @Test
    void addsWithinTheSameBusinessDay() {
        // Tue 2026-10-06 10:00 London (09:00Z, BST) + 120 business minutes = 12:00 London.
        OffsetDateTime start = OffsetDateTime.parse("2026-10-06T09:00:00Z");
        assertThat(cal.addMinutes(start, 120)).isEqualTo(OffsetDateTime.parse("2026-10-06T11:00:00Z"));
    }

    @Test
    void rollsOverEveningsAndWeekends() {
        // Fri 2026-10-09 16:00 London + 120 minutes = Mon 10:00 London.
        OffsetDateTime start = OffsetDateTime.parse("2026-10-09T15:00:00Z");
        assertThat(cal.addMinutes(start, 120)).isEqualTo(OffsetDateTime.parse("2026-10-12T09:00:00Z"));
        // Saturday start counts from Monday 09:00.
        assertThat(cal.addMinutes(OffsetDateTime.parse("2026-10-10T12:00:00Z"), 60))
                .isEqualTo(OffsetDateTime.parse("2026-10-12T09:00:00Z"));
    }

    @Test
    void skipsHolidaysAndHandlesWinterTime() {
        // Thu 2026-12-24 16:30 London (GMT) + 60 minutes: 30 on the 24th, Christmas and the weekend skipped, 30 on Mon 28th.
        OffsetDateTime start = OffsetDateTime.parse("2026-12-24T16:30:00Z");
        assertThat(cal.addMinutes(start, 60)).isEqualTo(OffsetDateTime.parse("2026-12-28T09:30:00Z"));
        assertThat(cal.isWorkingDay(LocalDate.of(2026, 12, 25))).isFalse();
    }

    @Test
    void measuresBusinessMinutesBetweenInstants() {
        OffsetDateTime fri = OffsetDateTime.parse("2026-10-09T15:00:00Z"); // 16:00 London
        OffsetDateTime mon = OffsetDateTime.parse("2026-10-12T09:30:00Z"); // 10:30 London
        assertThat(cal.minutesBetween(fri, mon)).isEqualTo(60 + 90);
        assertThat(cal.minutesBetween(mon, fri)).isZero();
    }

    @Test
    void roundTripsAddAndMeasure() {
        OffsetDateTime start = OffsetDateTime.parse("2026-10-07T13:17:00Z");
        for (long m : List.of(1L, 59L, 480L, 481L, 2400L, 9999L)) {
            assertThat(cal.minutesBetween(start, cal.addMinutes(start, m))).isEqualTo(m);
        }
    }

    @Test
    void twentyFourSevenAndWorkingDays() {
        OffsetDateTime start = OffsetDateTime.parse("2026-10-10T23:00:00Z");
        assertThat(BusinessCalendar.ALWAYS.addMinutes(start, 90)).isEqualTo(OffsetDateTime.parse("2026-10-11T00:30:00Z"));
        BusinessCalendar weekdays = BusinessCalendar.weekdays(ZoneId.of("UTC"), Set.of());
        // Thu + 3 working days = Tue.
        assertThat(weekdays.addWorkingDays(OffsetDateTime.parse("2026-10-08T10:00:00Z"), 3))
                .isEqualTo(OffsetDateTime.parse("2026-10-13T10:00:00Z"));
        assertThatThrownBy(() -> new BusinessCalendar(true, LocalTime.of(17, 0), LocalTime.of(9, 0),
                EnumSet.of(DayOfWeek.MONDAY), LONDON, Set.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
