package io.meridian.transactionmonitoring.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class ScenariosTest {
    static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    static Scenarios.Txn txn(String dir, String amount, boolean cash, String cp, Duration ago) {
        return new Scenarios.Txn(UUID.randomUUID(), UUID.randomUUID(), cash ? "CASH" : "TRANSFER", null, null, dir,
                new BigDecimal(amount), "USD", cash, cp, cp, null, NOW.minus(ago));
    }

    static Scenarios.Params params(int lookback, String minAmount, Integer minCount, Map<String, Object> values) {
        return new Scenarios.Params(lookback, minAmount == null ? null : new BigDecimal(minAmount), minCount, BigDecimal.ONE, values);
    }

    static Optional<Scenarios.Detection> eval(String type, Scenarios.Params p, List<Scenarios.Txn> txns) {
        return Scenarios.evaluate(type, p, txns, NOW.minus(Duration.ofDays(400)), NOW);
    }

    @Test
    void structuringCountsCashDepositsJustBelowTheThreshold() {
        Scenarios.Params p = params(7, null, 3, Map.of("threshold", 10000, "margin_pct", 10));
        List<Scenarios.Txn> txns = List.of(
                txn("CREDIT", "9300", true, null, Duration.ofDays(3)),
                txn("CREDIT", "9450", true, null, Duration.ofDays(2)),
                txn("CREDIT", "10000", true, null, Duration.ofDays(2)),   // at the threshold: reported anyway
                txn("CREDIT", "9500", false, null, Duration.ofDays(1)),   // not cash
                txn("CREDIT", "9990", true, null, Duration.ofHours(5)));
        Scenarios.Detection d = eval("STRUCTURING", p, txns).orElseThrow();
        assertThat(d.contributing()).hasSize(3);
        assertThat(d.totalAmount()).isEqualByComparingTo("28740");
        assertThat(d.score()).isBetween(500, 1000);
        assertThat(d.reason()).containsEntry("deposits_in_band", 3);
        assertThat(eval("STRUCTURING", p, txns.subList(0, 3))).isEmpty();
    }

    @Test
    void slidingWindowOnlyCountsTransactionsInsideTheLookback() {
        Scenarios.Params p = params(7, null, 3, Map.of("threshold", 10000));
        List<Scenarios.Txn> spread = List.of(
                txn("CREDIT", "9500", true, null, Duration.ofDays(30)),
                txn("CREDIT", "9500", true, null, Duration.ofDays(20)),
                txn("CREDIT", "9500", true, null, Duration.ofDays(10)),
                txn("CREDIT", "9500", true, null, Duration.ofDays(1)));
        assertThat(eval("STRUCTURING", p, spread)).isEmpty();
        assertThat(eval("STRUCTURING", params(30, null, 3, Map.of("threshold", 10000)), spread)).isPresent();
    }

    @Test
    void rapidMovementNeedsMostOfTheMoneyToLeaveQuickly() {
        Scenarios.Params p = params(7, "1000", null, Map.of("window_hours", 24, "min_out_ratio", 0.8));
        List<Scenarios.Txn> fast = List.of(txn("CREDIT", "5000", false, "A", Duration.ofHours(30)),
                txn("DEBIT", "4700", false, "B", Duration.ofHours(20)));
        Scenarios.Detection d = eval("RAPID_MOVEMENT", p, fast).orElseThrow();
        assertThat(d.reason().get("out_in_ratio")).hasToString("0.9400");
        List<Scenarios.Txn> slow = List.of(txn("CREDIT", "5000", false, "A", Duration.ofDays(5)),
                txn("DEBIT", "4700", false, "B", Duration.ofDays(1)));
        assertThat(eval("RAPID_MOVEMENT", p, slow)).isEmpty();
        List<Scenarios.Txn> partial = List.of(txn("CREDIT", "5000", false, "A", Duration.ofHours(30)),
                txn("DEBIT", "2000", false, "B", Duration.ofHours(20)));
        assertThat(eval("RAPID_MOVEMENT", p, partial)).isEmpty();
    }

    @Test
    void fanInFanOutAndMule() {
        List<Scenarios.Txn> in = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            in.add(txn("CREDIT", "300", false, "PAYER-" + i, Duration.ofHours(10 + i)));
        }
        Scenarios.Params fan = params(7, null, null, Map.of("min_counterparties", 5));
        assertThat(eval("FAN_IN", fan, in).orElseThrow().reason()).containsEntry("distinct_counterparties", 6);
        assertThat(eval("FAN_OUT", fan, in)).isEmpty();
        assertThat(eval("FAN_IN", fan, in.subList(0, 4))).isEmpty();

        List<Scenarios.Txn> mule = new ArrayList<>(in.subList(0, 3));
        mule.add(txn("DEBIT", "850", false, "CASHOUT", Duration.ofHours(1)));
        Scenarios.Detection m = eval("MULE_ACCOUNT", params(7, null, 4, Map.of("min_counterparties", 3, "min_out_ratio", 0.7)), mule)
                .orElseThrow();
        assertThat(m.score()).isGreaterThanOrEqualTo(700);
    }

    @Test
    void dormantReactivationNeedsHistoryAndAGap() {
        Scenarios.Params p = params(7, "5000", null, Map.of("dormant_days", 180));
        List<Scenarios.Txn> txns = List.of(txn("CREDIT", "50", false, "X", Duration.ofDays(300)),
                txn("CREDIT", "7000", false, "Y", Duration.ofDays(2)));
        assertThat(eval("DORMANT_REACTIVATION", p, txns)).isPresent();
        assertThat(eval("DORMANT_REACTIVATION", p, txns.subList(1, 2))).isEmpty();
        List<Scenarios.Txn> active = List.of(txn("CREDIT", "50", false, "X", Duration.ofDays(20)),
                txn("CREDIT", "7000", false, "Y", Duration.ofDays(2)));
        assertThat(eval("DORMANT_REACTIVATION", p, active)).isEmpty();
    }

    @Test
    void otherScenariosAndWeights() {
        assertThat(eval("LARGE_CASH", params(1, null, null, Map.of("threshold", 10000)),
                List.of(txn("CREDIT", "25000", true, null, Duration.ofHours(1))))).isPresent();
        assertThat(eval("HIGH_RISK_JURISDICTION", params(30, null, 1, Map.of("countries", List.of("IR", "KP"))),
                List.of(new Scenarios.Txn(UUID.randomUUID(), UUID.randomUUID(), "TRANSFER", null, null, "DEBIT",
                        new BigDecimal("100"), "USD", false, "Z", "Z", "ir", NOW)))).isPresent();
        List<Scenarios.Txn> round = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            round.add(txn("DEBIT", "2000", false, "R", Duration.ofHours(i + 1)));
        }
        assertThat(eval("ROUND_AMOUNTS", params(7, null, 5, Map.of("round_unit", 1000)), round)).isPresent();
        List<Scenarios.Txn> dev = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            dev.add(txn("DEBIT", "100", false, "D", Duration.ofDays(20 + i * 7L)));
        }
        dev.add(txn("DEBIT", "5000", false, "D", Duration.ofDays(1)));
        assertThat(eval("PROFILE_DEVIATION", params(7, null, null, Map.of("history_days", 90, "multiplier", 3)), dev)).isPresent();

        Scenarios.Params heavy = new Scenarios.Params(1, null, null, new BigDecimal("2"), Map.of("threshold", 10000));
        assertThat(eval("LARGE_CASH", heavy, List.of(txn("CREDIT", "30000", true, null, Duration.ofHours(1)))).orElseThrow().score())
                .isEqualTo(1000);
        assertThat(Scenarios.severity(900)).isEqualTo("CRITICAL");
        assertThat(Scenarios.severity(720)).isEqualTo("HIGH");
        assertThat(Scenarios.validate("CUSTOM", heavy)).contains("no evaluator");
        assertThat(Scenarios.validate("HIGH_RISK_JURISDICTION", params(7, null, null, Map.of()))).contains("countries");
    }

    @Test
    void riskRatingFactorsAndFloors() {
        LocalDate today = LocalDate.parse("2026-10-06");
        RiskRating.Behaviour quiet = new RiskRating.Behaviour(0, 0, 0.0);
        RiskRating.Result low = RiskRating.rate(Map.of("country_risk", "LOW", "product_risk", "LOW", "channel_risk", "LOW",
                "occupation_risk", "LOW"), quiet, today);
        assertThat(low.rating()).isEqualTo("LOW");
        assertThat(low.nextReview()).isEqualTo(today.plusYears(3));
        RiskRating.Result pep = RiskRating.rate(Map.of("country_risk", "LOW", "pep", true), quiet, today);
        assertThat(pep.rating()).isEqualTo("HIGH");
        assertThat(pep.nextReview()).isEqualTo(today.plusYears(1));
        RiskRating.Result busy = RiskRating.rate(Map.of("country_risk", "HIGH", "product_risk", "HIGH", "channel_risk", "MEDIUM",
                "occupation_risk", "HIGH", "adverse_media", true), new RiskRating.Behaviour(3, 1, 0.8), today);
        assertThat(busy.rating()).isEqualTo("HIGH");
        assertThat(busy.score()).isGreaterThanOrEqualTo(700);
        assertThat(RiskRating.rate(Map.of("country_risk", "PROHIBITED"), quiet, today).rating()).isEqualTo("PROHIBITED");
    }

    // ------------------------------------------------------------------ properties

    @Provide
    Arbitrary<List<Scenarios.Txn>> feeds() {
        Arbitrary<Scenarios.Txn> one = Combinators.combine(
                Arbitraries.of("DEBIT", "CREDIT"),
                Arbitraries.integers().between(1, 30_000),
                Arbitraries.of(true, false),
                Arbitraries.of("A", "B", "C", "D", "E", "F", "G"),
                Arbitraries.integers().between(0, 24 * 60))
                .as((dir, amount, cash, cp, hoursAgo) -> txn(dir, String.valueOf(amount), cash, cp, Duration.ofHours(hoursAgo)));
        return one.list().ofMinSize(0).ofMaxSize(40);
    }

    @Property(tries = 200)
    void detectionsAreBoundedAndOnlyUseInputTransactions(@ForAll("feeds") List<Scenarios.Txn> feed) {
        Scenarios.Params p = params(14, null, 2, Map.of("threshold", 10000, "min_counterparties", 3, "dormant_days", 30,
                "countries", List.of("A"), "round_unit", 1000, "history_days", 30));
        for (String type : Scenarios.SUPPORTED) {
            eval(type, p, feed).ifPresent(d -> {
                assertThat(d.score()).isBetween(0, 1000);
                assertThat(d.contributing()).isNotEmpty();
                assertThat(feed).containsAll(d.contributing());
                assertThat(d.totalAmount()).isEqualByComparingTo(Scenarios.sum(d.contributing()));
                assertThat(d.periodStart()).isBeforeOrEqualTo(d.periodEnd());
            });
        }
    }

    @Property(tries = 200)
    void addingDebitsNeverHidesStructuring(@ForAll("feeds") List<Scenarios.Txn> feed) {
        Scenarios.Params p = params(14, null, 3, Map.of("threshold", 10000, "margin_pct", 20));
        boolean before = eval("STRUCTURING", p, feed).isPresent();
        List<Scenarios.Txn> more = new ArrayList<>(feed);
        more.add(txn("DEBIT", "9500", true, "X", Duration.ofHours(3)));
        if (before) {
            assertThat(eval("STRUCTURING", p, more)).isPresent();
        }
    }
}
