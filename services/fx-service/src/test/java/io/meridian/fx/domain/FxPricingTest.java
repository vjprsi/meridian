package io.meridian.fx.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FxPricingTest {

    @Test
    void customerRateTakesTheMarginFromTheMid() {
        assertThat(FxPricing.customerRate(new BigDecimal("1.0850"), 50)).isEqualByComparingTo("1.079575");
        assertThat(FxPricing.customerRate(new BigDecimal("1.0850"), 0)).isEqualByComparingTo("1.0850");
        assertThat(FxPricing.customerRate(new BigDecimal("1.0850"), 50).scale()).isEqualTo(10);
    }

    @Test
    void sellSideFixedComputesBuyAmountInBuyMinorUnits() {
        FxPricing.Priced p = FxPricing.price("EUR", "USD", new BigDecimal("1000"), null, new BigDecimal("1.0850"), 50,
                new BigDecimal("2.5"));
        assertThat(p.sellAmount()).isEqualByComparingTo("1000.00");
        assertThat(p.buyAmount()).isEqualByComparingTo("1079.58"); // 1079.575 -> HALF_EVEN -> 1079.58 (8 is even)
        assertThat(p.fee()).isEqualByComparingTo("2.50");
        assertThat(p.totalDebit()).isEqualByComparingTo("1002.50");
    }

    @Test
    void buySideFixedComputesSellAmount() {
        FxPricing.Priced p = FxPricing.price("USD", "EUR", null, new BigDecimal("500.00"), new BigDecimal("0.92"), 100,
                null);
        // rate = 0.92 * 0.99 = 0.9108; sell = 500 / 0.9108 = 548.9679...
        assertThat(p.customerRate()).isEqualByComparingTo("0.9108");
        assertThat(p.sellAmount()).isEqualByComparingTo("548.97");
        assertThat(p.buyAmount()).isEqualByComparingTo("500.00");
        assertThat(p.fee()).isEqualByComparingTo("0");
    }

    @Test
    void zeroDecimalCurrenciesRoundToWholeUnits() {
        FxPricing.Priced p = FxPricing.price("USD", "JPY", new BigDecimal("100"), null, new BigDecimal("149.555"), 0, null);
        assertThat(p.buyAmount()).isEqualByComparingTo("14956");
        assertThat(p.buyAmount().scale()).isZero();
    }

    @Test
    void halfEvenRounding() {
        assertThat(FxPricing.round(new BigDecimal("10.125"), "EUR")).isEqualByComparingTo("10.12");
        assertThat(FxPricing.round(new BigDecimal("10.135"), "EUR")).isEqualByComparingTo("10.14");
    }

    @Test
    void exactlyOneSideMustBeGiven() {
        assertThatThrownBy(() -> FxPricing.price("EUR", "USD", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxPricing.price("EUR", "USD", null, null, BigDecimal.ONE, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxPricing.price("EUR", "USD", BigDecimal.ZERO, null, BigDecimal.ONE, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void remittanceFeeIsFixedPlusPercent() {
        assertThat(FxPricing.remittanceFee(new BigDecimal("3.99"), new BigDecimal("0.01"), new BigDecimal("250.00"), "GBP"))
                .isEqualByComparingTo("6.49");
        assertThat(FxPricing.remittanceFee(null, null, new BigDecimal("250.00"), "GBP")).isEqualByComparingTo("0");
    }

    @Test
    void codesAndBusinessDays() {
        assertThat(FxPricing.isCurrency("EUR")).isTrue();
        assertThat(FxPricing.isCurrency("XYZ")).isFalse();
        assertThat(FxPricing.isCurrency("eur")).isFalse();
        assertThat(FxPricing.isCountry("KE")).isTrue();
        assertThat(FxPricing.isCountry("QQ")).isFalse();
        // Thursday 2026-10-08 + 2 business days = Monday 2026-10-12
        assertThat(FxPricing.addBusinessDays(LocalDate.parse("2026-10-08"), 2)).isEqualTo("2026-10-12");
        assertThat(FxPricing.addBusinessDays(LocalDate.parse("2026-10-05"), 2)).isEqualTo("2026-10-07");
    }
}
