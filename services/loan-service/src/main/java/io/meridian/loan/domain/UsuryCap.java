package io.meridian.loan.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Maximum legal interest rate (Colombia: tasa de usura, 1.5 x the interes bancario corriente certified monthly by the
 * Superintendencia Financiera per credit modality; Codigo Penal art. 305, Codigo de Comercio art. 884). Rates are
 * compared as effective annual rates (TEA / EA), so a nominal rate is converted with its compounding frequency:
 * EA = (1 + r/m)^m - 1. Both the contractual rate and the default (moratorium) rate must not exceed the cap.
 *
 * <pre>
 * usury:                                  # loan-service settings of the tenant (config-service, namespace ledger)
 *   max_effective_annual_rate: "0.2502"   # default modality (consumo y ordinario)
 *   by_modality: {MICROCREDITO: "0.5480", BAJO_MONTO: "0.8000"}
 *   reference: "SFC certification 2026-11"
 *   valid_until: "2026-11-30"             # after this date bookings are refused until the cap is updated
 * </pre>
 * No {@code usury} settings = no cap (other countries keep their behaviour).
 */
public final class UsuryCap {
    private UsuryCap() {
    }

    /** The cap that applies to a modality (null/unknown modality = the default cap). */
    public record Cap(BigDecimal maxEffectiveAnnualRate, String modality, String reference, java.time.LocalDate validUntil) {}

    public static Optional<Cap> of(JsonNode cfg, String modality) {
        if (cfg == null || !cfg.isObject()) {
            return Optional.empty();
        }
        String m = modality == null ? null : modality.trim().toUpperCase(java.util.Locale.ROOT);
        JsonNode v = m != null && cfg.path("by_modality").has(m) ? cfg.path("by_modality").get(m) : cfg.get("max_effective_annual_rate");
        if (v == null || v.isNull()) {
            return Optional.empty();
        }
        java.time.LocalDate until = cfg.hasNonNull("valid_until") ? java.time.LocalDate.parse(cfg.get("valid_until").asString()) : null;
        return Optional.of(new Cap(new BigDecimal(v.asString()), m != null && cfg.path("by_modality").has(m) ? m : "DEFAULT",
                cfg.path("reference").asString(null), until));
    }

    /** Effective annual rate of a nominal annual rate compounded {@code periodsPerYear} times a year (6 decimals). */
    public static BigDecimal effectiveAnnual(BigDecimal nominal, int periodsPerYear) {
        if (nominal == null || nominal.signum() == 0) {
            return BigDecimal.ZERO.setScale(6);
        }
        int m = Math.max(1, periodsPerYear);
        BigDecimal per = nominal.divide(BigDecimal.valueOf(m), MathContext.DECIMAL64);
        return BigDecimal.ONE.add(per).pow(m, MathContext.DECIMAL64).subtract(BigDecimal.ONE).setScale(6, RoundingMode.HALF_UP);
    }

    /** Highest nominal annual rate compounded {@code periodsPerYear} times a year that stays within the cap. */
    public static BigDecimal maxNominal(BigDecimal maxEa, int periodsPerYear) {
        int m = Math.max(1, periodsPerYear);
        double r = m * (Math.pow(1.0 + maxEa.doubleValue(), 1.0 / m) - 1.0);
        return BigDecimal.valueOf(r).setScale(6, RoundingMode.DOWN);
    }
}
