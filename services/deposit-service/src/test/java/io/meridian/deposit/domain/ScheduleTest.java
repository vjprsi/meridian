package io.meridian.deposit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import io.meridian.deposit.domain.Schedule.BusinessCalendar;
import io.meridian.deposit.domain.Schedule.Frequency;
import io.meridian.deposit.domain.Schedule.HolidayRule;
import io.meridian.deposit.domain.Schedule.Plan;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class ScheduleTest {
    static final BusinessCalendar CAL = new BusinessCalendar(Set.of(LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 28)));

    private static List<LocalDate> dates(Plan p, int n) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate after = p.start().minusDays(1);
        for (int i = 0; i < n; i++) {
            Optional<LocalDate> next = Schedule.nextAfter(p, CAL, after, i);
            if (next.isEmpty()) {
                break;
            }
            out.add(next.get());
            after = next.get();
        }
        return out;
    }

    @Test
    void monthlyOnThe31stClampsToMonthEndAndRollsOffWeekends() {
        Plan p = new Plan(Frequency.MONTHLY, LocalDate.of(2026, 1, 31), null, null, null, HolidayRule.NEXT);
        assertThat(dates(p, 4)).containsExactly(
                LocalDate.of(2026, 2, 2),   // 31 Jan 2026 is a Saturday -> Monday 2 Feb
                LocalDate.of(2026, 3, 2),   // 28 Feb is a Saturday -> Monday 2 Mar
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 4, 30));
    }

    @Test
    void previousAndModifiedFollowingAndSkip() {
        Plan prev = new Plan(Frequency.MONTHLY, LocalDate.of(2026, 1, 31), 31, null, null, HolidayRule.PREVIOUS);
        assertThat(dates(prev, 2)).containsExactly(LocalDate.of(2026, 2, 2), LocalDate.of(2026, 2, 27));
        Plan mf = new Plan(Frequency.MONTHLY, LocalDate.of(2026, 5, 1), 30, null, null, HolidayRule.MODIFIED_FOLLOWING);
        assertThat(dates(mf, 1)).containsExactly(LocalDate.of(2026, 5, 29)); // 30/31 May are Sat/Sun -> stay in May
        Plan skip = new Plan(Frequency.DAILY, LocalDate.of(2026, 12, 24), null, null, null, HolidayRule.SKIP);
        assertThat(dates(skip, 3)).containsExactly(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 29),
                LocalDate.of(2026, 12, 30));
    }

    @Test
    void weeklyOnAGivenWeekdayHonoursEndDateAndMaxExecutions() {
        Plan p = new Plan(Frequency.WEEKLY, LocalDate.of(2026, 10, 6), 5, LocalDate.of(2026, 10, 31), null, HolidayRule.NEXT);
        assertThat(dates(p, 10)).containsExactly(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 16),
                LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 30));
        Plan max = new Plan(Frequency.QUARTERLY, LocalDate.of(2026, 1, 15), null, null, 2, HolidayRule.NEXT);
        assertThat(dates(max, 10)).hasSize(2);
        Plan once = new Plan(Frequency.ONCE, LocalDate.of(2026, 12, 25), null, null, null, HolidayRule.NEXT);
        assertThat(dates(once, 5)).containsExactly(LocalDate.of(2026, 12, 29));
    }

    @Test
    void validationRejectsBadParameters() {
        assertThatThrownBy(() -> Schedule.validate(new Plan(Frequency.WEEKLY, LocalDate.of(2026, 1, 1), 8, null, null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("1-7");
        assertThatThrownBy(() -> Schedule.validate(new Plan(Frequency.MONTHLY, LocalDate.of(2026, 1, 1), null,
                LocalDate.of(2025, 1, 1), null, null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Schedule.frequency("HOURLY")).isInstanceOf(ApiException.class);
    }

    @Provide
    Arbitrary<Frequency> recurring() {
        return Arbitraries.of(Frequency.DAILY, Frequency.WEEKLY, Frequency.BIWEEKLY, Frequency.MONTHLY,
                Frequency.QUARTERLY, Frequency.SEMI_ANNUAL, Frequency.ANNUAL);
    }

    /** Execution dates are business days, strictly increasing and never before the start date. */
    @Property(tries = 300)
    void executionDatesAreIncreasingBusinessDays(@ForAll("recurring") Frequency f,
                                                 @ForAll @IntRange(min = 0, max = 3000) int startOffset,
                                                 @ForAll @IntRange(min = 1, max = 31) int day,
                                                 @ForAll HolidayRule rule) {
        LocalDate start = LocalDate.of(2025, 1, 1).plusDays(startOffset);
        Integer dop = f == Frequency.DAILY ? null
                : Integer.valueOf(f == Frequency.WEEKLY || f == Frequency.BIWEEKLY ? 1 + day % 7 : day);
        Plan p = new Plan(f, start, dop, null, null, rule);
        List<LocalDate> ds = dates(p, 12);
        if (rule != HolidayRule.SKIP) { // SKIP on a weekend weekday legitimately yields no dates (rejected at create)
            assertThat(ds).isNotEmpty();
        }
        for (int i = 0; i < ds.size(); i++) {
            assertThat(CAL.isBusinessDay(ds.get(i))).isTrue();
            assertThat(ds.get(i)).isAfterOrEqualTo(start);
            if (i > 0) {
                assertThat(ds.get(i)).isAfter(ds.get(i - 1));
            }
        }
    }
}
