package io.meridian.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure unit tests of tier math, period arithmetic and invoice calculation. */
class PricingTest {

    private static TieredPrice apiTiers() {
        return TieredPrice.parse(Map.of("tiers", List.of(
                Map.of("up_to", 1000, "unit_price", "0"),
                Map.of("up_to", 10000, "unit_price", "0.01"),
                new java.util.HashMap<>(Map.of("unit_price", "0.005")))));
    }

    @Test
    void graduatedTiersChargeEachBand() {
        List<TieredPrice.TierCharge> c = apiTiers().charge(12_500);
        assertThat(c).hasSize(3);
        assertThat(c.get(0).quantity()).isEqualTo(1000);
        assertThat(c.get(1).quantity()).isEqualTo(9000);
        assertThat(c.get(1).amount()).isEqualByComparingTo("90.00");
        assertThat(c.get(2).quantity()).isEqualTo(2500);
        assertThat(c.get(2).amount()).isEqualByComparingTo("12.50");
        assertThat(c.get(2).label()).isEqualTo("tier 3 (10001+)");
        assertThat(apiTiers().total(12_500)).isEqualByComparingTo("102.50");
        assertThat(apiTiers().charge(0)).isEmpty();
        assertThat(apiTiers().total(1000)).isEqualByComparingTo("0");
    }

    @Test
    void rejectsMalformedTiers() {
        assertThatThrownBy(() -> TieredPrice.parse(Map.of("tiers", List.of(
                Map.of("up_to", 100, "unit_price", "1"), Map.of("up_to", 50, "unit_price", "1")))))
                .isInstanceOf(PricingException.class);
        assertThatThrownBy(() -> TieredPrice.parse(Map.of("tiers", List.of(Map.of("up_to", 100, "unit_price", "1")))))
                .hasMessageContaining("open-ended");
        assertThatThrownBy(() -> TieredPrice.parse(Map.of("unit_price", "-1"))).isInstanceOf(PricingException.class);
        assertThatThrownBy(() -> UsageMetrics.parsePrices(Map.of("BOGUS", Map.of("unit_price", "1"))))
                .hasMessageContaining("unknown usage metric");
    }

    @Test
    void periodsAnchorOnStartWithoutDrift() {
        LocalDate anchor = LocalDate.of(2026, 1, 31);
        BillingPeriods.Period p = BillingPeriods.containing(anchor, "MONTHLY", LocalDate.of(2026, 3, 1));
        assertThat(p.start()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(p.end()).isEqualTo(LocalDate.of(2026, 3, 30));
        BillingPeriods.Period q = BillingPeriods.containing(LocalDate.of(2026, 1, 1), "QUARTERLY", LocalDate.of(2026, 5, 15));
        assertThat(q.start()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(q.end()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void prorationChargesRemainingDaysInclusive() {
        BillingPeriods.Period june = new BillingPeriods.Period(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        // 15 of 30 days remain from June 16 (inclusive): half the difference
        assertThat(BillingPeriods.proration(new BigDecimal("1000"), new BigDecimal("3000"), june, LocalDate.of(2026, 6, 16)))
                .isEqualByComparingTo("1000.00");
        assertThat(BillingPeriods.proration(new BigDecimal("3000"), new BigDecimal("1000"), june, LocalDate.of(2026, 6, 21)))
                .isEqualByComparingTo("-666.67");
    }

    @Test
    void invoiceAppliesDiscountAdjustmentsCommitmentAndTax() {
        InvoiceCalculator.Result r = InvoiceCalculator.calculate(new InvoiceCalculator.Input(
                "BASIC", 1, new BigDecimal("1000"), BigDecimal.ONE, 100, new BigDecimal("0.50"), 150,
                Map.of("API_CALL", 2000L, "PAYMENT", 30L),
                Map.of("API_CALL", apiTiers(), "PAYMENT", TieredPrice.flat(new BigDecimal("0.10"))),
                new BigDecimal("0.1"),
                List.of(new InvoiceCalculator.Adjustment("Proration charge", new BigDecimal("50"))),
                null, new BigDecimal("0.2")));
        // 1000 + 25 + 0 + 10 + 3 = 1038; -103.80 discount; +50 proration = 984.20; tax 196.84
        assertThat(r.lines()).hasSize(7);
        assertThat(r.subtotal()).isEqualByComparingTo("984.20");
        assertThat(r.taxAmount()).isEqualByComparingTo("196.84");
        assertThat(r.total()).isEqualByComparingTo("1181.04");

        InvoiceCalculator.Result committed = InvoiceCalculator.calculate(new InvoiceCalculator.Input(
                "BASIC", 3, new BigDecimal("100"), BigDecimal.ONE, 0, BigDecimal.ZERO, 0, Map.of(), Map.of(),
                BigDecimal.ZERO, List.of(), new BigDecimal("500"), BigDecimal.ZERO));
        assertThat(committed.subtotal()).isEqualByComparingTo("1500.00");
        assertThat(committed.lines().getLast().description()).contains("Minimum commitment");
    }
}
