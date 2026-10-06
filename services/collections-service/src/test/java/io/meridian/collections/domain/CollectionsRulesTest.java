package io.meridian.collections.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CollectionsRulesTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    // ------------------------------------------------------------------ buckets, lifecycles, priority

    @Test
    void bucketsByDaysPastDue() {
        assertThat(Delinquency.bucket(0)).isEqualTo("CURRENT");
        assertThat(Delinquency.bucket(1)).isEqualTo("DPD_1_29");
        assertThat(Delinquency.bucket(29)).isEqualTo("DPD_1_29");
        assertThat(Delinquency.bucket(30)).isEqualTo("DPD_30_59");
        assertThat(Delinquency.bucket(89)).isEqualTo("DPD_60_89");
        assertThat(Delinquency.bucket(90)).isEqualTo("DPD_90_119");
        assertThat(Delinquency.bucket(179)).isEqualTo("DPD_120_179");
        assertThat(Delinquency.bucket(180)).isEqualTo("DPD_180_PLUS");
    }

    @Test
    void caseAndLegalStateMachines() {
        assertThat(Lifecycles.allowed(Lifecycles.CASE, "OPEN", "PROMISE_ACTIVE")).isTrue();
        assertThat(Lifecycles.allowed(Lifecycles.CASE, "CLOSED", "OPEN")).isFalse();
        assertThat(Lifecycles.allowed(Lifecycles.CASE, "LEGAL", "PROMISE_ACTIVE")).isFalse();
        assertThat(Lifecycles.closingStatus("CURED")).isEqualTo("RESOLVED");
        assertThat(Lifecycles.closingStatus("SOLD")).isEqualTo("CLOSED");
        assertThat(Lifecycles.allowed(Lifecycles.LEGAL, "INITIATED", "FILED")).isTrue();
        assertThat(Lifecycles.allowed(Lifecycles.LEGAL, "INITIATED", "JUDGEMENT")).isFalse();
        assertThatThrownBy(() -> Lifecycles.require(Lifecycles.LEGAL, "Legal", "PENDING_APPROVAL", "FILED"))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("invalid_transition");
    }

    @Test
    void priorityRisesWithDelinquencyAndSinksWhenDisputed() {
        int low = Priority.score(5, new BigDecimal("50"), 0, true, false, false);
        int high = Priority.score(120, new BigDecimal("5000"), 2, false, false, false);
        assertThat(high).isGreaterThan(low).isLessThanOrEqualTo(1000);
        assertThat(Priority.score(120, new BigDecimal("5000"), 2, false, true, false)).isLessThan(high);
        assertThat(Priority.score(0, BigDecimal.ZERO, 0, true, true, false)).isZero();
    }

    // ------------------------------------------------------------------ strategy selection

    private static StrategyMatcher.Strategy strategy(String code, String type, int min, Integer max, int priority,
                                                     String rules) {
        return new StrategyMatcher.Strategy(UUID.nameUUIDFromBytes(code.getBytes()), code, type, min, max, priority, true,
                StrategyMatcher.SegmentRules.parse(JSON.readTree(rules)));
    }

    @Test
    void strategySelectionByTypeDpdSegmentAndPriority() {
        var early = strategy("EARLY", "LOAN", 1, 29, 100, "{}");
        var lateHigh = strategy("LATE_HIGH", "LOAN", 30, null, 50, "{\"min_balance\":\"10000\"}");
        var late = strategy("LATE", null, 30, null, 100, "{}");
        var gradeE = strategy("GRADE_E", "LOAN", 30, 120, 10, "{\"risk_grades\":[\"E1\",\"E2\"]}");
        var all = List.of(early, lateHigh, late, gradeE);
        UUID product = UUID.randomUUID();
        assertThat(StrategyMatcher.select(all, facts("LOAN", 10, "500", "B1", product)).orElseThrow().code())
                .isEqualTo("EARLY");
        assertThat(StrategyMatcher.select(all, facts("LOAN", 45, "500", "B1", product)).orElseThrow().code())
                .isEqualTo("LATE");
        assertThat(StrategyMatcher.select(all, facts("LOAN", 45, "20000", "B1", product)).orElseThrow().code())
                .isEqualTo("LATE_HIGH");
        assertThat(StrategyMatcher.select(all, facts("LOAN", 45, "20000", "E1", product)).orElseThrow().code())
                .isEqualTo("GRADE_E");
        assertThat(StrategyMatcher.select(all, facts("CREDIT_LINE", 10, "500", "B1", product))).isEmpty();
        assertThat(StrategyMatcher.select(all, facts("CREDIT_LINE", 40, "500", "B1", product)).orElseThrow().code())
                .isEqualTo("LATE");
    }

    private static StrategyMatcher.CaseFacts facts(String type, int dpd, String total, String grade, UUID product) {
        return new StrategyMatcher.CaseFacts(type, dpd, new BigDecimal(total), grade, product, false);
    }

    @Test
    void segmentRulesAreValidated() {
        assertThatThrownBy(() -> StrategyMatcher.SegmentRules.parse(JSON.readTree("{\"colour\":\"red\"}")))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("invalid_segment_rules");
        assertThatThrownBy(() -> StrategyMatcher.SegmentRules.parse(JSON.readTree("{\"risk_grades\":\"A1\"}")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> StrategyMatcher.SegmentRules.parse(JSON.readTree("{\"min_balance\":10,\"max_balance\":5}")))
                .isInstanceOf(ApiException.class);
        assertThat(StrategyMatcher.SegmentRules.parse(JSON.readTree("{\"vulnerable\":true,\"product_ids\":[\""
                + UUID.randomUUID() + "\"]}")).vulnerable()).isTrue();
    }

    // ------------------------------------------------------------------ payment plans

    @Test
    void planInstalmentsRoundToTheMinorUnitWithLastInstalmentTrueUp() {
        var plan = PaymentPlanCalculator.instalments(new BigDecimal("100.00"), "EUR", 3, "MONTHLY", LocalDate.of(2026, 1, 31));
        assertThat(plan).extracting(i -> i.amount().toPlainString()).containsExactly("33.33", "33.33", "33.34");
        assertThat(plan).extracting(PaymentPlanCalculator.Instalment::dueDate)
                .containsExactly(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31));
        var yen = PaymentPlanCalculator.instalments(new BigDecimal("1000"), "JPY", 3, "WEEKLY", LocalDate.of(2026, 1, 1));
        assertThat(yen).extracting(i -> i.amount().toPlainString()).containsExactly("333", "333", "334");
        assertThatThrownBy(() -> PaymentPlanCalculator.instalments(BigDecimal.TEN, "EUR", 0, "MONTHLY", LocalDate.now()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PaymentPlanCalculator.instalments(BigDecimal.TEN, "EUR", 2, "DAILY", LocalDate.now()))
                .isInstanceOf(ApiException.class);
        assertThat(PaymentPlanCalculator.outsidePolicy(13, BigDecimal.TEN, BigDecimal.ONE)).isTrue();
        assertThat(PaymentPlanCalculator.outsidePolicy(6, BigDecimal.ONE, BigDecimal.TEN)).isTrue();
        assertThat(PaymentPlanCalculator.outsidePolicy(6, BigDecimal.TEN, BigDecimal.TEN)).isFalse();
    }

    @Property(tries = 300)
    void planInstalmentsAlwaysSumToTheTotal(@ForAll @LongRange(min = 100, max = 100_000_000) long cents,
                                            @ForAll @IntRange(min = 1, max = 60) int count) {
        BigDecimal total = BigDecimal.valueOf(cents, 2);
        if (total.divide(BigDecimal.valueOf(count), 2, java.math.RoundingMode.DOWN).signum() == 0) {
            return;
        }
        var plan = PaymentPlanCalculator.instalments(total, "USD", count, "FORTNIGHTLY", LocalDate.of(2026, 3, 1));
        assertThat(plan).hasSize(count);
        assertThat(plan.stream().map(PaymentPlanCalculator.Instalment::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(total);
        BigDecimal first = plan.getFirst().amount();
        assertThat(plan.getLast().amount().subtract(first).abs()).isLessThan(BigDecimal.valueOf(count, 2).add(BigDecimal.ONE));
    }

    // ------------------------------------------------------------------ promises and plan tracking

    @Test
    void paymentsCountOnlyWhenPostedAndNotReversed() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var payments = List.of(
                new ArrangementTracker.Payment(a, new BigDecimal("40"), LocalDate.of(2026, 10, 7), "POSTED", null),
                new ArrangementTracker.Payment(b, new BigDecimal("60"), LocalDate.of(2026, 10, 8), "POSTED", null),
                new ArrangementTracker.Payment(UUID.randomUUID(), new BigDecimal("60"), LocalDate.of(2026, 10, 9), "REVERSED", b),
                new ArrangementTracker.Payment(UUID.randomUUID(), new BigDecimal("99"), LocalDate.of(2026, 9, 1), "POSTED", null));
        assertThat(ArrangementTracker.paid(payments, LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 10)))
                .isEqualByComparingTo("40");
    }

    @Test
    void promiseOutcomes() {
        LocalDate promised = LocalDate.of(2026, 10, 10);
        assertThat(ArrangementTracker.promiseOutcome(new BigDecimal("100"), new BigDecimal("100"), promised,
                LocalDate.of(2026, 10, 8))).contains("KEPT");
        assertThat(ArrangementTracker.promiseOutcome(new BigDecimal("100"), BigDecimal.ZERO, promised,
                LocalDate.of(2026, 10, 10))).isEmpty();
        assertThat(ArrangementTracker.promiseOutcome(new BigDecimal("100"), new BigDecimal("60"), promised,
                LocalDate.of(2026, 10, 11))).contains("PARTIALLY_KEPT");
        assertThat(ArrangementTracker.promiseOutcome(new BigDecimal("100"), new BigDecimal("10"), promised,
                LocalDate.of(2026, 10, 11))).contains("BROKEN");
    }

    @Test
    void planTracking() {
        var inst = List.of(
                new ArrangementTracker.PlanInstalment(1, LocalDate.of(2026, 10, 10), new BigDecimal("50"), BigDecimal.ZERO),
                new ArrangementTracker.PlanInstalment(2, LocalDate.of(2026, 11, 10), new BigDecimal("50"), BigDecimal.ZERO));
        var ok = ArrangementTracker.plan(inst, new BigDecimal("50"), LocalDate.of(2026, 10, 20));
        assertThat(ok.status()).isEqualTo("ACTIVE");
        assertThat(ok.instalments().getFirst().paid()).isEqualByComparingTo("50");
        assertThat(ArrangementTracker.plan(inst, new BigDecimal("20"), LocalDate.of(2026, 10, 11)).status()).isEqualTo("BROKEN");
        assertThat(ArrangementTracker.plan(inst, new BigDecimal("100"), LocalDate.of(2026, 10, 11)).status()).isEqualTo("COMPLETED");
    }

    // ------------------------------------------------------------------ queues

    @Test
    void rebalanceIsEvenMinimalAndRespectsCapacity() {
        List<QueueDistribution.QueuedCase> cases = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            cases.add(new QueueDistribution.QueuedCase(new UUID(0, i), i < 5 ? "ann" : null));
        }
        var r = QueueDistribution.rebalance(List.of("ann", "bob", "cid"), null, cases);
        assertThat(r.perAgent()).isEqualTo(Map.of("ann", 3, "bob", 2, "cid", 2));
        assertThat(r.moved()).isEqualTo(4); // 2 of ann's 5 cases move to bob, the 2 unassigned go to cid
        assertThat(r.unassigned()).isZero();
        assertThat(r.assignment().get(new UUID(0, 0))).isEqualTo("ann");

        var capped = QueueDistribution.rebalance(List.of("ann", "bob"), 2, cases);
        assertThat(capped.perAgent()).isEqualTo(Map.of("ann", 2, "bob", 2));
        assertThat(capped.unassigned()).isEqualTo(3);
        // the lowest-priority cases (end of the list) are the ones left unassigned
        assertThat(capped.assignment().get(new UUID(0, 6))).isNull();

        var two = QueueDistribution.rebalance(List.of("ann", "bob"), null, cases.subList(0, 3));
        assertThat(two.perAgent()).isEqualTo(Map.of("ann", 2, "bob", 1));
        assertThat(two.unassigned()).isZero();

        assertThat(QueueDistribution.leastLoaded(List.of("ann", "bob"), Map.of("ann", 2, "bob", 1), null)).isEqualTo("bob");
        assertThat(QueueDistribution.leastLoaded(List.of("ann", "bob"), Map.of("ann", 2, "bob", 2), 2)).isNull();
    }
}
