package io.meridian.liquidity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.liquidity.domain.LcrCalculator.Category;
import io.meridian.liquidity.domain.LcrCalculator.Line;
import java.math.BigDecimal;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import org.junit.jupiter.api.Test;

class LcrCalculatorTest {

    private static Line hqla(String code, String amount) {
        return new Line(code, code, Category.HQLA, new BigDecimal(amount), null);
    }

    private static Line out(String code, String amount) {
        return new Line(code, code, Category.OUTFLOW, new BigDecimal(amount), null);
    }

    private static Line in(String code, String amount) {
        return new Line(code, code, Category.INFLOW, new BigDecimal(amount), null);
    }

    /** Worked example without binding caps: HQLA 585, outflows 600, inflows 100, LCR 117%. */
    @Test
    void simpleRatioWithoutCaps() {
        LcrCalculator.Result r = LcrCalculator.calculate(List.of(
                hqla("L1", "500"), hqla("L2A", "100"),
                out("RETAIL_LESS_STABLE", "2000"), out("NON_OPERATIONAL_CORPORATE", "1000"),
                in("RETAIL_LOANS", "200")), null);
        assertThat(r.level1()).isEqualByComparingTo("500");
        assertThat(r.level2a()).isEqualByComparingTo("85");
        assertThat(r.capAdjustment()).isEqualByComparingTo("0");
        assertThat(r.totalHqla()).isEqualByComparingTo("585");
        assertThat(r.outflows()).isEqualByComparingTo("600");
        assertThat(r.inflows()).isEqualByComparingTo("100");
        assertThat(r.netOutflows()).isEqualByComparingTo("500");
        assertThat(r.ratio()).isEqualByComparingTo("1.17");
        assertThat(r.breach()).isFalse();
        assertThat(r.components()).hasSize(5);
        assertThat(r.components().get(2).factor()).isEqualByComparingTo("0.10");
        assertThat(r.components().get(2).weighted()).isEqualByComparingTo("200");
    }

    /**
     * Basel Annex 1 caps: L1 100, L2A 85 (100 less 15%), L2B 50 (100 less 50%). 15% cap: max(50 - 15/85 x 185,
     * 50 - 15/60 x 100, 0) = 25; 40% cap: max(85 + 50 - 25 - 2/3 x 100, 0) = 43.33; HQLA = 166.67 (= L1 / 60%).
     * Inflows 200 are capped at 75% of outflows 150 = 112.50.
     */
    @Test
    void levelTwoCapsAndInflowCap() {
        LcrCalculator.Result r = LcrCalculator.calculate(List.of(
                hqla("L1", "100"), hqla("L2A", "100"), hqla("L2B", "100"),
                out("RETAIL_STABLE", "1000"), out("FINANCIAL_INSTITUTION", "100"),
                in("CORPORATE_LOANS", "400")), new BigDecimal("1.0"));
        assertThat(r.level2b()).isEqualByComparingTo("50");
        assertThat(r.capAdjustment()).isEqualByComparingTo("68.33");
        assertThat(r.totalHqla()).isEqualByComparingTo("166.67");
        assertThat(r.outflows()).isEqualByComparingTo("150");
        assertThat(r.uncappedInflows()).isEqualByComparingTo("200");
        assertThat(r.inflows()).isEqualByComparingTo("112.50");
        assertThat(r.netOutflows()).isEqualByComparingTo("37.50");
        assertThat(r.ratio()).isEqualByComparingTo("4.4445333333");
    }

    @Test
    void factorOverrideAndBreach() {
        LcrCalculator.Result r = LcrCalculator.calculate(List.of(
                hqla("L1", "80"),
                new Line("big corp", "NON_OPERATIONAL_CORPORATE", Category.OUTFLOW, new BigDecimal("200"),
                        new BigDecimal("0.50"))), null);
        assertThat(r.outflows()).isEqualByComparingTo("100");
        assertThat(r.ratio()).isEqualByComparingTo("0.8");
        assertThat(r.breach()).isTrue();
    }

    @Test
    void rejectsUnknownCodesBadFactorsAndNoOutflows() {
        assertThatThrownBy(() -> LcrCalculator.calculate(List.of(out("MYSTERY", "1")), null))
                .isInstanceOf(DomainException.class).hasMessageContaining("MYSTERY");
        assertThatThrownBy(() -> LcrCalculator.calculate(List.of(
                new Line("1", "RETAIL_STABLE", Category.OUTFLOW, BigDecimal.TEN, new BigDecimal("1.5"))), null))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> LcrCalculator.calculate(List.of(hqla("L1", "10")), null))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("no_net_outflows");
        assertThat(LcrCalculator.categoryOf("L2B_RMBS")).isEqualTo(Category.HQLA);
        assertThat(LcrCalculator.categoryOf("REVERSE_REPO_L2A")).isEqualTo(Category.INFLOW);
    }

    /** After caps Level 1 is at least 60% and Level 2B at most 15% of HQLA; inflows never exceed 75% of outflows. */
    @Property(tries = 300)
    void capsAndInflowCapHold(@ForAll @BigRange(min = "0", max = "1000000") BigDecimal l1,
                              @ForAll @BigRange(min = "0", max = "1000000") BigDecimal l2a,
                              @ForAll @BigRange(min = "0", max = "1000000") BigDecimal l2b,
                              @ForAll @BigRange(min = "1", max = "1000000") BigDecimal outflow,
                              @ForAll @BigRange(min = "0", max = "2000000") BigDecimal inflow) {
        LcrCalculator.Result r = LcrCalculator.calculate(List.of(hqla("L1", l1.toPlainString()),
                hqla("L2A", l2a.toPlainString()), hqla("L2B", l2b.toPlainString()),
                out("FINANCIAL_INSTITUTION", outflow.toPlainString()),
                in("FINANCIAL_INSTITUTION_LOANS", inflow.toPlainString())), null);
        BigDecimal tolerance = new BigDecimal("0.05");
        assertThat(r.totalHqla().multiply(new BigDecimal("0.6")).subtract(r.level1()))
                .isLessThanOrEqualTo(tolerance);
        assertThat(r.totalHqla()).isLessThanOrEqualTo(r.level1().add(r.level2a()).add(r.level2b()).add(tolerance));
        assertThat(r.inflows()).isLessThanOrEqualTo(r.outflows().multiply(new BigDecimal("0.75")).add(tolerance));
        assertThat(r.netOutflows().signum()).isPositive();
    }
}
