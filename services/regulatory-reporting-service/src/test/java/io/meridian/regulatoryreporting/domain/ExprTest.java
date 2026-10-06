package io.meridian.regulatoryreporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import org.junit.jupiter.api.Test;

class ExprTest {
    private static Object eval(String e, Map<String, Object> vars) {
        return Expr.parse(e).eval(id -> ReportEngine.lookup(vars, id), Map.of("limit", new BigDecimal("10"), "codes", List.of("A", "B"))::get);
    }

    private static Object eval(String e) {
        return eval(e, Map.of());
    }

    @Test
    void arithmeticIsExactWithPrecedence() {
        assertThat((BigDecimal) eval("1 + 2 * 3")).isEqualByComparingTo("7");
        assertThat((BigDecimal) eval("(1 + 2) * 3")).isEqualByComparingTo("9");
        assertThat((BigDecimal) eval("0.1 + 0.2")).isEqualByComparingTo("0.3");
        assertThat((BigDecimal) eval("-2 * -3")).isEqualByComparingTo("6");
        assertThat((BigDecimal) eval("10 % 4")).isEqualByComparingTo("2");
        assertThat((BigDecimal) eval("ROUND(10 / 3, 2)")).isEqualByComparingTo("3.33");
        assertThat((BigDecimal) eval("ROUND(2.345, 2)")).isEqualByComparingTo("2.34");
    }

    @Test
    void variablesNestedPathsAndParameters() {
        Map<String, Object> row = Map.of("amount", "125.50", "balance", Map.of("currency", "USD"), "n", 3);
        assertThat((BigDecimal) eval("amount * n", row)).isEqualByComparingTo("376.50");
        assertThat(eval("balance.currency = 'USD'", row)).isEqualTo(true);
        assertThat(eval("n < :limit", row)).isEqualTo(true);
        assertThat(eval("'A' IN (:codes)", row)).isEqualTo(true);
        assertThat(Expr.parse("a + b.c * :p").identifiers()).containsExactly("a", "b.c");
        assertThat(Expr.parse("a + b.c * :p").parameters()).containsExactly("p");
    }

    @Test
    void booleansComparisonsAndNulls() {
        Map<String, Object> row = new HashMap<>();
        row.put("status", "ACTIVE");
        row.put("x", null);
        assertThat(eval("status = 'ACTIVE' AND NOT (status IN ('CLOSED', 'WRITTEN_OFF'))", row)).isEqualTo(true);
        assertThat(eval("status NOT IN ('ACTIVE')", row)).isEqualTo(false);
        assertThat(eval("x IS NULL AND status IS NOT NULL", row)).isEqualTo(true);
        assertThat(eval("x > 1", row)).isEqualTo(false);
        assertThat(eval("x + 1", row)).isNull();
        assertThat(eval("COALESCE(x, 5) = 5", row)).isEqualTo(true);
        assertThat(eval("IF(status = 'ACTIVE', 1, 0) = 1 || false", row)).isEqualTo(true);
        assertThat(eval("'2026-01-31' < '2026-02-01'")).isEqualTo(true);
        assertThat(eval("'10' = 10.00")).isEqualTo(true);
        assertThat((BigDecimal) eval("DAYS_BETWEEN('2026-01-01', '2026-03-01')")).isEqualByComparingTo("59");
        assertThat(eval("CONCAT('A', 1.50, 'B')")).isEqualTo("A1.50B");
    }

    @Test
    void invalidExpressionsAreRejectedWithPosition() {
        assertThatThrownBy(() -> Expr.parse("1 +")).hasMessageContaining("position");
        assertThatThrownBy(() -> Expr.parse("DROP(TABLE)")).hasMessageContaining("unknown function DROP");
        assertThatThrownBy(() -> Expr.parse("a; delete")).hasMessageContaining("unexpected character ';'");
        assertThatThrownBy(() -> Expr.parse("'open")).hasMessageContaining("unterminated");
        assertThatThrownBy(() -> Expr.parse("ROUND(1, 2, 3)")).hasMessageContaining("wrong number of arguments");
        assertThatThrownBy(() -> Expr.parse("(".repeat(200) + "1" + ")".repeat(200))).hasMessageContaining("nested too deeply");
        assertThatThrownBy(() -> Expr.parse("1".repeat(Expr.MAX_LENGTH + 1))).hasMessageContaining("longer than");
        assertThatThrownBy(() -> eval("1 / 0")).hasMessageContaining("division by zero");
        assertThatThrownBy(() -> eval("'abc' * 2")).hasMessageContaining("needs numbers");
    }

    @Property(tries = 200)
    void additionIsCommutativeAndExact(@ForAll @BigRange(min = "-1000000", max = "1000000") BigDecimal a,
                                       @ForAll @BigRange(min = "-1000000", max = "1000000") BigDecimal b) {
        Map<String, Object> v = Map.of("a", a, "b", b);
        BigDecimal ab = (BigDecimal) Expr.parse("a + b").eval(v::get);
        BigDecimal ba = (BigDecimal) Expr.parse("b + a").eval(v::get);
        assertThat(ab).isEqualByComparingTo(ba).isEqualByComparingTo(a.add(b));
    }
}
