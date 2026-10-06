package io.meridian.collateral.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class LtvCalculatorTest {

    @Test
    void ltvIsExposureOverAllAllocatedValues() {
        LtvCalculator.Result r = LtvCalculator.calculate(new BigDecimal("140000"),
                List.of(new BigDecimal("150000"), new BigDecimal("50000")), new BigDecimal("0.80"));
        assertThat(r.securedValue()).isEqualByComparingTo("200000");
        assertThat(r.ltv()).isEqualByComparingTo("0.70");
        assertThat(r.status()).isEqualTo(LtvCalculator.Status.OK);
    }

    @Test
    void warningFromNinetyPercentOfTheLimitAndBreachAboveIt() {
        BigDecimal max = new BigDecimal("0.80");
        assertThat(LtvCalculator.calculate(new BigDecimal("71999"), new BigDecimal("100000"), max).status())
                .isEqualTo(LtvCalculator.Status.OK);
        assertThat(LtvCalculator.calculate(new BigDecimal("72000"), new BigDecimal("100000"), max).status())
                .isEqualTo(LtvCalculator.Status.WARNING);
        assertThat(LtvCalculator.calculate(new BigDecimal("80000"), new BigDecimal("100000"), max).status())
                .isEqualTo(LtvCalculator.Status.WARNING);
        assertThat(LtvCalculator.calculate(new BigDecimal("80001"), new BigDecimal("100000"), max).status())
                .isEqualTo(LtvCalculator.Status.BREACH);
    }

    @Test
    void noLimitIsAlwaysOkAndZeroExposureIsZeroLtv() {
        assertThat(LtvCalculator.calculate(new BigDecimal("500"), new BigDecimal("100"), null).status())
                .isEqualTo(LtvCalculator.Status.OK);
        assertThat(LtvCalculator.calculate(BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("0.5")).ltv())
                .isEqualByComparingTo("0");
    }

    @Test
    void unsecuredExposureIsCappedAndBreaches() {
        LtvCalculator.Result r = LtvCalculator.calculate(new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("0.8"));
        assertThat(r.ltv()).isEqualByComparingTo(LtvCalculator.UNSECURED_LTV);
        assertThat(r.status()).isEqualTo(LtvCalculator.Status.BREACH);
    }

    @Test
    void worsenedOnlyWhenStatusGetsWorse() {
        assertThat(LtvCalculator.worsened(null, LtvCalculator.Status.WARNING)).isTrue();
        assertThat(LtvCalculator.worsened(LtvCalculator.Status.WARNING, LtvCalculator.Status.BREACH)).isTrue();
        assertThat(LtvCalculator.worsened(LtvCalculator.Status.BREACH, LtvCalculator.Status.BREACH)).isFalse();
        assertThat(LtvCalculator.worsened(LtvCalculator.Status.BREACH, LtvCalculator.Status.OK)).isFalse();
        assertThat(LtvCalculator.worsened(null, LtvCalculator.Status.OK)).isFalse();
    }

    @Property
    void ltvIsMonotonicInExposure(@ForAll @LongRange(min = 0, max = 10_000_000) long a,
                                  @ForAll @LongRange(min = 0, max = 10_000_000) long b,
                                  @ForAll @LongRange(min = 1, max = 10_000_000) long secured) {
        BigDecimal lo = BigDecimal.valueOf(Math.min(a, b));
        BigDecimal hi = BigDecimal.valueOf(Math.max(a, b));
        BigDecimal s = BigDecimal.valueOf(secured);
        LtvCalculator.Result rl = LtvCalculator.calculate(lo, s, new BigDecimal("0.8"));
        LtvCalculator.Result rh = LtvCalculator.calculate(hi, s, new BigDecimal("0.8"));
        assertThat(rh.ltv()).isGreaterThanOrEqualTo(rl.ltv());
        assertThat(rh.status().ordinal()).isGreaterThanOrEqualTo(rl.status().ordinal());
    }

    @Property
    void moreCollateralNeverIncreasesLtv(@ForAll @LongRange(min = 1, max = 10_000_000) long exposure,
                                         @ForAll @LongRange(min = 1, max = 10_000_000) long v1,
                                         @ForAll @LongRange(min = 0, max = 10_000_000) long extra) {
        BigDecimal e = BigDecimal.valueOf(exposure);
        BigDecimal one = LtvCalculator.calculate(e, List.of(BigDecimal.valueOf(v1)), null).ltv();
        BigDecimal two = LtvCalculator.calculate(e, List.of(BigDecimal.valueOf(v1), BigDecimal.valueOf(extra)), null).ltv();
        assertThat(two).isLessThanOrEqualTo(one);
    }

    // ------------------------------------------------------------------ liquidation waterfall

    @Test
    void liquidationPaysFirstChargeFirstAndCapsAtOutstanding() {
        UUID loanA = UUID.randomUUID();
        UUID loanB = UUID.randomUUID();
        UUID l1 = UUID.randomUUID();
        UUID l2 = UUID.randomUUID();
        LiquidationAllocator.Result r = LiquidationAllocator.allocate(new BigDecimal("1000"), List.of(
                new LiquidationAllocator.Claim(l2, loanB, 2, 0, new BigDecimal("800")),
                new LiquidationAllocator.Claim(l1, loanA, 1, 1, new BigDecimal("600"))));
        assertThat(r.allocations()).hasSize(2);
        assertThat(r.allocations().get(0).linkId()).isEqualTo(l1);
        assertThat(r.allocations().get(0).amount()).isEqualByComparingTo("600");
        assertThat(r.allocations().get(1).amount()).isEqualByComparingTo("400");
        assertThat(r.surplus()).isEqualByComparingTo("0");
    }

    @Test
    void linksToTheSameExposureShareTheCapAndSurplusIsReturned() {
        UUID loan = UUID.randomUUID();
        LiquidationAllocator.Result r = LiquidationAllocator.allocate(new BigDecimal("1000"), List.of(
                new LiquidationAllocator.Claim(UUID.randomUUID(), loan, 1, 0, new BigDecimal("300")),
                new LiquidationAllocator.Claim(UUID.randomUUID(), loan, 1, 1, new BigDecimal("300"))));
        assertThat(r.allocations()).hasSize(1);
        assertThat(r.surplus()).isEqualByComparingTo("700");
        assertThatThrownBy(() -> LiquidationAllocator.allocate(new BigDecimal("-1"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void liquidationConservesProceeds(@ForAll @LongRange(min = 0, max = 1_000_000) long proceeds,
                                      @ForAll @LongRange(min = 0, max = 1_000_000) long o1,
                                      @ForAll @LongRange(min = 0, max = 1_000_000) long o2) {
        LiquidationAllocator.Result r = LiquidationAllocator.allocate(BigDecimal.valueOf(proceeds), List.of(
                new LiquidationAllocator.Claim(UUID.randomUUID(), UUID.randomUUID(), 1, 0, BigDecimal.valueOf(o1)),
                new LiquidationAllocator.Claim(UUID.randomUUID(), UUID.randomUUID(), 2, 1, BigDecimal.valueOf(o2))));
        BigDecimal paid = r.allocations().stream().map(LiquidationAllocator.Allocation::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(paid.add(r.surplus())).isEqualByComparingTo(BigDecimal.valueOf(proceeds));
        assertThat(r.surplus().signum()).isGreaterThanOrEqualTo(0);
        assertThat(paid).isLessThanOrEqualTo(BigDecimal.valueOf(o1 + o2));
    }
}
