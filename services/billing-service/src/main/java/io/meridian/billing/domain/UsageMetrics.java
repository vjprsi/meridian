package io.meridian.billing.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** UsageMetric values and how they aggregate over a billing period. */
public final class UsageMetrics {
    public static final List<String> ALL = List.of("ACTIVE_ACCOUNT", "API_CALL", "LEDGER_POSTING",
            "CARD_AUTHORIZATION", "PAYMENT", "WALLET_TRANSACTION", "NOTIFICATION_SMS", "NOTIFICATION_EMAIL",
            "KYC_CHECK", "SANCTIONS_SCREENING", "STORAGE_GB", "AI_TOKENS");

    /** Gauges are billed on the peak daily value in the period; all other metrics are summed. */
    public static final Set<String> GAUGES = Set.of("ACTIVE_ACCOUNT", "STORAGE_GB");

    private UsageMetrics() {}

    public static boolean isGauge(String metric) {
        return GAUGES.contains(metric);
    }

    /**
     * Validates a {@code metric_prices} object: keys are UsageMetric values (ACTIVE_ACCOUNT is priced through
     * price_per_active_account, not here) and each value is a valid tiered price.
     */
    public static Map<String, TieredPrice> parsePrices(Map<?, ?> metricPrices) {
        Map<String, TieredPrice> out = new LinkedHashMap<>();
        if (metricPrices == null) {
            return out;
        }
        metricPrices.forEach((k, v) -> {
            String metric = String.valueOf(k);
            if (!ALL.contains(metric)) {
                throw new PricingException("unknown usage metric '" + metric + "'");
            }
            if ("ACTIVE_ACCOUNT".equals(metric)) {
                throw new PricingException("ACTIVE_ACCOUNT is priced via price_per_active_account");
            }
            try {
                out.put(metric, TieredPrice.parse(v));
            } catch (PricingException e) {
                throw new PricingException(metric + ": " + e.getMessage());
            }
        });
        return out;
    }
}
