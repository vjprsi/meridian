package io.meridian.insights.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class BudgetForecastInsightTest {
    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    @Test
    void periodsAreAnchoredOnTheStartDate() {
        assertThat(BudgetPeriods.containing(d("2026-01-15"), "MONTHLY", d("2026-03-20")))
                .contains(new BudgetPeriods.Period(d("2026-03-15"), d("2026-04-14")));
        assertThat(BudgetPeriods.containing(d("2026-01-15"), "MONTHLY", d("2026-03-14")))
                .contains(new BudgetPeriods.Period(d("2026-02-15"), d("2026-03-14")));
        assertThat(BudgetPeriods.containing(d("2026-01-31"), "MONTHLY", d("2026-02-28")))
                .contains(new BudgetPeriods.Period(d("2026-02-28"), d("2026-03-30")));
        assertThat(BudgetPeriods.containing(d("2026-10-05"), "WEEKLY", d("2026-10-18")))
                .contains(new BudgetPeriods.Period(d("2026-10-12"), d("2026-10-18")));
        assertThat(BudgetPeriods.containing(d("2026-01-01"), "QUARTERLY", d("2026-05-01")))
                .contains(new BudgetPeriods.Period(d("2026-04-01"), d("2026-06-30")));
        assertThat(BudgetPeriods.containing(d("2025-03-01"), "YEARLY", d("2026-02-28")))
                .contains(new BudgetPeriods.Period(d("2025-03-01"), d("2026-02-28")));
        assertThat(BudgetPeriods.containing(d("2026-01-15"), "MONTHLY", d("2026-01-14"))).isEmpty();
        BudgetPeriods.Period p = new BudgetPeriods.Period(d("2026-01-15"), d("2026-02-14"));
        assertThat(BudgetPeriods.next(d("2026-01-15"), "MONTHLY", p)).isEqualTo(new BudgetPeriods.Period(d("2026-02-15"), d("2026-03-14")));
    }

    @Property(tries = 300)
    void periodsTileTheCalendar(@ForAll @IntRange(min = 0, max = 2000) int offset, @ForAll @IntRange(min = 0, max = 3) int t) {
        String type = List.of("WEEKLY", "MONTHLY", "QUARTERLY", "YEARLY").get(t);
        LocalDate start = d("2024-01-31");
        LocalDate date = start.plusDays(offset);
        BudgetPeriods.Period p = BudgetPeriods.containing(start, type, date).orElseThrow();
        assertThat(p.contains(date)).isTrue();
        BudgetPeriods.Period next = BudgetPeriods.containing(start, type, p.end().plusDays(1)).orElseThrow();
        assertThat(next.start()).isEqualTo(p.end().plusDays(1));
    }

    @Test
    void rolloverAndAlertLevels() {
        assertThat(BudgetPeriods.withRollover(new BigDecimal("500"), true, new BigDecimal("40"))).isEqualByComparingTo("540");
        assertThat(BudgetPeriods.withRollover(new BigDecimal("500"), true, new BigDecimal("-40"))).isEqualByComparingTo("500");
        assertThat(BudgetPeriods.withRollover(new BigDecimal("500"), false, new BigDecimal("40"))).isEqualByComparingTo("500");
        BigDecimal pct = new BigDecimal("0.8");
        assertThat(BudgetPeriods.level(new BigDecimal("399"), new BigDecimal("500"), pct)).isEqualTo(BudgetPeriods.Alert.NONE);
        assertThat(BudgetPeriods.level(new BigDecimal("400"), new BigDecimal("500"), pct)).isEqualTo(BudgetPeriods.Alert.THRESHOLD);
        assertThat(BudgetPeriods.level(new BigDecimal("500"), new BigDecimal("500"), pct)).isEqualTo(BudgetPeriods.Alert.THRESHOLD);
        assertThat(BudgetPeriods.level(new BigDecimal("500.01"), new BigDecimal("500"), pct)).isEqualTo(BudgetPeriods.Alert.EXCEEDED);
    }

    @Test
    void forecastIsDeterministicAndFindsTheLowestBalance() {
        LocalDate asOf = d("2026-10-06");
        List<CashFlowForecaster.Series> series = List.of(
                new CashFlowForecaster.Series("Rent", "MONTHLY", new BigDecimal("-900"), d("2026-10-10")),
                new CashFlowForecaster.Series("Salary", "MONTHLY", new BigDecimal("2000"), d("2026-09-25")),
                new CashFlowForecaster.Series("Gym", "WEEKLY", new BigDecimal("-10"), d("2026-10-07")));
        CashFlowForecaster.Forecast f = CashFlowForecaster.project(asOf, 30, new BigDecimal("1000"), series, new BigDecimal("-20"));
        CashFlowForecaster.Forecast again = CashFlowForecaster.project(asOf, 30, new BigDecimal("1000"), series, new BigDecimal("-20"));
        assertThat(f).isEqualTo(again);
        assertThat(f.points()).hasSize(30);
        assertThat(f.points().getFirst().date()).isEqualTo(d("2026-10-07"));
        // Salary was due 09-25 (in the past): next occurrence projected on 10-25.
        assertThat(f.expectedIncome()).isEqualByComparingTo("2000");
        assertThat(f.expectedBills()).isEqualByComparingTo(new BigDecimal("900").add(new BigDecimal("50")));
        assertThat(f.expectedDiscretionary()).isEqualByComparingTo("600");
        // Lowest: day before salary: 1000 - 900 - 18 days * 20 - 3 gym sessions... compute from points.
        BigDecimal min = f.points().stream().map(CashFlowForecaster.Point::balance).min(BigDecimal::compareTo).orElseThrow();
        assertThat(f.lowestBalance()).isEqualByComparingTo(min);
        assertThat(f.lowestBalanceDate()).isEqualTo(d("2026-10-24"));
        assertThat(f.points().get(3).items()).extracting(CashFlowForecaster.Item::name).containsExactly("Rent");
        assertThat(CashFlowForecaster.dailyAverage(new BigDecimal("-900"), 90)).isEqualByComparingTo("-10");
    }

    @Test
    void spendSpikeNeedsHistoryFactorAndMinimumExcess() {
        UUID cat = UUID.randomUUID();
        LocalDate today = d("2026-10-06");
        var spike = InsightRules.spendSpike(cat, "Restaurants", "GBP", new BigDecimal("400"),
                Arrays.asList(new BigDecimal("200"), new BigDecimal("180"), null), new BigDecimal("50"), today);
        assertThat(spike).isPresent();
        assertThat(spike.get().type()).isEqualTo("SPEND_SPIKE");
        assertThat(spike.get().dedupeKey()).isEqualTo("SPEND_SPIKE:" + cat + ":GBP:2026-10");
        assertThat(spike.get().data().get("trailing_average")).isEqualTo("190.00");
        assertThat(InsightRules.spendSpike(cat, "Restaurants", "GBP", new BigDecimal("250"),
                List.of(new BigDecimal("200")), new BigDecimal("50"), today)).isEmpty();
        assertThat(InsightRules.spendSpike(cat, "Coffee", "GBP", new BigDecimal("30"),
                List.of(new BigDecimal("10")), new BigDecimal("50"), today)).isEmpty();
        assertThat(InsightRules.spendSpike(cat, "New", "GBP", new BigDecimal("300"), Arrays.asList(null, null, null),
                new BigDecimal("50"), today)).isEmpty();
    }

    @Test
    void otherInsightCandidates() {
        UUID id = UUID.randomUUID();
        var up = InsightRules.priceChange(id, "Netflix", "GBP", new BigDecimal("10.99"), new BigDecimal("12.49"));
        assertThat(up.severity()).isEqualTo("WARNING");
        assertThat(up.dedupeKey()).isEqualTo("SUB_PRICE:" + id + ":12.49");
        assertThat(InsightRules.newSeries(id, "Acme Payroll", "MONTHLY", "GBP", new BigDecimal("2500"), true, d("2026-09-30"))
                .type()).isEqualTo("SALARY_RECEIVED");
        var low = InsightRules.lowBalance(id, id, "GBP", new BigDecimal("-20"), d("2026-10-20"), BigDecimal.ZERO);
        assertThat(low.severity()).isEqualTo("CRITICAL");
        assertThat(InsightRules.budget(true, id, id, "Food", "GBP", new BigDecimal("510"), new BigDecimal("500"), d("2026-10-01"))
                .type()).isEqualTo("BUDGET_EXCEEDED");
    }
}
