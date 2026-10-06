package io.meridian.productcatalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import io.meridian.productcatalog.domain.rates.DayCount;
import io.meridian.productcatalog.domain.rates.RateCalculator;
import io.meridian.productcatalog.domain.rates.RateCalculator.Config;
import io.meridian.productcatalog.domain.rates.RateCalculator.Tier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class RateCalculatorTest {
    static final LocalDate D = LocalDate.of(2026, 6, 15);

    static Config cfg(String type, BigDecimal fixed, List<Tier> tiers) {
        return new Config(type, fixed, null, null, null, BigDecimal.ZERO, DayCount.ACT_365, RoundingMode.HALF_EVEN, 2,
                LocalDate.of(2026, 1, 1), null, tiers);
    }

    static List<Tier> threeTiers() {
        return List.of(new Tier(1, bd("0"), bd("1000"), null, null, bd("0.01"), null),
                new Tier(2, bd("1000"), bd("10000"), null, null, bd("0.02"), null),
                new Tier(3, bd("10000"), null, null, null, bd("0.03"), null));
    }

    @Test
    void fixedRateAndInterest() {
        RateCalculator.Result r = RateCalculator.compute(cfg("FIXED", bd("0.045"), null), bd("10000"), D, null);
        assertThat(r.effectiveRate()).isEqualByComparingTo("0.045");
        assertThat(r.annualInterest()).isEqualByComparingTo("450.00");
        assertThat(r.dailyInterest()).isEqualByComparingTo("1.23"); // 450 / 365 = 1.2328..
    }

    @Test
    void tieredAppliesTierRateToWholeBalance() {
        RateCalculator.Result r = RateCalculator.compute(cfg("TIERED", null, threeTiers()), bd("5000"), D, null);
        assertThat(r.effectiveRate()).isEqualByComparingTo("0.02");
        assertThat(r.appliedTier()).isEqualTo(2);
        assertThat(r.annualInterest()).isEqualByComparingTo("100.00");
        // boundary: lower bound inclusive, upper exclusive
        assertThat(RateCalculator.compute(cfg("TIERED", null, threeTiers()), bd("1000"), D, null).appliedTier()).isEqualTo(2);
        assertThat(RateCalculator.compute(cfg("TIERED", null, threeTiers()), bd("999.99"), D, null).appliedTier()).isEqualTo(1);
    }

    @Test
    void bandedBlendsEachSliceAtItsRate() {
        RateCalculator.Result r = RateCalculator.compute(cfg("BANDED", null, threeTiers()), bd("15000"), D, null);
        // 1000*1% + 9000*2% + 5000*3% = 10 + 180 + 150 = 340
        assertThat(r.annualInterest()).isEqualByComparingTo("340.00");
        assertThat(r.bands()).hasSize(3);
        assertThat(r.effectiveRate()).isEqualByComparingTo(bd("340").divide(bd("15000"), 10, RoundingMode.HALF_EVEN));
    }

    @Test
    void steppedUsesDayNumber() {
        List<Tier> steps = List.of(new Tier(1, BigDecimal.ZERO, null, 1, 90, bd("0.05"), null),
                new Tier(2, BigDecimal.ZERO, null, 91, null, bd("0.03"), null));
        assertThat(RateCalculator.compute(cfg("STEPPED", null, steps), bd("1"), LocalDate.of(2026, 3, 31), null)
                .effectiveRate()).isEqualByComparingTo("0.05"); // day 90
        assertThat(RateCalculator.compute(cfg("STEPPED", null, steps), bd("1"), LocalDate.of(2026, 4, 1), null)
                .effectiveRate()).isEqualByComparingTo("0.03"); // day 91
    }

    @Test
    void floatingIsIndexPlusSpreadWithinFloorAndCap() {
        Config c = new Config("FLOATING", null, bd("0.015"), bd("0.02"), bd("0.06"), BigDecimal.ZERO, DayCount.ACT_360,
                RoundingMode.HALF_EVEN, 2, null, null, null);
        assertThat(RateCalculator.compute(c, bd("100"), D, bd("0.043")).effectiveRate()).isEqualByComparingTo("0.058");
        RateCalculator.Result capped = RateCalculator.compute(c, bd("100"), D, bd("0.05"));
        assertThat(capped.effectiveRate()).isEqualByComparingTo("0.06");
        assertThat(capped.capApplied()).isTrue();
        RateCalculator.Result floored = RateCalculator.compute(c, bd("100"), D, bd("0.001"));
        assertThat(floored.effectiveRate()).isEqualByComparingTo("0.02");
        assertThat(floored.floorApplied()).isTrue();
        assertThatThrownBy(() -> RateCalculator.compute(c, bd("100"), D, null))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("index_value_missing");
    }

    @Test
    void minimumBalanceAndEffectiveWindow() {
        Config c = new Config("FIXED", bd("0.05"), null, null, null, bd("500"), DayCount.ACT_365, RoundingMode.HALF_EVEN,
                2, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null);
        RateCalculator.Result low = RateCalculator.compute(c, bd("499.99"), D, null);
        assertThat(low.belowMinimumBalance()).isTrue();
        assertThat(low.effectiveRate()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> RateCalculator.compute(c, bd("1000"), LocalDate.of(2027, 1, 1), null))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("rate_not_effective");
    }

    @Test
    void dayCountConventions() {
        assertThat(DayCount.ACT_360.yearFraction(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .isEqualByComparingTo(bd("30").divide(bd("360"), java.math.MathContext.DECIMAL128));
        assertThat(DayCount.THIRTY_360.yearFraction(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28)))
                .isEqualByComparingTo(bd("28").divide(bd("360"), java.math.MathContext.DECIMAL128));
        assertThat(DayCount.THIRTY_E_360.yearFraction(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)))
                .isEqualByComparingTo("1");
        // ISDA: 2027-12-01..2028-02-01 = 31/365 + 31/366
        BigDecimal isda = DayCount.ACT_ACT_ISDA.yearFraction(LocalDate.of(2027, 12, 1), LocalDate.of(2028, 2, 1));
        BigDecimal expected = bd("31").divide(bd("365"), java.math.MathContext.DECIMAL128)
                .add(bd("31").divide(bd("366"), java.math.MathContext.DECIMAL128));
        assertThat(isda.subtract(expected).abs()).isLessThan(bd("1E-20"));
        assertThat(DayCount.ACT_365L.yearFraction(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 3, 1)))
                .isEqualByComparingTo(bd("29").divide(bd("366"), java.math.MathContext.DECIMAL128));
    }

    // ------------------------------------------------------------------ properties

    @Property(tries = 300)
    void bandedRateLiesBetweenLowestAndHighestBandRate(@ForAll @BigRange(min = "0.01", max = "1000000") BigDecimal balance) {
        RateCalculator.Result r = RateCalculator.compute(cfg("BANDED", null, threeTiers()), balance, D, null);
        assertThat(r.effectiveRate()).isBetween(bd("0.01"), bd("0.03"));
        BigDecimal sumBands = r.bands().stream().map(RateCalculator.Band::portion).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumBands).isEqualByComparingTo(balance);
    }

    @Property(tries = 300)
    void bandedNeverPaysMoreThanTieredForRisingRates(@ForAll @BigRange(min = "0", max = "1000000") BigDecimal balance) {
        BigDecimal banded = RateCalculator.compute(cfg("BANDED", null, threeTiers()), balance, D, null).annualInterest();
        BigDecimal tiered = RateCalculator.compute(cfg("TIERED", null, threeTiers()), balance, D, null).annualInterest();
        assertThat(banded).isLessThanOrEqualTo(tiered);
    }

    @Property(tries = 300)
    void floatingRateAlwaysWithinFloorAndCap(@ForAll @BigRange(min = "-0.05", max = "0.20") BigDecimal index,
                                             @ForAll @BigRange(min = "-0.02", max = "0.05") BigDecimal spread) {
        Config c = new Config("FLOATING", null, spread, bd("0.01"), bd("0.09"), BigDecimal.ZERO, DayCount.ACT_365,
                RoundingMode.HALF_EVEN, 2, null, null, null);
        BigDecimal r = RateCalculator.compute(c, bd("1000"), D, index).effectiveRate();
        assertThat(r).isBetween(bd("0.01"), bd("0.09"));
    }

    @Property(tries = 300)
    void actualDayCountsAreAdditive(@ForAll @IntRange(min = 0, max = 2000) int a, @ForAll @IntRange(min = 0, max = 2000) int b) {
        LocalDate start = LocalDate.of(2024, 1, 1);
        LocalDate mid = start.plusDays(a);
        LocalDate end = mid.plusDays(b);
        for (DayCount dc : List.of(DayCount.ACT_360, DayCount.ACT_365, DayCount.ACT_ACT_ISDA)) {
            BigDecimal whole = dc.yearFraction(start, end);
            BigDecimal parts = dc.yearFraction(start, mid).add(dc.yearFraction(mid, end));
            assertThat(whole.subtract(parts).abs()).as(dc.name()).isLessThan(bd("1E-25"));
        }
    }

    static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }
}
