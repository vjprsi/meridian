package io.meridian.treasurydeal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/** Worked examples for money market, bond and FX arithmetic. */
class DealMathTest {
    static final LocalDate V = LocalDate.of(2026, 10, 6);

    @Test
    void moneyMarketInterestAct360AndAct365() {
        BigDecimal p = new BigDecimal("1000000");
        BigDecimal r = new BigDecimal("0.05");
        // 90 days: 1,000,000 x 5% x 90/360 = 12,500.00 ; x 90/365 = 12,328.77
        assertThat(MoneyMarketMath.interest(p, r, DayCount.ACT_360, V, V.plusDays(90), "EUR")).isEqualByComparingTo("12500.00");
        assertThat(MoneyMarketMath.interest(p, r, DayCount.ACT_365, V, V.plusDays(90), "GBP")).isEqualByComparingTo("12328.77");
        // JPY has no minor unit
        assertThat(MoneyMarketMath.interest(new BigDecimal("100000000"), new BigDecimal("0.001"), DayCount.ACT_365, V,
                V.plusDays(30), "JPY")).isEqualByComparingTo("8219");
    }

    @Test
    void accrualIncludesAsOfDayAndStopsAtMaturity() {
        BigDecimal p = new BigDecimal("360000");
        BigDecimal r = new BigDecimal("0.10");
        LocalDate m = V.plusDays(30);
        // 100 per day on ACT/360; as of the value date one day has accrued
        assertThat(MoneyMarketMath.accruedThrough(p, r, DayCount.ACT_360, V, m, V, "EUR")).isEqualByComparingTo("100.00");
        assertThat(MoneyMarketMath.accruedThrough(p, r, DayCount.ACT_360, V, m, V.plusDays(9), "EUR")).isEqualByComparingTo("1000.00");
        assertThat(MoneyMarketMath.accruedThrough(p, r, DayCount.ACT_360, V, m, m.plusDays(5), "EUR")).isEqualByComparingTo("3000.00");
    }

    @Property
    void accrualIsMonotonicAndBoundedByInterest(@ForAll @IntRange(min = 0, max = 400) int day) {
        BigDecimal p = new BigDecimal("2500000");
        BigDecimal r = new BigDecimal("0.0425");
        LocalDate m = V.plusDays(181);
        BigDecimal total = MoneyMarketMath.interest(p, r, DayCount.ACT_365, V, m, "USD");
        BigDecimal a = MoneyMarketMath.accruedThrough(p, r, DayCount.ACT_365, V, m, V.plusDays(day), "USD");
        BigDecimal b = MoneyMarketMath.accruedThrough(p, r, DayCount.ACT_365, V, m, V.plusDays(day + 1), "USD");
        assertThat(a).isLessThanOrEqualTo(b);
        assertThat(b).isLessThanOrEqualTo(total);
    }

    @Test
    void thirty360DayCounts() {
        assertThat(DayCount.thirty360(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 31), false)).isEqualTo(60);
        assertThat(DayCount.thirty360(LocalDate.of(2026, 1, 30), LocalDate.of(2026, 3, 31), true)).isEqualTo(60);
        assertThat(DayCount.thirty360(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 3, 31), false)).isEqualTo(76);
        assertThat(DayCount.thirty360(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 3, 31), true)).isEqualTo(75);
    }

    @Test
    void bondAccruedInterestThirty360AndActAct() {
        LocalDate maturity = LocalDate.of(2030, 3, 15);
        LocalDate settle = LocalDate.of(2026, 6, 20);
        BondMath.Terms t30 = new BondMath.Terms(new BigDecimal("0.06"), 2, LocalDate.of(2020, 3, 15), maturity, DayCount.THIRTY_360);
        assertThat(BondMath.previousCoupon(t30, settle)).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(BondMath.nextCoupon(t30, settle)).isEqualTo(LocalDate.of(2026, 9, 15));
        // 95 days on 30/360: 6 x 95 / 360 = 1.58333...
        assertThat(BondMath.accruedPct(t30, settle).setScale(8, java.math.RoundingMode.HALF_EVEN)).isEqualByComparingTo("1.58333333");
        // ACT/ACT (ICMA): 3 x 97 / 184 = 1.58152174
        BondMath.Terms tAct = new BondMath.Terms(new BigDecimal("0.06"), 2, LocalDate.of(2020, 3, 15), maturity, DayCount.ACT_ACT_ISDA);
        assertThat(BondMath.accruedPct(tAct, settle).setScale(8, java.math.RoundingMode.HALF_EVEN)).isEqualByComparingTo("1.58152174");
        assertThat(BondMath.accruedAmount(tAct, new BigDecimal("1000000"), settle, "EUR")).isEqualByComparingTo("15815.22");
        assertThat(BondMath.dirtyPrice(tAct, new BigDecimal("99.50"), settle)).isEqualByComparingTo("101.0815217391");
        // on a coupon date nothing has accrued
        assertThat(BondMath.accruedPct(t30, LocalDate.of(2026, 9, 15))).isZero();
    }

    @Test
    void simpleYieldAndDiscountInstruments() {
        LocalDate settle = LocalDate.of(2026, 1, 1);
        BondMath.Terms t = new BondMath.Terms(new BigDecimal("0.05"), 1, null, settle.plusDays(730), DayCount.ACT_ACT_ISDA);
        // (5 + (100 - 98) / 2) / 98 = 0.0612244898
        assertThat(BondMath.simpleYield(t, new BigDecimal("98"), settle)).isEqualByComparingTo("0.0612244898");
        BondMath.Terms bill = new BondMath.Terms(null, 1, null, settle.plusDays(365), DayCount.ACT_360);
        assertThat(BondMath.accruedPct(bill, settle.plusDays(10))).isZero();
        assertThat(BondMath.simpleYield(bill, new BigDecimal("97"), settle)).isEqualByComparingTo("0.0309278351");
    }

    @Test
    void forwardPointsMtmAndNdf() {
        assertThat(FxMath.outright(new BigDecimal("1.0850"), new BigDecimal("25.5"), "USD")).isEqualByComparingTo("1.08755");
        assertThat(FxMath.outright(new BigDecimal("150.00"), new BigDecimal("-35"), "JPY")).isEqualByComparingTo("149.65");
        assertThat(FxMath.consistent(new BigDecimal("1.08755"), new BigDecimal("1.0850"), new BigDecimal("25.5"), "USD")).isTrue();
        assertThat(FxMath.consistent(new BigDecimal("1.0880"), new BigDecimal("1.0850"), new BigDecimal("25.5"), "USD")).isFalse();
        assertThat(FxMath.quoteAmount(new BigDecimal("1000000"), new BigDecimal("1.08755"), "USD")).isEqualByComparingTo("1087550.00");
        // bought EUR 1m at 1.08 ; market 1.09 -> +10,000 USD; sold -> -10,000
        assertThat(FxMath.legMtm("BUY", new BigDecimal("1000000"), new BigDecimal("1.08"), new BigDecimal("1.09"), "USD"))
                .isEqualByComparingTo("10000.00");
        assertThat(FxMath.legMtm("SELL", new BigDecimal("1000000"), new BigDecimal("1.08"), new BigDecimal("1.09"), "USD"))
                .isEqualByComparingTo("-10000.00");
        // USD/INR NDF bought USD 1m at 83.00, fixing 83.50 settled in USD: 1m x 0.5 / 83.5 = 5,988.02
        assertThat(FxMath.ndfSettlement("BUY", new BigDecimal("1000000"), new BigDecimal("83.00"), new BigDecimal("83.50"),
                "USD", "USD")).isEqualByComparingTo("5988.02");
        assertThat(FxMath.ndfSettlement("SELL", new BigDecimal("1000000"), new BigDecimal("83.00"), new BigDecimal("83.50"),
                "USD", "INR")).isEqualByComparingTo("-500000.00");
    }

    @Test
    void pfeAddOnAndExposures() {
        assertThat(FxMath.pfeAddOn(true, 2)).isZero();
        assertThat(FxMath.pfeAddOn(true, 90)).isEqualByComparingTo("0.01");
        assertThat(FxMath.pfeAddOn(true, 700)).isEqualByComparingTo("0.05");
        assertThat(FxMath.pfeAddOn(true, 2000)).isEqualByComparingTo("0.075");
        assertThat(FxMath.pfeAddOn(false, 700)).isEqualByComparingTo("0.005");
        List<Exposures.Exposure> fwd = Exposures.fx(V, List.of(new Exposures.FxLeg("BUY", "EUR", new BigDecimal("1000000"), "USD",
                new BigDecimal("1087550"), V.plusDays(90))), false);
        assertThat(fwd).anySatisfy(e -> {
            assertThat(e.limitType()).isEqualTo("PRE_SETTLEMENT");
            assertThat(e.amount()).isEqualByComparingTo("10000");
        });
        assertThat(fwd).anySatisfy(e -> {
            assertThat(e.limitType()).isEqualTo("SETTLEMENT");
            assertThat(e.currency()).isEqualTo("USD");
            assertThat(e.amount()).isEqualByComparingTo("1087550");
        });
        assertThat(Exposures.moneyMarket("BORROWING", new BigDecimal("5"), "EUR", V, V.plusDays(5))).isEmpty();
        assertThat(Exposures.moneyMarket("PLACEMENT", new BigDecimal("5"), "EUR", V, V.plusDays(5))).hasSize(4);
    }
}
