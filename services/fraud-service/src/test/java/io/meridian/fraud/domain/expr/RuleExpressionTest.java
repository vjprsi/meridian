package io.meridian.fraud.domain.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class RuleExpressionTest {

    static RuleExpression.Bindings facts() {
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("is_new", true);
        device.put("customers_seen", 3);
        Map<String, Object> velocity = Map.of("count_1h", 4, "amount_24h", "1250.50");
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("amount", new BigDecimal("7500.00"));
        f.put("currency", "USD");
        f.put("channel", "WEB");
        f.put("counterparty_country", "NG");
        f.put("device", device);
        f.put("velocity", velocity);
        f.put("tags", List.of("a", "b"));
        f.put("missing", null);
        return RuleExpression.Bindings.of(f, Set.of("HIGH_RISK_COUNTRIES")::contains);
    }

    static boolean eval(String expr) {
        return RuleExpression.compile(expr).test(facts());
    }

    @Test
    void comparisonsAndBooleanOperators() {
        assertThat(eval("amount > 5000 && channel == 'WEB'")).isTrue();
        assertThat(eval("amount >= 7500 and device.is_new")).isTrue();
        assertThat(eval("amount < 100 || velocity.count_1h >= 4")).isTrue();
        assertThat(eval("!(amount > 5000)")).isFalse();
        assertThat(eval("not device.is_new")).isFalse();
        assertThat(eval("channel != \"POS\" && currency == 'USD'")).isTrue();
    }

    @Test
    void arithmeticWithPrecedenceAndNumericStrings() {
        assertThat(eval("amount > velocity.amount_24h * 5")).isTrue();     // 7500 > 6252.5
        assertThat(eval("amount > velocity.amount_24h * 6")).isFalse();    // 7500 > 7503
        assertThat(eval("1 + 2 * 3 == 7")).isTrue();
        assertThat(eval("(1 + 2) * 3 == 9")).isTrue();
        assertThat(eval("10 % 4 == 2 && -amount < 0")).isTrue();
        assertThat(RuleExpression.compile("amount / 0").evaluate(facts())).isNull();
    }

    @Test
    void membershipAndRanges() {
        assertThat(eval("counterparty_country in ['NG', 'GH', 'KP']")).isTrue();
        assertThat(eval("counterparty_country not in ['NG', 'GH']")).isFalse();
        assertThat(eval("amount in 5000..10000")).isTrue();
        assertThat(eval("amount in 1..10")).isFalse();
        assertThat(eval("amount not in 1..10")).isTrue();
        assertThat(eval("velocity.count_1h between 1 and 4")).isTrue();
        assertThat(eval("velocity.count_1h between 5 and 9 or device.customers_seen in [2, 3]")).isTrue();
        assertThat(eval("'b' in tags && size(tags) == 2")).isTrue();
    }

    @Test
    void functionsAndLists() {
        assertThat(eval("on_list('HIGH_RISK_COUNTRIES')")).isTrue();
        assertThat(eval("on_list('OTHER')")).isFalse();
        assertThat(eval("lower(channel) == 'web' && starts_with(currency, 'US') && contains(channel, 'E')")).isTrue();
        assertThat(eval("max(amount, 9000) == 9000 && abs(-3) == 3 && min(1, 2) == 1")).isTrue();
        assertThat(eval("has(device.is_new) && !has(missing) && !has(nothing.here)")).isTrue();
        assertThat(eval("coalesce(missing, 'x') == 'x'")).isTrue();
        RuleExpression e = RuleExpression.compile("on_list('A') && amount > 1 && device.is_new");
        assertThat(e.listCodes()).containsExactly("A");
        assertThat(e.identifiers()).containsExactlyInAnyOrder("amount", "device.is_new");
    }

    @Test
    void nullsNeverMatchComparisons() {
        assertThat(eval("missing > 1")).isFalse();
        assertThat(eval("missing < 1")).isFalse();
        assertThat(eval("missing == null")).isTrue();
        assertThat(eval("unknown.path != null")).isFalse();
        assertThat(eval("missing in [1, 2]")).isFalse();
        assertThat(eval("'abc' > 5")).isFalse(); // incomparable types are simply false
    }

    @Test
    void rejectsInvalidExpressionsWithPosition() {
        assertThatThrownBy(() -> RuleExpression.compile("amount > ")).isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> RuleExpression.compile("amount >> 5")).isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> RuleExpression.compile("exec('rm -rf')"))
                .isInstanceOf(ExpressionException.class).hasMessageContaining("unknown function");
        assertThatThrownBy(() -> RuleExpression.compile("on_list(channel)")).hasMessageContaining("string literal");
        assertThatThrownBy(() -> RuleExpression.compile("'open")).hasMessageContaining("unterminated");
        assertThatThrownBy(() -> RuleExpression.compile("amount # 1")).isInstanceOf(ExpressionException.class)
                .satisfies(e -> assertThat(((ExpressionException) e).position()).isEqualTo(7));
        assertThatThrownBy(() -> RuleExpression.compile("(".repeat(100) + "1" + ")".repeat(100)))
                .hasMessageContaining("nested too deeply");
        assertThatThrownBy(() -> RuleExpression.compile("1 + ".repeat(2000) + "1")).isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> RuleExpression.compile(" ")).hasMessageContaining("empty");
    }

    @Property(tries = 300)
    void integerComparisonsMatchJava(@ForAll @IntRange(min = -100_000, max = 100_000) int a,
                                     @ForAll @IntRange(min = -100_000, max = 100_000) int b) {
        Map<String, Object> f = Map.of("a", a, "b", b);
        RuleExpression.Bindings bind = RuleExpression.Bindings.of(f, x -> false);
        assertThat(RuleExpression.compile("a < b").test(bind)).isEqualTo(a < b);
        assertThat(RuleExpression.compile("a >= b").test(bind)).isEqualTo(a >= b);
        assertThat(RuleExpression.compile("a == b").test(bind)).isEqualTo(a == b);
        assertThat(RuleExpression.compile("a + b == " + ((long) a + b)).test(bind)).isTrue();
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        assertThat(RuleExpression.compile("a in " + lo + ".." + hi).test(bind)).isTrue();
    }

    @Property(tries = 500)
    void parserOnlyFailsWithExpressionException(@ForAll("junk") String source) {
        try {
            RuleExpression e = RuleExpression.compile(source);
            e.evaluate(facts()); // must never throw on any input
        } catch (ExpressionException expected) {
            // fine: a syntax error with a position
        }
    }

    @Provide
    Arbitrary<String> junk() {
        return Arbitraries.strings().withChars("abc().[],'\"!&|<>=+-*/%0123456789 ._inotandbetween")
                .ofMinLength(1).ofMaxLength(60);
    }
}
