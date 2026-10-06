package io.meridian.liquidity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.liquidity.domain.NsfrCalculator.Line;
import io.meridian.liquidity.domain.NsfrCalculator.Side;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class NsfrCalculatorTest {

    private static Line l(String code, String amount) {
        return new Line(code, code, null, new BigDecimal(amount), null);
    }

    /**
     * ASF = 100 x 100% + 1000 x 95% + 200 x 0% = 1050; RSF = 300 x 5% + 800 x 85% + 400 x 65% + 1000 x 5% = 1005;
     * NSFR = 104.48%.
     */
    @Test
    void workedExample() {
        NsfrCalculator.Result r = NsfrCalculator.calculate(List.of(
                l("REGULATORY_CAPITAL", "100"), l("RETAIL_STABLE", "1000"), l("FINANCIAL_SHORT", "200"),
                l("L1_UNENCUMBERED", "300"), l("LOANS_LONG", "800"), l("RESIDENTIAL_MORTGAGES_LOW_RW", "400"),
                l("OFF_BALANCE_COMMITTED", "1000")), null);
        assertThat(r.asf()).isEqualByComparingTo("1050");
        assertThat(r.rsf()).isEqualByComparingTo("1005");
        assertThat(r.ratio()).isEqualByComparingTo("1.0447761194");
        assertThat(r.breach()).isFalse();
        assertThat(r.components()).extracting(NsfrCalculator.Component::side)
                .containsExactly(Side.ASF, Side.ASF, Side.ASF, Side.RSF, Side.RSF, Side.RSF, Side.RSF);
    }

    @Test
    void overrideBreachAndValidation() {
        NsfrCalculator.Result r = NsfrCalculator.calculate(List.of(
                new Line("deposits", "RETAIL_LESS_STABLE", Side.ASF, new BigDecimal("500"), new BigDecimal("0.80")),
                l("LOANS_SHORT", "1000")), new BigDecimal("1.0"));
        assertThat(r.asf()).isEqualByComparingTo("400");
        assertThat(r.rsf()).isEqualByComparingTo("500");
        assertThat(r.ratio()).isEqualByComparingTo("0.8");
        assertThat(r.breach()).isTrue();
        assertThatThrownBy(() -> NsfrCalculator.calculate(List.of(
                new Line("x", "LOANS_SHORT", Side.ASF, BigDecimal.ONE, null)), null))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("side_mismatch");
        assertThatThrownBy(() -> NsfrCalculator.calculate(List.of(l("NOPE", "1")), null))
                .extracting("code").isEqualTo("unknown_code");
        assertThatThrownBy(() -> NsfrCalculator.calculate(List.of(l("REGULATORY_CAPITAL", "1")), null))
                .extracting("code").isEqualTo("no_required_funding");
    }
}
