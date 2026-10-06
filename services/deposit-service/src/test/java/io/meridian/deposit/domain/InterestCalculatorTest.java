package io.meridian.deposit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.deposit.domain.InterestCalculator.RateConfig;
import io.meridian.deposit.domain.InterestCalculator.Structure;
import io.meridian.deposit.domain.InterestCalculator.Tier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class InterestCalculatorTest {
    static final LocalDate DAY = LocalDate.of(2026, 3, 10);

    @Test
    void fixedRateAct365AndAct360() {
        RateConfig act365 = RateConfig.fixed("CREDIT_INTEREST", new BigDecimal("0.0365"), DayCount.ACT_365);
        RateConfig act360 = RateConfig.fixed("CREDIT_INTEREST", new BigDecimal("0.036"), DayCount.ACT_360);
        assertThat(InterestCalculator.dailyInterest(act365, new BigDecimal("10000"), DAY)).isEqualByComparingTo("1.00000000");
        assertThat(InterestCalculator.dailyInterest(act360, new BigDecimal("10000"), DAY)).isEqualByComparingTo("1.00000000");
        assertThat(InterestCalculator.dailyInterest(act365, new BigDecimal("10000"), DAY).scale()).isEqualTo(8);
    }

    @Test
    void actActUsesLeapYearBasis() {
        RateConfig cfg = RateConfig.fixed("CREDIT_INTEREST", new BigDecimal("0.0366"), DayCount.ACT_ACT_ISDA);
        assertThat(InterestCalculator.dailyInterest(cfg, new BigDecimal("10000"), LocalDate.of(2028, 2, 29)))
                .isEqualByComparingTo("1.00000000");
        assertThat(DayCount.ACT_ACT_ISDA.yearFraction(LocalDate.of(2027, 12, 31), LocalDate.of(2028, 1, 2)))
                .isEqualByComparingTo(BigDecimal.ONE.divide(BigDecimal.valueOf(365), DayCount.MC)
                        .add(BigDecimal.ONE.divide(BigDecimal.valueOf(366), DayCount.MC)));
    }

    @Test
    void tieredAppliesTheBandRateToTheWholeBalance() {
        RateConfig cfg = new RateConfig("CREDIT_INTEREST", Structure.TIERED, null, List.of(
                new Tier(BigDecimal.ZERO, new BigDecimal("1000"), new BigDecimal("0.01")),
                new Tier(new BigDecimal("1000"), new BigDecimal("10000"), new BigDecimal("0.02")),
                new Tier(new BigDecimal("10000"), null, new BigDecimal("0.03"))), DayCount.ACT_365, null, null, null);
        assertThat(InterestCalculator.annualRate(cfg, new BigDecimal("999.99"))).isEqualByComparingTo("0.01");
        assertThat(InterestCalculator.annualRate(cfg, new BigDecimal("1000"))).isEqualByComparingTo("0.02");
        assertThat(InterestCalculator.annualRate(cfg, new BigDecimal("50000"))).isEqualByComparingTo("0.03");
        // 5000 * 2% / 365
        assertThat(InterestCalculator.dailyInterest(cfg, new BigDecimal("5000"), DAY)).isEqualByComparingTo("0.27397260");
    }

    @Test
    void bandedAppliesEachBandRateToItsSlice() {
        RateConfig cfg = new RateConfig("CREDIT_INTEREST", Structure.BANDED, null, List.of(
                new Tier(BigDecimal.ZERO, new BigDecimal("1000"), new BigDecimal("0.0365")),
                new Tier(new BigDecimal("1000"), null, new BigDecimal("0.073"))), DayCount.ACT_365, null, null, null);
        // 1000 * 3.65% / 365 = 0.10 ; 2000 * 7.3% / 365 = 0.40
        assertThat(InterestCalculator.dailyInterest(cfg, new BigDecimal("3000"), DAY)).isEqualByComparingTo("0.50000000");
        assertThat(InterestCalculator.annualRate(cfg, new BigDecimal("3000")).setScale(10, java.math.RoundingMode.HALF_EVEN))
                .isEqualByComparingTo("0.0608333333");
    }

    @Test
    void minimumBalanceFloorAndCapAndOverrides() {
        RateConfig cfg = new RateConfig("CREDIT_INTEREST", Structure.FIXED, new BigDecimal("0.05"), List.of(),
                DayCount.ACT_365, new BigDecimal("100"), null, new BigDecimal("0.04"));
        assertThat(InterestCalculator.dailyInterest(cfg, new BigDecimal("99.99"), DAY)).isZero();
        assertThat(InterestCalculator.annualRate(cfg, new BigDecimal("500"))).isEqualByComparingTo("0.04"); // capped
        assertThat(InterestCalculator.annualRate(cfg.withOverride(new BigDecimal("0.03"), null), new BigDecimal("500")))
                .isEqualByComparingTo("0.03");
        assertThat(InterestCalculator.annualRate(cfg.withOverride(null, new BigDecimal("-0.02")), new BigDecimal("500")))
                .isEqualByComparingTo("0.03");
    }

    @Test
    void invalidConfigurationsAreRejected() {
        assertThatThrownBy(() -> new RateConfig("X", Structure.TIERED, null, List.of(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Tier(new BigDecimal("10"), new BigDecimal("5"), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(DayCount.parse("ACT/360")).isEqualTo(DayCount.ACT_360);
        assertThat(DayCount.parse("30E/360")).isEqualTo(DayCount.THIRTY_E_360);
    }

    @Test
    void thirty360CountsThirtyDayMonths() {
        assertThat(DayCount.thirty360(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 31), false)).isEqualTo(60);
        assertThat(DayCount.thirty360(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31), true)).isEqualTo(32);
    }

    /** Daily accruals over a period add up to the period interest (within rounding of 8 dp per day). */
    @Property(tries = 200)
    void dailyAccrualsSumToPeriodInterest(@ForAll @BigRange(min = "0", max = "10000000") BigDecimal balance,
                                          @ForAll @IntRange(min = 1, max = 400) int days,
                                          @ForAll @BigRange(min = "0", max = "0.25") BigDecimal rate) {
        RateConfig cfg = RateConfig.fixed("CREDIT_INTEREST", rate, DayCount.ACT_365);
        BigDecimal sum = InterestCalculator.project(cfg, balance, DAY, days).stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal whole = InterestCalculator.interest(cfg, balance, DAY, DAY.plusDays(days));
        assertThat(sum.subtract(whole).abs()).isLessThanOrEqualTo(new BigDecimal("0.00000001").multiply(BigDecimal.valueOf(days)));
    }

    /** Banded interest never exceeds what the top band rate would pay and is monotone in the balance. */
    @Property(tries = 200)
    void bandedInterestIsMonotoneAndBounded(@ForAll @BigRange(min = "0", max = "1000000") BigDecimal a,
                                            @ForAll @BigRange(min = "0", max = "1000000") BigDecimal b) {
        RateConfig cfg = new RateConfig("CREDIT_INTEREST", Structure.BANDED, null, List.of(
                new Tier(BigDecimal.ZERO, new BigDecimal("5000"), new BigDecimal("0.01")),
                new Tier(new BigDecimal("5000"), new BigDecimal("50000"), new BigDecimal("0.02")),
                new Tier(new BigDecimal("50000"), null, new BigDecimal("0.035"))), DayCount.ACT_360, null, null, null);
        BigDecimal lo = a.min(b);
        BigDecimal hi = a.max(b);
        BigDecimal iLo = InterestCalculator.dailyInterest(cfg, lo, DAY);
        BigDecimal iHi = InterestCalculator.dailyInterest(cfg, hi, DAY);
        assertThat(iLo).isLessThanOrEqualTo(iHi);
        BigDecimal topRate = InterestCalculator.dailyInterest(RateConfig.fixed("X", new BigDecimal("0.035"), DayCount.ACT_360), hi, DAY);
        assertThat(iHi).isLessThanOrEqualTo(topRate);
        assertThat(iHi.signum()).isGreaterThanOrEqualTo(0);
    }
}
