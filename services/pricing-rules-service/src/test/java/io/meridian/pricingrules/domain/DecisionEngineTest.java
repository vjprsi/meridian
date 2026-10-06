package io.meridian.pricingrules.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class DecisionEngineTest {

    // ------------------------------------------------------------------ unary tests

    @Test
    void wildcardMatchesEverythingIncludingNull() {
        UnaryTest t = UnaryTest.parse("-", ValueType.NUMBER);
        assertThat(t.matches(null)).isTrue();
        assertThat(t.matches(new BigDecimal("5"))).isTrue();
    }

    @Test
    void numericComparisonsAreByValueNotText() {
        assertThat(UnaryTest.parse("< 10", ValueType.NUMBER).matches(new BigDecimal("9.99"))).isTrue();
        assertThat(UnaryTest.parse("< 10", ValueType.NUMBER).matches(new BigDecimal("10.00"))).isFalse();
        assertThat(UnaryTest.parse(">= 700", ValueType.NUMBER).matches(700)).isTrue();
        assertThat(UnaryTest.parse("= 5", ValueType.NUMBER).matches(new BigDecimal("5.000"))).isTrue();
        assertThat(UnaryTest.parse("5", ValueType.NUMBER).matches("5.0")).isTrue();
        assertThat(UnaryTest.parse("!= 5", ValueType.NUMBER).matches(6)).isTrue();
    }

    @Test
    void rangesRespectInclusiveAndExclusiveEnds() {
        UnaryTest closed = UnaryTest.parse("[700..850]", ValueType.NUMBER);
        assertThat(closed.matches(700)).isTrue();
        assertThat(closed.matches(850)).isTrue();
        assertThat(closed.matches(851)).isFalse();
        UnaryTest open = UnaryTest.parse("(700..850)", ValueType.NUMBER);
        assertThat(open.matches(700)).isFalse();
        assertThat(open.matches(701)).isTrue();
        UnaryTest halfOpen = UnaryTest.parse("[0..1000[", ValueType.NUMBER);
        assertThat(halfOpen.matches(0)).isTrue();
        assertThat(halfOpen.matches(1000)).isFalse();
    }

    @Test
    void stringListsAndNegation() {
        UnaryTest in = UnaryTest.parse("\"GOLD\",\"PLATINUM\"", ValueType.STRING);
        assertThat(in.matches("GOLD")).isTrue();
        assertThat(in.matches("gold")).isFalse();
        assertThat(in.matches("SILVER")).isFalse();
        UnaryTest bare = UnaryTest.parse("RETAIL, SME", ValueType.STRING);
        assertThat(bare.matches("SME")).isTrue();
        UnaryTest not = UnaryTest.parse("not(\"GOLD\",\"PLATINUM\")", ValueType.STRING);
        assertThat(not.matches("SILVER")).isTrue();
        assertThat(not.matches("GOLD")).isFalse();
    }

    @Test
    void datesAndBooleans() {
        UnaryTest d = UnaryTest.parse(">= date(\"2026-01-01\")", ValueType.DATE);
        assertThat(d.matches(LocalDate.of(2026, 3, 1))).isTrue();
        assertThat(d.matches("2025-12-31")).isFalse();
        UnaryTest b = UnaryTest.parse("true", ValueType.BOOLEAN);
        assertThat(b.matches(Boolean.TRUE)).isTrue();
        assertThat(b.matches("false")).isFalse();
    }

    @Test
    void typeMismatchNeverMatches() {
        assertThat(UnaryTest.parse("< 10", ValueType.ANY).matches("abc")).isFalse();
        assertThat(UnaryTest.parse("[1..2]", ValueType.NUMBER).matches(null)).isFalse();
    }

    @Test
    void invalidExpressionsAreRejected() {
        assertThatThrownBy(() -> UnaryTest.parse("[10..1]", ValueType.NUMBER))
                .isInstanceOf(DecisionTableException.class).hasMessageContaining("lower end");
        assertThatThrownBy(() -> UnaryTest.parse("< abc", ValueType.NUMBER))
                .isInstanceOf(DecisionTableException.class);
        assertThatThrownBy(() -> UnaryTest.parse("\"open", ValueType.STRING))
                .isInstanceOf(DecisionTableException.class);
    }

    // ------------------------------------------------------------------ hit policies

    @Test
    void firstHitReturnsFirstMatchingRule() {
        DecisionTableModel t = pricingGrid("FIRST", null);
        DecisionEngine.Outcome o = DecisionEngine.evaluate(t, Map.of("credit_score", 760, "segment", "RETAIL"));
        assertThat(o.matchedRules()).containsExactly(1, 3);
        assertThat(o.selectedRules()).containsExactly(1);
        assertThat((BigDecimal) o.outputs().get("rate")).isEqualByComparingTo("0.05");
        assertThat(o.outputs().get("tier")).isEqualTo("PRIME");
    }

    @Test
    void uniqueHitPolicyRejectsOverlap() {
        DecisionTableModel t = pricingGrid("UNIQUE", null);
        assertThatThrownBy(() -> DecisionEngine.evaluate(t, Map.of("credit_score", 760, "segment", "RETAIL")))
                .isInstanceOf(DecisionTableException.class)
                .extracting(e -> ((DecisionTableException) e).code()).isEqualTo("hit_policy_violation");
        DecisionEngine.Outcome ok = DecisionEngine.evaluate(t, Map.of("credit_score", 650, "segment", "SME"));
        assertThat(ok.selectedRules()).containsExactly(2);
    }

    @Test
    void collectAggregatesAndListsInRuleOrder() {
        DecisionTableModel sum = DecisionTableModel.parse("COLLECT", "SUM", inputs(),
                List.of(col("rate", "number")),
                List.of(row(List.of("[700..850]", "-"), List.of("0.05")),
                        row(List.of("[600..700)", "-"), List.of("0.08")),
                        row(List.of(">= 750", "RETAIL"), List.of("0.09"))));
        DecisionEngine.Outcome o = DecisionEngine.evaluate(sum, Map.of("credit_score", 760, "segment", "RETAIL"));
        assertThat((BigDecimal) o.outputs().get("rate")).isEqualByComparingTo("0.14");

        DecisionTableModel list = DecisionTableModel.parse("RULE_ORDER", null,
                List.of(col("x", "number")),
                List.of(col("r", "number")),
                List.of(row(List.of("> 1"), List.of("1")), row(List.of("> 2"), List.of("2"))));
        assertThat(DecisionEngine.evaluate(list, Map.of("x", 5)).outputs().get("r"))
                .isEqualTo(List.of(new BigDecimal("1"), new BigDecimal("2")));
    }

    @Test
    void priorityPicksLowestPriorityNumber() {
        Map<String, Object> r1 = row(List.of("-"), List.of("\"LOW\""));
        r1.put("priority", 5);
        Map<String, Object> r2 = row(List.of("-"), List.of("\"HIGH\""));
        r2.put("priority", 1);
        DecisionTableModel t = DecisionTableModel.parse("PRIORITY", null,
                List.of(col("x", "number")), List.of(col("p", "string")),
                List.of(r1, r2));
        assertThat(DecisionEngine.evaluate(t, Map.of("x", 1)).outputs().get("p")).isEqualTo("HIGH");
    }

    @Test
    void anyRequiresIdenticalOutputs() {
        DecisionTableModel t = DecisionTableModel.parse("ANY", null,
                List.of(col("x", "number")), List.of(col("ok", "boolean")),
                List.of(row(List.of("> 1"), List.of("true")), row(List.of("> 2"), List.of("false"))));
        assertThat(DecisionEngine.evaluate(t, Map.of("x", 2)).outputs().get("ok")).isEqualTo(true);
        assertThatThrownBy(() -> DecisionEngine.evaluate(t, Map.of("x", 3))).isInstanceOf(DecisionTableException.class);
    }

    @Test
    void noMatchUsesDefaults() {
        DecisionTableModel t = pricingGrid("FIRST", null);
        DecisionEngine.Outcome o = DecisionEngine.evaluate(t, Map.of("credit_score", 300, "segment", "CORPORATE"));
        assertThat(o.defaulted()).isTrue();
        assertThat((BigDecimal) o.outputs().get("rate")).isEqualByComparingTo("0.2");
        assertThat(o.matchedRules()).isEmpty();
    }

    @Test
    void nestedInputPathsAndInvalidInputs() {
        DecisionTableModel t = DecisionTableModel.parse("FIRST", null,
                List.of(Map.of("name", "age", "type", "number", "expression", "applicant.age")),
                List.of(col("adult", "boolean")),
                List.of(row(List.of(">= 18"), List.of("true")), row(List.of("-"), List.of("false"))));
        assertThat(DecisionEngine.evaluate(t, Map.of("applicant", Map.of("age", 30))).outputs().get("adult")).isEqualTo(true);
        assertThatThrownBy(() -> DecisionEngine.evaluate(t, Map.of("applicant", Map.of("age", "thirty"))))
                .isInstanceOf(DecisionTableException.class)
                .extracting(e -> ((DecisionTableException) e).code()).isEqualTo("invalid_input");
    }

    @Test
    void definitionErrorsAreAllReported() {
        assertThatThrownBy(() -> DecisionTableModel.parse("FIRST", null,
                List.of(col("a", "number")),
                List.of(),
                List.of(row(List.of("[5..1]", "-"), List.of()))))
                .isInstanceOf(DecisionTableException.class)
                .satisfies(e -> assertThat(((DecisionTableException) e).violations()).hasSizeGreaterThanOrEqualTo(2));
    }

    @Test
    void rulesKeyedByColumnName() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("inputs", Map.of("segment", "\"SME\""));
        rule.put("outputs", Map.of("rate", "0.07", "tier", "\"SME\""));
        DecisionTableModel t = DecisionTableModel.parse("FIRST", null, inputs(), outputs(null),
                List.of(rule));
        DecisionEngine.Outcome o = DecisionEngine.evaluate(t, Map.of("credit_score", 1, "segment", "SME"));
        assertThat((BigDecimal) o.outputs().get("rate")).isEqualByComparingTo("0.07");
    }

    // ------------------------------------------------------------------ properties

    @Property(tries = 300)
    void rangeMembershipAgreesWithNumericBounds(@ForAll @BigRange(min = "-1000", max = "1000") BigDecimal low,
                                                @ForAll @IntRange(min = 0, max = 500) int width,
                                                @ForAll @BigRange(min = "-2000", max = "2000") BigDecimal x) {
        BigDecimal high = low.add(BigDecimal.valueOf(width));
        UnaryTest closed = UnaryTest.parse("[" + low.toPlainString() + ".." + high.toPlainString() + "]", ValueType.NUMBER);
        UnaryTest open = UnaryTest.parse("(" + low.toPlainString() + ".." + high.toPlainString() + ")", ValueType.NUMBER);
        boolean in = x.compareTo(low) >= 0 && x.compareTo(high) <= 0;
        boolean strictlyIn = x.compareTo(low) > 0 && x.compareTo(high) < 0;
        assertThat(closed.matches(x)).isEqualTo(in);
        assertThat(open.matches(x)).isEqualTo(strictlyIn);
    }

    @Property(tries = 200)
    void collectSumEqualsSumOfMatchedRuleOutputs(@ForAll @IntRange(min = 0, max = 100) int x,
                                                 @ForAll @IntRange(min = 1, max = 8) int ruleCount) {
        List<Object> rows = new ArrayList<>();
        BigDecimal expected = BigDecimal.ZERO;
        for (int i = 1; i <= ruleCount; i++) {
            int threshold = i * 10;
            rows.add(row(List.of(">= " + threshold), List.of(String.valueOf(i))));
            if (x >= threshold) {
                expected = expected.add(BigDecimal.valueOf(i));
            }
        }
        DecisionTableModel t = DecisionTableModel.parse("COLLECT", "SUM",
                List.of(col("x", "number")),
                List.of(col("points", "number")),
                rows);
        DecisionEngine.Outcome o = DecisionEngine.evaluate(t, Map.of("x", x));
        BigDecimal actual = o.defaulted() ? BigDecimal.ZERO : (BigDecimal) o.outputs().get("points");
        assertThat(actual).isEqualByComparingTo(expected);
    }

    @Property(tries = 100)
    void firstHitIsTheLowestMatchingRule(@ForAll @IntRange(min = 0, max = 100) int x) {
        DecisionTableModel t = DecisionTableModel.parse("FIRST", null,
                List.of(col("x", "number")),
                List.of(col("band", "number")),
                List.of(row(List.of("< 25"), List.of("1")), row(List.of("< 50"), List.of("2")),
                        row(List.of("< 75"), List.of("3")), row(List.of("-"), List.of("4"))));
        int band = ((BigDecimal) DecisionEngine.evaluate(t, Map.of("x", x)).outputs().get("band")).intValue();
        assertThat(band).isEqualTo(x < 25 ? 1 : x < 50 ? 2 : x < 75 ? 3 : 4);
    }

    // ------------------------------------------------------------------ fixtures

    static DecisionTableModel pricingGrid(String hitPolicy, String aggregation) {
        return DecisionTableModel.parse(hitPolicy, aggregation, inputs(), outputs("0.20"),
                List.of(
                        row(List.of("[700..850]", "-"), List.of("0.05", "\"PRIME\"")),
                        row(List.of("[600..700)", "\"SME\",\"RETAIL\""), List.of("0.08", "\"NEAR_PRIME\"")),
                        row(List.of(">= 750", "RETAIL"), List.of("0.09", "\"RETAIL_BONUS\""))));
    }

    static List<Object> inputs() {
        return List.of(col("credit_score", "number"), col("segment", "string"));
    }

    static List<Object> outputs(String defaultRate) {
        Map<String, Object> rate = new LinkedHashMap<>(col("rate", "number"));
        if (defaultRate != null) {
            rate.put("default", defaultRate);
        }
        return List.of(rate, col("tier", "string"));
    }

    static Map<String, Object> col(String name, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        return m;
    }

    static Map<String, Object> row(List<String> in, List<String> out) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inputs", in);
        m.put("outputs", out);
        return m;
    }
}
