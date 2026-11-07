package io.meridian.fraud.application;

import java.util.Locale;

/**
 * Per-tenant currency calibration of the scale-sensitive model features (amount_log, txn_amount, amount sums).
 * The models were calibrated on one currency; a tenant whose amounts are much larger or smaller per unit of value
 * (COP vs KES, about 30x) sets {@code amount_scale.<CCY>} in its fraud-service settings (config-service namespace
 * {@code ledger}, key {@code fraud-service}), e.g. {@code {"amount_scale": {"COP": "0.0315"}}}: amounts are multiplied
 * by the factor before those features are computed, so COP 9.5M scores like KES 300k, not like KES 9.5M. Rule
 * facts keep the raw amount in the tenant's currency. No setting (default) = factor 1 = unchanged behaviour.
 */
final class AmountScale {
    private AmountScale() {
    }

    static double of(String currency) {
        if (currency == null || currency.isBlank()) {
            return 1.0;
        }
        return io.meridian.web.config.TenantSettings.json("fraud-service",
                        "amount_scale." + currency.trim().toUpperCase(Locale.ROOT))
                .map(n -> {
                    try {
                        return Double.parseDouble(n.asString());
                    } catch (RuntimeException e) {
                        return 1.0;
                    }
                })
                .filter(d -> d > 0 && Double.isFinite(d))
                .orElse(1.0);
    }
}
