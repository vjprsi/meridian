package io.meridian.alm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.meridian.alm.domain.IrrbbShocks.Params;
import io.meridian.alm.domain.IrrbbShocks.ShockType;
import io.meridian.alm.domain.Position.RateType;
import io.meridian.alm.domain.Position.Side;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import org.junit.jupiter.api.Test;

class IrrbbTest {
    static final Params STD = Params.standard(null, null);
    static final LocalDate AS_OF = LocalDate.of(2026, 1, 1);

    static double bps(ShockType s, double t) {
        return IrrbbShocks.delta(s, t, STD).doubleValue() * 10000;
    }

    @Test
    void standardShockShapes() {
        assertThat(bps(ShockType.PARALLEL_UP, 5)).isCloseTo(200, within(1e-9));
        assertThat(bps(ShockType.PARALLEL_DOWN, 0.1)).isCloseTo(-200, within(1e-9));
        assertThat(bps(ShockType.SHORT_UP, 0)).isCloseTo(250, within(1e-9));
        assertThat(bps(ShockType.SHORT_UP, 4)).isCloseTo(250 * Math.exp(-1), within(1e-9));
        assertThat(bps(ShockType.SHORT_DOWN, 0)).isCloseTo(-250, within(1e-9));
        // steepener: short end down 0.65 * 250, long end up towards 0.9 * 100
        assertThat(bps(ShockType.STEEPENER, 0)).isCloseTo(-162.5, within(1e-9));
        assertThat(bps(ShockType.STEEPENER, 100)).isCloseTo(90, within(0.01));
        // flattener: short end up 0.8 * 250, long end down towards 0.6 * 100
        assertThat(bps(ShockType.FLATTENER, 0)).isCloseTo(200, within(1e-9));
        assertThat(bps(ShockType.FLATTENER, 100)).isCloseTo(-60, within(0.01));
        Curve custom = new Curve(List.of(new Curve.Point(365, new BigDecimal("100")), new Curve.Point(3650, new BigDecimal("300"))));
        assertThat(IrrbbShocks.delta(ShockType.CUSTOM, 5.5, Params.standard(null, custom)).doubleValue() * 10000)
                .isCloseTo(200, within(1e-6));
    }

    @Test
    void postShockFloor() {
        assertThat(IrrbbShocks.floor(0)).isEqualByComparingTo("-0.01");
        assertThat(IrrbbShocks.floor(10)).isEqualByComparingTo("-0.005");
        assertThat(IrrbbShocks.floor(30)).isEqualByComparingTo("0");
        // base 0.5%, shock -200bp at t=0 -> floored at -1%
        assertThat(IrrbbShocks.shocked(new BigDecimal("0.005"), new BigDecimal("-0.02"), 0)).isEqualByComparingTo("-0.01");
        // base already below the floor: never shocked below the base
        assertThat(IrrbbShocks.shocked(new BigDecimal("-0.015"), new BigDecimal("-0.02"), 0)).isEqualByComparingTo("-0.015");
        assertThat(IrrbbShocks.shocked(new BigDecimal("0.03"), new BigDecimal("0.02"), 1)).isEqualByComparingTo("0.05");
    }

    @Property
    void rotationsAreBoundedByTheirComponents(@ForAll @DoubleRange(min = 0, max = 50) double t) {
        assertThat(Math.abs(bps(ShockType.STEEPENER, t))).isLessThanOrEqualTo(0.65 * 250 + 0.9 * 100 + 1e-9);
        assertThat(Math.abs(bps(ShockType.FLATTENER, t))).isLessThanOrEqualTo(0.8 * 250 + 0.6 * 100 + 1e-9);
        assertThat(bps(ShockType.SHORT_UP, t)).isBetween(0.0, 250.0);
    }

    @Test
    void eveOfAZeroCouponAssetUnderParallelUp() {
        Curve flat = new Curve(List.of(new Curve.Point(365, new BigDecimal("0.03"))));
        Position zero = new Position("BOND", null, null, Side.ASSET, new BigDecimal("1000000"), "EUR", BigDecimal.ZERO,
                RateType.FIXED, null, LocalDate.of(2027, 1, 1), false, 12);
        SensitivityCalculator.Result r = SensitivityCalculator.eve(AS_OF, List.of(zero), flat, ShockType.PARALLEL_UP, STD);
        // 1,000,000 * exp(-0.03) and * exp(-0.05)
        assertThat(r.base()).isEqualByComparingTo("970445.53");
        assertThat(r.shocked()).isEqualByComparingTo("951229.42");
        assertThat(r.delta()).isEqualByComparingTo("-19216.11");
        Position deposit = new Position("TD", null, null, Side.LIABILITY, new BigDecimal("1000000"), "EUR", BigDecimal.ZERO,
                RateType.FIXED, null, LocalDate.of(2027, 1, 1), false, 12);
        SensitivityCalculator.Result hedged = SensitivityCalculator.eve(AS_OF, List.of(zero, deposit), flat,
                ShockType.PARALLEL_UP, STD);
        assertThat(hedged.base()).isEqualByComparingTo("0.00");
        assertThat(hedged.delta()).isEqualByComparingTo("0.00");
    }

    @Test
    void couponCashFlowsAndEve() {
        Position bond = new Position("BOND", null, null, Side.ASSET, new BigDecimal("1000"), "EUR", new BigDecimal("0.05"),
                RateType.FIXED, null, LocalDate.of(2028, 1, 1), false, 12);
        List<SensitivityCalculator.CashFlow> flows = SensitivityCalculator.cashFlows(AS_OF, bond);
        assertThat(flows).hasSize(2);
        assertThat(flows.get(0).date()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(flows.get(0).amount()).isEqualByComparingTo("50");
        assertThat(flows.get(1).amount()).isEqualByComparingTo("1050");
        Curve zeroCurve = new Curve(List.of(new Curve.Point(1, BigDecimal.ZERO)));
        assertThat(SensitivityCalculator.eve(AS_OF, List.of(bond), zeroCurve, ShockType.PARALLEL_UP, STD).base())
                .isEqualByComparingTo("1100.00");
    }

    @Test
    void niiOverTwelveMonthsWithRepricingAndPassThrough() {
        Position floating = new Position("FRN", null, null, Side.ASSET, new BigDecimal("1000000"), "EUR",
                new BigDecimal("0.05"), RateType.FLOATING, LocalDate.of(2026, 4, 1), LocalDate.of(2030, 1, 1), false, 3);
        Position savings = new Position("SAV", null, null, Side.LIABILITY, new BigDecimal("800000"), "EUR",
                new BigDecimal("0.02"), RateType.FLOATING, null, null, true, 12);
        SensitivityCalculator.Result full = SensitivityCalculator.nii(AS_OF, List.of(floating, savings), 12,
                ShockType.PARALLEL_UP, STD, p -> BigDecimal.ONE);
        assertThat(full.base()).isEqualByComparingTo("34000.00");
        // asset reprices after 90 days: 1,000,000 * 2% * (1 - 90/365) = 15,068.49; savings reprice now: -16,000
        assertThat(full.shocked()).isEqualByComparingTo("33068.49");
        assertThat(full.delta()).isEqualByComparingTo("-931.51");
        SensitivityCalculator.Result half = SensitivityCalculator.nii(AS_OF, List.of(floating, savings), 12,
                ShockType.PARALLEL_UP, STD, p -> p.nonMaturity() ? new BigDecimal("0.5") : BigDecimal.ONE);
        assertThat(half.delta()).isEqualByComparingTo("7068.49");
        Position fixed = new Position("MORT", null, null, Side.ASSET, new BigDecimal("500000"), "EUR",
                new BigDecimal("0.04"), RateType.FIXED, null, LocalDate.of(2036, 1, 1), false, 12);
        assertThat(SensitivityCalculator.nii(AS_OF, List.of(fixed), 12, ShockType.PARALLEL_DOWN, STD, null).delta())
                .isEqualByComparingTo("0.00");
    }
}
