package io.meridian.loan.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class UsuryCapTest {
    private static final tools.jackson.databind.JsonNode CFG = JsonMapper.builder().build().readTree("""
            {"max_effective_annual_rate": "0.2502", "by_modality": {"MICROCREDITO": "0.5480"},
             "reference": "SFC 2026-11", "valid_until": "2026-11-30"}""");

    @Test
    void nominalMonthlyRatesConvertToEffectiveAnnual() {
        // 24% nominal monthly = 2% a month -> 26.8242% EA
        assertThat(UsuryCap.effectiveAnnual(new BigDecimal("0.24"), 12)).isEqualByComparingTo("0.268242");
        assertThat(UsuryCap.effectiveAnnual(new BigDecimal("0.12"), 1)).isEqualByComparingTo("0.12");
        assertThat(UsuryCap.effectiveAnnual(BigDecimal.ZERO, 12)).isEqualByComparingTo("0");
    }

    @Test
    void maxNominalIsTheInverseAndStaysWithinTheCap() {
        BigDecimal n = UsuryCap.maxNominal(new BigDecimal("0.2502"), 12);
        assertThat(UsuryCap.effectiveAnnual(n, 12)).isLessThanOrEqualTo(new BigDecimal("0.2502"));
        assertThat(n).isBetween(new BigDecimal("0.2250"), new BigDecimal("0.2260"));
    }

    @Test
    void capPerModalityFallsBackToTheDefault() {
        assertThat(UsuryCap.of(CFG, "microcredito").orElseThrow().maxEffectiveAnnualRate()).isEqualByComparingTo("0.5480");
        assertThat(UsuryCap.of(CFG, "CONSUMO").orElseThrow().maxEffectiveAnnualRate()).isEqualByComparingTo("0.2502");
        assertThat(UsuryCap.of(CFG, null).orElseThrow().modality()).isEqualTo("DEFAULT");
        assertThat(UsuryCap.of(null, null)).isEmpty();
    }
}
