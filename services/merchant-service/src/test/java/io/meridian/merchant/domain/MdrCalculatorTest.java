package io.meridian.merchant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MdrCalculatorTest {
    static final MdrCalculator.Plan PLAN = new MdrCalculator.Plan(new BigDecimal("0.015"), new BigDecimal("2.00"),
            new BigDecimal("5.00"), new BigDecimal("100.00"), new BigDecimal("10"), new BigDecimal("25"));

    @Test
    void percentagePlusFixedClampedToMinAndMax() {
        assertThat(MdrCalculator.fee(PLAN, new BigDecimal("1000"))).isEqualByComparingTo("17.00");
        assertThat(MdrCalculator.fee(PLAN, new BigDecimal("100"))).isEqualByComparingTo("5.00");     // 3.50 -> min
        assertThat(MdrCalculator.fee(PLAN, new BigDecimal("10000"))).isEqualByComparingTo("100.00"); // 152 -> max
        assertThat(MdrCalculator.fee(PLAN, new BigDecimal("3"))).isEqualByComparingTo("3.00");       // never above amount
    }

    @Test
    void halfEvenRounding() {
        MdrCalculator.Plan p = new MdrCalculator.Plan(new BigDecimal("0.01"), BigDecimal.ZERO, null, null, null, null);
        assertThat(MdrCalculator.fee(p, new BigDecimal("12.50"))).isEqualByComparingTo("0.12");
        assertThat(MdrCalculator.fee(p, new BigDecimal("13.50"))).isEqualByComparingTo("0.14");
    }

    @Test
    void settlementTotals() {
        MdrCalculator.Totals t = MdrCalculator.settle(PLAN, List.of(new BigDecimal("1000"), new BigDecimal("100")),
                new BigDecimal("50"), new BigDecimal("2"));
        assertThat(t.count()).isEqualTo(2);
        assertThat(t.gross()).isEqualByComparingTo("1100");
        assertThat(t.mdr()).isEqualByComparingTo("47.00"); // 17 + 5 + settlement fee 25
        assertThat(t.net()).isEqualByComparingTo("1001.00");
        MdrCalculator.Totals empty = MdrCalculator.settle(PLAN, List.of(), BigDecimal.ZERO, null);
        assertThat(empty.mdr()).isEqualByComparingTo("0");
    }

    @Test
    void invalidPlans() {
        assertThatThrownBy(() -> MdrCalculator.validate(new MdrCalculator.Plan(new BigDecimal("-0.01"), null, null, null,
                null, null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> MdrCalculator.validate(new MdrCalculator.Plan(new BigDecimal("1.5"), null, null, null,
                null, null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> MdrCalculator.validate(new MdrCalculator.Plan(BigDecimal.ZERO, null, BigDecimal.TEN,
                BigDecimal.ONE, null, null))).isInstanceOf(ApiException.class);
        MdrCalculator.validate(PLAN);
    }

    @Test
    void merchantCodesCarryALuhnDigit() {
        for (int i = 0; i < 50; i++) {
            String c = MerchantRules.merchantCode();
            assertThat(c).hasSize(7).doesNotStartWith("0");
            assertThat(MerchantRules.luhn(c.substring(0, 6))).isEqualTo(c.charAt(6) - '0');
        }
        assertThat(MerchantRules.linkCode()).hasSize(10);
    }
}
