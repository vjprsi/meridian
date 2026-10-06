package io.meridian.loan.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

class AprTest {
    static final LocalDate START = LocalDate.of(2025, 1, 1);

    @Test
    void singleRepaymentAfterOneYearGivesTheSimpleReturn() {
        BigDecimal apr = Apr.of(List.of(new Apr.Flow(START, new BigDecimal("-1000")),
                new Apr.Flow(START.plusDays(365), new BigDecimal("1100"))));
        assertThat(apr).isEqualByComparingTo("0.100000");
    }

    @Test
    void monthlyAnnuityAprIsTheEffectiveAnnualRate() {
        ScheduleGenerator.Schedule s = ScheduleGenerator.generate(new ScheduleGenerator.Terms(new BigDecimal("10000"),
                new BigDecimal("0.12"), DayCount.THIRTY_360, ScheduleGenerator.Type.ANNUITY, Frequency.MONTHLY, 12, START,
                null, null, null, null, 2, BusinessCalendar.weekendsOnly(), BusinessCalendar.Convention.NONE));
        BigDecimal apr = Apr.ofSchedule(new BigDecimal("10000"), START, s.lines(), null);
        // 12% nominal monthly = (1.01)^12 - 1 = 12.68% effective (actual-day XIRR differs by a few basis points)
        assertThat(apr.doubleValue()).isCloseTo(0.1268, Offset.offset(0.003));
    }

    @Test
    void upfrontFeesRaiseTheApr() {
        ScheduleGenerator.Schedule s = ScheduleGenerator.generate(new ScheduleGenerator.Terms(new BigDecimal("5000"),
                new BigDecimal("0.10"), DayCount.ACT_365, ScheduleGenerator.Type.ANNUITY, Frequency.MONTHLY, 24, START,
                null, null, null, null, 2, BusinessCalendar.weekendsOnly(), BusinessCalendar.Convention.FOLLOWING));
        BigDecimal noFee = Apr.ofSchedule(new BigDecimal("5000"), START, s.lines(), null);
        BigDecimal fee = Apr.ofSchedule(new BigDecimal("4900"), START, s.lines(), null);
        assertThat(fee).isGreaterThan(noFee);
        assertThat(fee.subtract(noFee).doubleValue()).isBetween(0.015, 0.03);
    }

    @Test
    void zeroCostCreditHasZeroApr() {
        ScheduleGenerator.Schedule s = ScheduleGenerator.generate(new ScheduleGenerator.Terms(new BigDecimal("400"),
                BigDecimal.ZERO, DayCount.ACT_365, ScheduleGenerator.Type.ANNUITY, Frequency.FORTNIGHTLY, 4, START,
                null, null, null, null, 2, BusinessCalendar.weekendsOnly(), BusinessCalendar.Convention.NONE));
        assertThat(Apr.ofSchedule(new BigDecimal("400"), START, s.lines(), null).abs().doubleValue()).isLessThan(1e-6);
    }

    @Test
    void flowsWithoutBothSignsAreRejected() {
        assertThatThrownBy(() -> Apr.of(List.of(new Apr.Flow(START, new BigDecimal("100")),
                new Apr.Flow(START.plusDays(10), new BigDecimal("100"))))).isInstanceOf(IllegalArgumentException.class);
    }

    @Property(tries = 200)
    void aprIsAtLeastTheNominalRateWhenFeesArePositive(@ForAll @BigRange(min = "0.01", max = "0.40") @net.jqwik.api.constraints.Scale(4) BigDecimal rate,
                                                      @ForAll @IntRange(min = 3, max = 60) int months,
                                                      @ForAll @BigRange(min = "0.001", max = "0.05") @net.jqwik.api.constraints.Scale(4) BigDecimal feePct) {
        BigDecimal principal = new BigDecimal("10000");
        ScheduleGenerator.Schedule s = ScheduleGenerator.generate(new ScheduleGenerator.Terms(principal, rate.setScale(4, java.math.RoundingMode.HALF_EVEN),
                DayCount.ACT_365, ScheduleGenerator.Type.ANNUITY, Frequency.MONTHLY, months, START, null, null, null, null, 2,
                BusinessCalendar.weekendsOnly(), BusinessCalendar.Convention.NONE));
        BigDecimal fee = principal.multiply(feePct).setScale(2, java.math.RoundingMode.HALF_EVEN);
        BigDecimal apr = Apr.ofSchedule(principal.subtract(fee), START, s.lines(), null);
        assertThat(apr.doubleValue()).isGreaterThan(rate.doubleValue() - 0.002);
        // NPV at the APR is (close to) zero
        double npv = -principal.subtract(fee).doubleValue();
        for (ScheduleGenerator.Line l : s.lines()) {
            double t = java.time.temporal.ChronoUnit.DAYS.between(START, l.dueDate()) / 365.0;
            npv += l.total().doubleValue() / Math.pow(1 + apr.doubleValue(), t);
        }
        assertThat(Math.abs(npv)).isLessThan(1.0);
    }
}
