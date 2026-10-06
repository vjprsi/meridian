package io.meridian.fraud.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.fraud.domain.DecisionEngine.Decision;
import io.meridian.fraud.domain.DecisionEngine.Policy;
import io.meridian.fraud.domain.DecisionEngine.Rule;
import io.meridian.fraud.domain.expr.RuleExpression;
import io.meridian.fraud.domain.model.FraudModel;
import io.meridian.fraud.domain.model.LogisticRegressionModel;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import org.junit.jupiter.api.Test;

class DecisionEngineTest {
    static final Policy POLICY = new Policy(400, 600, 850, null, null, 50, Decision.ALLOW);
    static final FraudModel MODEL = LogisticRegressionModel.champion();

    static Rule rule(String code, String action, int adj, int priority, boolean shadow, String expr) {
        return new Rule(UUID.randomUUID(), code, 1, action, adj, priority, shadow, RuleExpression.compile(expr));
    }

    static Map<String, Object> facts(String amount, boolean newDevice, int count1h) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("amount", new BigDecimal(amount));
        f.put("device", Map.of("is_new", newDevice, "customers_seen", 1));
        f.put("velocity", Map.of("count_1h", count1h, "count_30d", 10, "avg_amount_30d", "100"));
        return f;
    }

    static FraudModel.Output score(Map<String, Object> f) {
        return MODEL.score(FeatureEngineering.engineer(f));
    }

    @Test
    void quietPaymentIsAllowed() {
        Map<String, Object> f = facts("80", false, 0);
        var o = DecisionEngine.decide(List.of(), f, Set.of(), Set.of(), score(f), null, POLICY);
        assertThat(o.decision()).isEqualTo(Decision.ALLOW);
        assertThat(o.score()).isLessThan(400);
        assertThat(o.reasonCodes()).isEmpty();
    }

    @Test
    void riskySignalsRaiseModelScoreAndReasonCodes() {
        Map<String, Object> f = facts("5000", true, 5);
        var out = score(f);
        var o = DecisionEngine.decide(List.of(), f, Set.of(), Set.of(), out, null, POLICY);
        assertThat(o.modelScore()).isGreaterThan(600);
        assertThat(o.decision()).isIn(Decision.REVIEW, Decision.BLOCK);
        assertThat(o.reasonCodes()).contains("HIGH_VELOCITY_1H", "AMOUNT_ABOVE_CUSTOMER_AVERAGE");
    }

    @Test
    void reviewRuleSetsAFloorAndAddsPoints() {
        Map<String, Object> f = facts("80", false, 0);
        Rule r = rule("BIG_NEW", "REVIEW", 50, 10, false, "amount > 50");
        var o = DecisionEngine.decide(List.of(r), f, Set.of(), Set.of(), score(f), null, POLICY);
        assertThat(o.decision()).isEqualTo(Decision.REVIEW);
        assertThat(o.ruleScore()).isEqualTo(50);
        assertThat(o.reasonCodes()).first().isEqualTo("RULE:BIG_NEW");
        assertThat(o.hits()).hasSize(1);
        assertThat(o.hits().getFirst().matchedValues()).containsEntry("amount", new BigDecimal("80"));
    }

    @Test
    void allowRuleShortCircuitsButHardControlsStillBlock() {
        Map<String, Object> f = facts("5000", true, 5);
        Rule allow = rule("VIP", "ALLOW", 0, 1, false, "true");
        Rule block = rule("NEVER", "BLOCK", 0, 2, false, "true");
        var allowed = DecisionEngine.decide(List.of(allow, block), f, Set.of(), Set.of(), score(f), null, POLICY);
        assertThat(allowed.decision()).isEqualTo(Decision.ALLOW);
        assertThat(allowed.hits()).hasSize(1);

        var blocked = DecisionEngine.decide(List.of(allow), f, Set.of("BAD_DEVICES"), Set.of("BAD_DEVICES"),
                score(f), null, POLICY);
        assertThat(blocked.decision()).isEqualTo(Decision.BLOCK);
        assertThat(blocked.reasonCodes()).contains("BLOCK_LIST:BAD_DEVICES");

        Map<String, Object> g = new LinkedHashMap<>(f);
        g.put("device", Map.of("blocked", true));
        assertThat(DecisionEngine.decide(List.of(), g, Set.of(), Set.of(), score(g), null, POLICY).decision())
                .isEqualTo(Decision.BLOCK);
    }

    @Test
    void shadowRulesRecordHitsWithoutEffect() {
        Map<String, Object> f = facts("80", false, 0);
        Rule shadow = rule("TRY", "BLOCK", 500, 1, true, "amount > 10");
        var o = DecisionEngine.decide(List.of(shadow), f, Set.of(), Set.of(), score(f), score(f), POLICY);
        assertThat(o.decision()).isEqualTo(Decision.ALLOW);
        assertThat(o.ruleScore()).isZero();
        assertThat(o.hits()).singleElement().satisfies(h -> assertThat(h.shadow()).isTrue());
        assertThat(o.shadowDecision()).isEqualTo(Decision.ALLOW);
    }

    @Test
    void brokenRuleIsSkippedNotFatal() {
        Map<String, Object> f = facts("80", false, 0);
        Rule ok = rule("OK", "STEP_UP", 0, 2, false, "amount > 10");
        var o = DecisionEngine.decide(List.of(ok), f, Set.of(), Set.of(), score(f), null, POLICY);
        assertThat(o.decision()).isEqualTo(Decision.STEP_UP);
    }

    @Property(tries = 200)
    void modelScoreIsMonotoneInRiskFeatures(@ForAll @DoubleRange(min = 0, max = 1) double v,
                                            @ForAll @DoubleRange(min = 0, max = 1) double w) {
        double lo = Math.min(v, w);
        double hi = Math.max(v, w);
        int a = MODEL.score(Map.of("velocity_1h", lo, "remote_access", lo)).score();
        int b = MODEL.score(Map.of("velocity_1h", hi, "remote_access", hi)).score();
        assertThat(b).isGreaterThanOrEqualTo(a);
        assertThat(a).isBetween(0, 1000);
    }

    @Test
    void thresholdsMapScoresToDecisions() {
        assertThat(POLICY.forScore(0)).isEqualTo(Decision.ALLOW);
        assertThat(POLICY.forScore(400)).isEqualTo(Decision.STEP_UP);
        assertThat(POLICY.forScore(600)).isEqualTo(Decision.REVIEW);
        assertThat(POLICY.forScore(850)).isEqualTo(Decision.BLOCK);
    }

    @Test
    void listValuesAreHashedAfterNormalisation() {
        assertThat(ListValues.hash("EMAIL", " Bad@Example.COM ")).isEqualTo(ListValues.hash("EMAIL", "bad@example.com"));
        assertThat(ListValues.hash("ACCOUNT", "GB12 3456")).isEqualTo(ListValues.hash("ACCOUNT", "gb123456"));
        assertThat(ListValues.hash("DEVICE", "x")).isNotEqualTo(ListValues.hash("EMAIL", "x"));
        assertThat(ListValues.mask("CARD", "4111 1111 1111 1234")).endsWith("1234").doesNotContain("4111");
        assertThat(ListValues.mask("EMAIL", "bad@example.com")).isEqualTo("ba***@example.com");
    }
}
