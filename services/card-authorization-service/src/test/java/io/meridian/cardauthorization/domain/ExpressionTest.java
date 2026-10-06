package io.meridian.cardauthorization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class ExpressionTest {
    static Map<String, Object> vars() {
        Map<String, Object> v = new HashMap<>();
        v.put("amount", new BigDecimal("250.00"));
        v.put("mcc", "7995");
        v.put("channel", "ECOM");
        v.put("is_international", true);
        v.put("card_present", false);
        v.put("hour", 23);
        v.put("merchant_country", "GB");
        return v;
    }

    static boolean eval(String e) {
        return Expression.parse(e).evaluate(vars());
    }

    @Test
    void comparisonsAndLogic() {
        assertThat(eval("amount > 100")).isTrue();
        assertThat(eval("amount >= 250 && amount <= 250.00")).isTrue();
        assertThat(eval("amount < 100 || mcc == '7995'")).isTrue();
        assertThat(eval("!(channel == 'ECOM')")).isFalse();
        assertThat(eval("channel != \"POS\"")).isTrue();
        assertThat(eval("is_international && !card_present")).isTrue();
        assertThat(eval("hour >= 22 || hour < 6")).isTrue();
        assertThat(eval("(amount > 1000 || mcc == '7995') && merchant_country in ['GB', 'IE']")).isTrue();
    }

    @Test
    void inListAndNumericStrings() {
        assertThat(eval("mcc in ['7995', '7800']")).isTrue();
        assertThat(eval("mcc in [7995]")).isTrue(); // numeric string vs number
        assertThat(eval("mcc in []")).isFalse();
        assertThat(eval("mcc == 7995")).isTrue();
        assertThat(eval("amount == '250'")).isTrue();
    }

    @Test
    void unknownVariablesNeverMatch() {
        assertThat(eval("fraud_score > 500")).isFalse();
        assertThat(eval("fraud_score < 500")).isFalse();
        assertThat(eval("fraud_score == null")).isTrue();
        assertThat(eval("missing in ['a']")).isFalse();
    }

    @Test
    void negativeNumbersAndIdentifiers() {
        assertThat(eval("amount > -1")).isTrue();
        assertThat(Expression.parse("mcc in ['1'] && amount > 5 || fraud_score > 1").identifiers())
                .containsExactly("mcc", "amount", "fraud_score");
    }

    @Test
    void syntaxErrorsAreReported() {
        for (String bad : List.of("", "amount >", "amount > 1 &&", "(amount > 1", "mcc in", "amount ~ 1", "'open",
                "amount > 1 1", "in == 1")) {
            assertThatThrownBy(() -> Expression.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Property(tries = 300)
    void numericComparisonMatchesBigDecimal(@ForAll @IntRange(min = -100_000, max = 100_000) int a,
                                            @ForAll @IntRange(min = -100_000, max = 100_000) int b) {
        Map<String, Object> v = Map.of("x", BigDecimal.valueOf(a, 2));
        String lit = BigDecimal.valueOf(b, 2).toPlainString();
        int cmp = BigDecimal.valueOf(a, 2).compareTo(BigDecimal.valueOf(b, 2));
        assertThat(Expression.parse("x < " + lit).evaluate(v)).isEqualTo(cmp < 0);
        assertThat(Expression.parse("x >= " + lit).evaluate(v)).isEqualTo(cmp >= 0);
        assertThat(Expression.parse("x == " + lit).evaluate(v)).isEqualTo(cmp == 0);
    }
}
