package io.meridian.loan.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreditAndDelinquencyTest {

    @Test
    void bucketsFollowTheDpdBands() {
        assertThat(Delinquency.bucket(0)).isEqualTo("CURRENT");
        assertThat(Delinquency.bucket(1)).isEqualTo("DPD_1_29");
        assertThat(Delinquency.bucket(29)).isEqualTo("DPD_1_29");
        assertThat(Delinquency.bucket(30)).isEqualTo("DPD_30_59");
        assertThat(Delinquency.bucket(60)).isEqualTo("DPD_60_89");
        assertThat(Delinquency.bucket(90)).isEqualTo("DPD_90_119");
        assertThat(Delinquency.bucket(120)).isEqualTo("DPD_120_179");
        assertThat(Delinquency.bucket(400)).isEqualTo("DPD_180_PLUS");
    }

    @Test
    void eclStageHints() {
        assertThat(Delinquency.eclStage(0, false, false)).isEqualTo("STAGE_1");
        assertThat(Delinquency.eclStage(31, false, false)).isEqualTo("STAGE_2");
        assertThat(Delinquency.eclStage(0, true, false)).isEqualTo("STAGE_2");
        assertThat(Delinquency.eclStage(91, false, false)).isEqualTo("STAGE_3");
        assertThat(Delinquency.eclStage(0, false, true)).isEqualTo("STAGE_3");
    }

    @Test
    void averageDailyBalanceWeighsEachDay() {
        LocalDate start = LocalDate.of(2026, 3, 1);
        LocalDate end = LocalDate.of(2026, 3, 30); // 30 days
        // 0 for 10 days, 300 for 10 days, 150 for 10 days -> (0 + 3000 + 1500) / 30 = 150
        BigDecimal adb = CreditLineMath.averageDailyBalance(BigDecimal.ZERO, List.of(
                new CreditLineMath.Movement(LocalDate.of(2026, 3, 11), new BigDecimal("300")),
                new CreditLineMath.Movement(LocalDate.of(2026, 3, 21), new BigDecimal("-150"))), start, end);
        assertThat(adb).isEqualByComparingTo("150");
        assertThat(CreditLineMath.interest(adb, new BigDecimal("0.365"), 30, DayCount.ACT_365, end, 2))
                .isEqualByComparingTo("4.50");
    }

    @Test
    void minimumPaymentUsesFloorPercentageAndBalance() {
        assertThat(CreditLineMath.minimumPayment(new BigDecimal("1000"), new BigDecimal("0.05"), new BigDecimal("25"), 2))
                .isEqualByComparingTo("50.00");
        assertThat(CreditLineMath.minimumPayment(new BigDecimal("200"), new BigDecimal("0.05"), new BigDecimal("25"), 2))
                .isEqualByComparingTo("25.00");
        assertThat(CreditLineMath.minimumPayment(new BigDecimal("12"), new BigDecimal("0.05"), new BigDecimal("25"), 2))
                .isEqualByComparingTo("12.00");
        assertThat(CreditLineMath.minimumPayment(BigDecimal.ZERO, new BigDecimal("0.05"), new BigDecimal("25"), 2))
                .isEqualByComparingTo("0");
    }

    @Test
    void lifecycleGuards() {
        assertThat(Lifecycles.allowed(Lifecycles.LOAN, "PENDING_DISBURSEMENT", "ACTIVE")).isTrue();
        assertThat(Lifecycles.allowed(Lifecycles.LOAN, "CLOSED", "ACTIVE")).isFalse();
        assertThat(Lifecycles.allowed(Lifecycles.RESTRUCTURE, "PROPOSED", "APPLIED")).isFalse();
        assertThat(Lifecycles.allowed(Lifecycles.CREDIT_LINE, "FROZEN", "ACTIVE")).isTrue();
    }

    @Test
    void frequencyPeriodsAndDates() {
        LocalDate start = LocalDate.of(2026, 1, 31);
        assertThat(Frequency.MONTHLY.periods(12, start)).isEqualTo(12);
        assertThat(Frequency.QUARTERLY.periods(12, start)).isEqualTo(4);
        assertThat(Frequency.FORTNIGHTLY.periods(2, start)).isEqualTo(4);
        assertThat(Frequency.MONTHLY.nth(start, 1)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(Frequency.MONTHLY.nth(start, 2)).isEqualTo(LocalDate.of(2026, 3, 31));
    }
}
