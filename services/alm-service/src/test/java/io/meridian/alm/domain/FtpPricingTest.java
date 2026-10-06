package io.meridian.alm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.alm.domain.FtpPricing.ExposureType;
import io.meridian.alm.domain.FtpPricing.Method;
import io.meridian.alm.domain.FtpPricing.Rule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FtpPricingTest {
    static final LocalDate D = LocalDate.of(2026, 1, 1);
    static final UUID LOAN_PRODUCT = UUID.fromString("0199a000-0000-7000-8000-00000000b001");
    static final Curve BASE = new Curve(List.of(new Curve.Point(30, new BigDecimal("0.03")),
            new Curve.Point(365, new BigDecimal("0.035"))));
    static final Curve LIQ = new Curve(List.of(new Curve.Point(30, new BigDecimal("0.001")),
            new Curve.Point(365, new BigDecimal("0.004"))));

    static Rule rule(UUID product, String segment, String currency, Method m, int priority, BigDecimal spread) {
        return new Rule(UUID.randomUUID(), product, segment, currency, m, "BASE", "LIQ", spread, null, priority,
                LocalDate.of(2025, 1, 1), null);
    }

    @Test
    void ruleResolutionPrefersSpecificityThenPriority() {
        Rule generic = rule(null, null, null, Method.MATCHED_MATURITY, 100, null);
        Rule eur = rule(null, null, "EUR", Method.MATCHED_MATURITY, 100, null);
        Rule eurUrgent = rule(null, null, "EUR", Method.POOLED, 10, null);
        Rule product = rule(LOAN_PRODUCT, null, null, Method.FIXED_SPREAD, 500, new BigDecimal("0.01"));
        Rule expired = new Rule(UUID.randomUUID(), LOAN_PRODUCT, "RETAIL", "EUR", Method.POOLED, "BASE", null, null,
                null, 1, LocalDate.of(2024, 1, 1), LocalDate.of(2025, 12, 31));
        List<Rule> rules = List.of(generic, eur, eurUrgent, product, expired);
        assertThat(FtpPricing.resolve(rules, LOAN_PRODUCT, "RETAIL", "EUR", D)).contains(product);
        assertThat(FtpPricing.resolve(rules, null, null, "EUR", D)).contains(eurUrgent);
        assertThat(FtpPricing.resolve(rules, null, null, "USD", D)).contains(generic);
        assertThat(FtpPricing.resolve(List.of(eur), null, null, "USD", D)).isEmpty();
    }

    @Test
    void matchedMaturityFixedSpreadAndPooled() {
        Rule mm = rule(null, null, null, Method.MATCHED_MATURITY, 100, null);
        FtpPricing.Pricing p = FtpPricing.price(mm, BASE, LIQ, FtpPricing.pricingTerm(mm, 365, null));
        assertThat(p.ftpRate()).isEqualByComparingTo("0.039");
        Rule spread = rule(null, null, null, Method.FIXED_SPREAD, 100, new BigDecimal("0.01"));
        assertThat(FtpPricing.price(spread, BASE, null, 365).ftpRate()).isEqualByComparingTo("0.045");
        Rule pooled = new Rule(UUID.randomUUID(), null, null, null, Method.POOLED, "BASE", null, null, null, 100, D, null);
        assertThat(FtpPricing.pricingTerm(pooled, 3650, null)).isEqualTo(30);
        Rule behavioural = rule(null, null, null, Method.BEHAVIOURAL, 100, null);
        assertThat(FtpPricing.pricingTerm(behavioural, 30, 400)).isEqualTo(400);
        assertThat(FtpPricing.pricingTerm(behavioural, 30, null)).isEqualTo(30);
        assertThatThrownBy(() -> FtpPricing.pricingTerm(mm, null, null)).hasMessageContaining("term_days");
    }

    @Test
    void marginAndPeriodCharge() {
        assertThat(FtpPricing.margin(ExposureType.LOAN, new BigDecimal("0.07"), new BigDecimal("0.045")))
                .isEqualByComparingTo("0.025");
        assertThat(FtpPricing.margin(ExposureType.DEPOSIT_ACCOUNT, new BigDecimal("0.01"), new BigDecimal("0.031")))
                .isEqualByComparingTo("0.021");
        // 100,000 * 4.5% * 31 / 365
        assertThat(FtpPricing.charge(new BigDecimal("100000"), new BigDecimal("0.045"), D, LocalDate.of(2026, 1, 31)))
                .isEqualByComparingTo("382.19");
        assertThat(FtpPricing.charge(new BigDecimal("50000"), new BigDecimal("0.031"), D, LocalDate.of(2026, 1, 31)))
                .isEqualByComparingTo("131.64");
    }
}
