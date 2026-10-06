package io.meridian.productcatalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.productcatalog.domain.fees.FeeCalculator;
import io.meridian.productcatalog.domain.rates.DayCount;
import io.meridian.productcatalog.domain.rates.RateCalculator;
import io.meridian.productcatalog.domain.simulation.FinanceMath;
import io.meridian.productcatalog.domain.simulation.SimulationEngine;
import io.meridian.productcatalog.domain.simulation.SimulationEngine.PeriodicFee;
import io.meridian.productcatalog.domain.simulation.SimulationEngine.Product;
import io.meridian.productcatalog.domain.simulation.SimulationEngine.Scenario;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class SimulationEngineTest {
    static final LocalDate START = LocalDate.of(2026, 1, 1);

    static RateCalculator.Config fixed(String rate, DayCount dc) {
        return new RateCalculator.Config("FIXED", bd(rate), null, null, null, BigDecimal.ZERO, dc, RoundingMode.HALF_EVEN, 2,
                null, null, null);
    }

    static PeriodicFee monthlyFee(String amount, String waiveAbove) {
        List<FeeCalculator.Waiver> w = waiveAbove == null ? List.of() : List.of(new FeeCalculator.Waiver(UUID.randomUUID(),
                "MIN_BALANCE", bd(waiveAbove), null, null, BigDecimal.ONE, null, null, null));
        return new PeriodicFee("MONTHLY_MAINTENANCE", "MONTHLY", new FeeCalculator.Fee(UUID.randomUUID(), "MAINT", "FLAT",
                bd(amount), null, null, null, null, 0, "USD", null, null, null, w), null);
    }

    static Scenario deposit(String opening, String monthly, int months, String wht) {
        return new Scenario("DEPOSIT", START, months, bd(opening), bd(monthly), BigDecimal.ZERO, null, null, 0, null,
                wht == null ? null : bd(wht), new TreeMap<>(), null);
    }

    @Test
    void depositAccruesByDayCountAndCapitalisesMonthly() {
        Product p = new Product("USD", fixed("0.0365", DayCount.ACT_365), "MONTHLY", null, List.of());
        SimulationEngine.Result r = SimulationEngine.run(p, deposit("10000", "0", 2, null));
        // Jan: 10000 * 0.0365 * 31/365 = 31.00 ; Feb: 10031 * 0.0365 * 28/365 = 28.0868 -> 28.09
        assertThat((BigDecimal) r.periods().get(0).get("interest_capitalised")).isEqualByComparingTo("31.00");
        assertThat((BigDecimal) r.periods().get(1).get("interest_capitalised")).isEqualByComparingTo("28.09");
        assertThat((BigDecimal) r.summary().get("closing_balance")).isEqualByComparingTo("10059.09");
    }

    @Test
    void quarterlyCapitalisationWithholdingTaxAndWaivedFees() {
        Product p = new Product("USD", fixed("0.05", DayCount.ACT_360), "QUARTERLY", null, List.of(monthlyFee("3.00", "5000")));
        SimulationEngine.Result r = SimulationEngine.run(p, deposit("1000", "0", 3, "0.10"));
        assertThat((BigDecimal) r.periods().get(0).get("interest_capitalised")).isEqualByComparingTo("0");
        assertThat((BigDecimal) r.periods().get(2).get("interest_capitalised")).isPositive();
        assertThat((BigDecimal) r.summary().get("total_fees")).isEqualByComparingTo("9.00");
        assertThat((BigDecimal) r.summary().get("total_tax")).isPositive();
        Product rich = new Product("USD", fixed("0.05", DayCount.ACT_360), "MONTHLY", null, List.of(monthlyFee("3.00", "5000")));
        assertThat((BigDecimal) SimulationEngine.run(rich, deposit("6000", "0", 3, null)).summary().get("total_fees"))
                .isEqualByComparingTo("0");
    }

    @Test
    void floatingRateFollowsScenarioPath() {
        RateCalculator.Config floating = new RateCalculator.Config("FLOATING", null, bd("0.01"), null, null, BigDecimal.ZERO,
                DayCount.ACT_365, RoundingMode.HALF_EVEN, 2, null, null, null);
        Product p = new Product("USD", floating, "MONTHLY", d -> bd("0.03"), List.of());
        Scenario s = new Scenario("DEPOSIT", START, 4, bd("1000"), null, null, null, null, 0, null, null,
                new TreeMap<>(Map.of(3, bd("0.05"))), null);
        SimulationEngine.Result r = SimulationEngine.run(p, s);
        assertThat((BigDecimal) r.periods().get(0).get("interest_rate")).isEqualByComparingTo("0.04");
        assertThat((BigDecimal) r.periods().get(2).get("interest_rate")).isEqualByComparingTo("0.06");
        assertThat((BigDecimal) r.periods().get(3).get("interest_rate")).isEqualByComparingTo("0.06");
    }

    @Test
    void loanAmortisesToZeroAndAprReflectsFees() {
        PeriodicFee origination = new PeriodicFee("LOAN_ORIGINATION", null, new FeeCalculator.Fee(UUID.randomUUID(), "ORIG",
                "PERCENTAGE", null, bd("0.02"), null, null, null, 0, "USD", null, null, null, null), null);
        Scenario s = new Scenario("LOAN", START, 24, null, null, null, bd("12000"), 24, 0, null, null, new TreeMap<>(), null);
        SimulationEngine.Result noFees = SimulationEngine.run(new Product("USD", fixed("0.12", DayCount.THIRTY_360), "MONTHLY",
                null, List.of()), s);
        assertThat((BigDecimal) noFees.summary().get("outstanding_balance")).isEqualByComparingTo("0");
        BigDecimal aprNoFees = (BigDecimal) noFees.summary().get("apr");
        assertThat(aprNoFees.subtract(bd("0.12")).abs()).isLessThan(bd("0.001"));
        SimulationEngine.Result withFees = SimulationEngine.run(new Product("USD", fixed("0.12", DayCount.THIRTY_360),
                "MONTHLY", null, List.of(origination)), s);
        assertThat((BigDecimal) withFees.summary().get("apr")).isGreaterThan(aprNoFees);
        assertThat((BigDecimal) withFees.summary().get("total_fees")).isEqualByComparingTo("240.00");
    }

    @Test
    void annuityMatchesKnownValue() {
        BigDecimal p = FinanceMath.annuityPayment(bd("100000"), bd("0.05").divide(bd("12"), MathContext.DECIMAL128), 360);
        assertThat(p.setScale(2, RoundingMode.HALF_EVEN)).isEqualByComparingTo("536.82");
        assertThat(FinanceMath.aer(bd("0.12"), 12).setScale(6, RoundingMode.HALF_EVEN)).isEqualByComparingTo("0.126825");
    }

    @Property(tries = 60)
    void depositBalanceReconciles(@ForAll @BigRange(min = "0", max = "100000") BigDecimal opening,
                                  @ForAll @BigRange(min = "0", max = "2000") BigDecimal monthly,
                                  @ForAll @IntRange(min = 1, max = 36) int months) {
        Product p = new Product("USD", fixed("0.04", DayCount.ACT_365), "MONTHLY", null, List.of(monthlyFee("2.50", "2500")));
        Scenario s = deposit(opening.setScale(2, RoundingMode.HALF_EVEN).toPlainString(),
                monthly.setScale(2, RoundingMode.HALF_EVEN).toPlainString(), months, "0.1");
        SimulationEngine.Result r = SimulationEngine.run(p, s);
        Map<String, Object> sum = r.summary();
        BigDecimal expected = ((BigDecimal) sum.get("opening_balance")).add((BigDecimal) sum.get("total_deposits"))
                .subtract((BigDecimal) sum.get("total_withdrawals")).add((BigDecimal) sum.get("total_interest"))
                .subtract((BigDecimal) sum.get("total_tax")).subtract((BigDecimal) sum.get("total_fees"));
        assertThat((BigDecimal) sum.get("closing_balance")).isEqualByComparingTo(expected);
        assertThat(SimulationEngine.run(p, s)).isEqualTo(r); // deterministic
    }

    @Property(tries = 40)
    void loanPrincipalRepaidExactly(@ForAll @BigRange(min = "100", max = "500000") BigDecimal principal,
                                    @ForAll @IntRange(min = 1, max = 120) int term,
                                    @ForAll @BigRange(min = "0", max = "0.35") BigDecimal rate) {
        Product p = new Product("USD", fixed(rate.setScale(4, RoundingMode.HALF_EVEN).toPlainString(), DayCount.ACT_365),
                "MONTHLY", null, List.of());
        Scenario s = new Scenario("LOAN", START, term, null, null, null, principal.setScale(2, RoundingMode.HALF_EVEN), term,
                0, null, null, new TreeMap<>(), null);
        SimulationEngine.Result r = SimulationEngine.run(p, s);
        BigDecimal repaid = r.periods().stream().map(m -> (BigDecimal) m.get("principal")).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(repaid).isEqualByComparingTo(principal.setScale(2, RoundingMode.HALF_EVEN));
        assertThat((BigDecimal) r.summary().get("outstanding_balance")).isEqualByComparingTo("0");
        assertThat(ChronoUnit.MONTHS.between(START, LocalDate.parse((String) r.periods().getLast().get("period_start"))))
                .isEqualTo(term - 1L);
    }

    static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }
}
