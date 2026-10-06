package io.meridian.businessday.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BusinessCalendarTest {
    private static final BusinessCalendar STD = BusinessCalendar.standard();

    @Test
    void rollsOverWeekend() {
        assertThat(STD.nextBusinessDay(LocalDate.of(2026, 1, 30))).isEqualTo(LocalDate.of(2026, 2, 2)); // Fri -> Mon
        assertThat(STD.nextBusinessDay(LocalDate.of(2026, 2, 2))).isEqualTo(LocalDate.of(2026, 2, 3));
        assertThat(STD.nextBusinessDay(LocalDate.of(2026, 1, 31))).isEqualTo(LocalDate.of(2026, 2, 2)); // Sat -> Mon
    }

    @Test
    void rollsOverWeekendAndHolidays() {
        BusinessCalendar cal = new BusinessCalendar(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                List.of(LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 28)));
        // Thu 24 Dec -> Fri 25 holiday, weekend, Mon 28 holiday -> Tue 29
        assertThat(cal.nextBusinessDay(LocalDate.of(2026, 12, 24))).isEqualTo(LocalDate.of(2026, 12, 29));
        assertThat(cal.previousBusinessDay(LocalDate.of(2026, 12, 29))).isEqualTo(LocalDate.of(2026, 12, 24));
        assertThat(cal.isBusinessDay(LocalDate.of(2026, 12, 25))).isFalse();
    }

    @Test
    void supportsFridaySaturdayWeekends() {
        BusinessCalendar gulf = new BusinessCalendar(EnumSet.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), Set.of());
        assertThat(gulf.nextBusinessDay(LocalDate.of(2026, 3, 5))).isEqualTo(LocalDate.of(2026, 3, 8)); // Thu -> Sun
        assertThat(gulf.isBusinessDay(LocalDate.of(2026, 3, 8))).isTrue();
    }

    @Test
    void monthEndIsLastBusinessDayOfTheMonth() {
        // 31 Jan 2026 is a Saturday: Friday 30 Jan is the month end
        assertThat(STD.isMonthEnd(LocalDate.of(2026, 1, 30))).isTrue();
        assertThat(STD.isMonthEnd(LocalDate.of(2026, 1, 29))).isFalse();
        BusinessCalendar cal = new BusinessCalendar(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                List.of(LocalDate.of(2026, 4, 30)));
        assertThat(cal.isMonthEnd(LocalDate.of(2026, 4, 29))).isTrue(); // holiday on the 30th
    }

    @Test
    void quarterAndYearEnd() {
        LocalDate dec31 = LocalDate.of(2026, 12, 31); // Thursday
        BusinessCalendar.PeriodEnd pe = STD.periodEnd(dec31);
        assertThat(pe.monthEnd()).isTrue();
        assertThat(pe.quarterEnd()).isTrue();
        assertThat(pe.yearEnd()).isTrue();
        assertThat(pe.defaultCycle()).isEqualTo(CycleType.EOY);
        assertThat(STD.periodEnd(LocalDate.of(2026, 3, 31)).defaultCycle()).isEqualTo(CycleType.EOQ);
        assertThat(STD.periodEnd(LocalDate.of(2026, 2, 27)).defaultCycle()).isEqualTo(CycleType.EOM);
        assertThat(STD.periodEnd(LocalDate.of(2026, 2, 26)).defaultCycle()).isEqualTo(CycleType.EOD);
    }

    @Test
    void rollIsAlwaysForward() {
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 400; i++) {
            LocalDate next = STD.nextBusinessDay(d);
            assertThat(next).isAfter(d);
            assertThat(STD.isBusinessDay(next)).isTrue();
            d = next;
        }
    }

    @Test
    void rejectsAllWeekCalendar() {
        assertThatThrownBy(() -> new BusinessCalendar(EnumSet.allOf(DayOfWeek.class), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cycleInclusion() {
        assertThat(CycleType.EOY.includedCycles()).contains(CycleType.EOD, CycleType.EOM, CycleType.EOQ);
        assertThat(CycleType.EOD.includedCycles()).containsExactly(CycleType.EOD);
        assertThatThrownBy(() -> CycleType.parse("EOW")).isInstanceOf(DomainException.class);
    }

    @Test
    void retryBackoffAndRunOutcome() {
        assertThat(EodPolicy.maxAttempts(EodPolicy.FailurePolicy.RETRY_THEN_STOP, 3)).isEqualTo(4);
        assertThat(EodPolicy.maxAttempts(EodPolicy.FailurePolicy.STOP_CYCLE, 3)).isEqualTo(1);
        Duration base = Duration.ofSeconds(1);
        Duration cap = Duration.ofSeconds(5);
        assertThat(EodPolicy.backoff(2, base, cap)).isEqualTo(Duration.ofSeconds(1));
        assertThat(EodPolicy.backoff(3, base, cap)).isEqualTo(Duration.ofSeconds(2));
        assertThat(EodPolicy.backoff(4, base, cap)).isEqualTo(Duration.ofSeconds(4));
        assertThat(EodPolicy.backoff(9, base, cap)).isEqualTo(cap);

        var ok = new EodPolicy.JobResult("A", true, EodPolicy.JobOutcome.SUCCEEDED);
        var skipped = new EodPolicy.JobResult("B", false, EodPolicy.JobOutcome.SKIPPED);
        var softFail = new EodPolicy.JobResult("C", false, EodPolicy.JobOutcome.FAILED);
        var hardFail = new EodPolicy.JobResult("D", true, EodPolicy.JobOutcome.FAILED);
        var notRun = new EodPolicy.JobResult("E", true, EodPolicy.JobOutcome.NOT_RUN);
        assertThat(EodPolicy.runStatus(List.of(ok, skipped), false)).isEqualTo("SUCCEEDED");
        assertThat(EodPolicy.runStatus(List.of(ok, softFail), false)).isEqualTo("PARTIALLY_SUCCEEDED");
        assertThat(EodPolicy.runStatus(List.of(ok, hardFail), false)).isEqualTo("FAILED");
        assertThat(EodPolicy.runStatus(List.of(ok, notRun), false)).isEqualTo("FAILED");
        assertThat(EodPolicy.runStatus(List.of(ok), true)).isEqualTo("CANCELLED");
    }

    @Test
    void businessDayStateMachine() {
        BusinessDayState.OPEN.requireCutOff();
        BusinessDayState.CUT_OFF.requireStartEod();
        BusinessDayState.EOD_FAILED.requireRoll();
        BusinessDayState.CLOSED.requireReopen();
        assertThatThrownBy(BusinessDayState.CUT_OFF::requireCutOff).extracting("status").isEqualTo(409);
        assertThatThrownBy(BusinessDayState.OPEN::requireRoll).extracting("code").isEqualTo("invalid_transition");
        assertThatThrownBy(BusinessDayState.CLOSED::requireStartEod).extracting("status").isEqualTo(409);
        assertThatThrownBy(BusinessDayState.OPEN::requireReopen).extracting("status").isEqualTo(409);
    }
}
