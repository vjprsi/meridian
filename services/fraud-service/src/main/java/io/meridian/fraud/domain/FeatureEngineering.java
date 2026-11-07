package io.meridian.fraud.domain;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns the raw evaluation facts (event, device, velocity, session, list hits) into model features scaled to 0..1.
 * Pure and deterministic so that explanations and backtests reproduce the original evaluation exactly.
 */
public final class FeatureEngineering {
    private FeatureEngineering() {}

    public static Map<String, Double> engineer(Map<String, Object> facts) {
        Map<String, Double> f = new LinkedHashMap<>();
        double amount = num(facts.get("amount"));
        Map<String, Object> device = map(facts.get("device"));
        Map<String, Object> velocity = map(facts.get("velocity"));
        Map<String, Object> session = map(facts.get("session"));
        Map<String, Object> lists = map(facts.get("lists"));

        double amountRef = facts.containsKey("amount_ref") ? num(facts.get("amount_ref")) : amount;
        f.put("amount_log", amountRef <= 0 ? 0.0 : Math.min(Math.log10(1 + amountRef) / 6.0, 1.0));
        double avg = num(velocity.get("avg_amount_30d"));
        double count30 = num(velocity.get("count_30d"));
        double ratio;
        if (avg > 0) {
            ratio = clamp01((amount / avg - 1.0) / 9.0);
        } else {
            ratio = count30 == 0 && amount > 0 ? 0.3 : 0.0; // first transaction: mild uncertainty
        }
        f.put("amount_to_avg_ratio", ratio);
        f.put("velocity_1h", clamp01(num(velocity.get("count_1h")) / 5.0));
        f.put("velocity_24h", clamp01(num(velocity.get("count_24h")) / 20.0));
        f.put("new_device", bool(device.get("is_new")) ? 1.0 : 0.0);
        f.put("device_emulator", bool(device.get("is_emulator")) ? 1.0 : 0.0);
        f.put("device_rooted", bool(device.get("is_rooted")) ? 1.0 : 0.0);
        f.put("attestation_failed", Boolean.FALSE.equals(device.get("attestation_passed")) ? 1.0 : 0.0);
        f.put("remote_access", bool(device.get("remote_access_tool")) || bool(session.get("remote_access_detected")) ? 1.0 : 0.0);
        f.put("shared_device", clamp01((num(device.get("customers_seen")) - 1.0) / 3.0));
        f.put("on_active_call", bool(session.get("on_active_call")) ? 1.0 : 0.0);
        Object cc = facts.get("counterparty_country");
        Object ipc = facts.get("ip_country");
        f.put("cross_border", cc instanceof String a && ipc instanceof String b && !a.equalsIgnoreCase(b) ? 1.0 : 0.0);
        double hour = num(facts.get("hour_utc"));
        f.put("unusual_hour", facts.get("hour_utc") != null && hour >= 1 && hour <= 4 ? 1.0 : 0.0);
        f.put("multiple_devices_24h", clamp01((num(velocity.get("distinct_devices_24h")) - 1.0) / 3.0));
        f.put("watch_list_hit", lists.get("watch_hits") instanceof Collection<?> c && !c.isEmpty() ? 1.0 : 0.0);
        f.put("device_risk", clamp01(num(device.get("risk_score")) / 1000.0));
        f.put("trusted_device", bool(device.get("trusted")) ? 1.0 : 0.0);
        return f;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return new BigDecimal(s).doubleValue();
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    static boolean bool(Object o) {
        return Boolean.TRUE.equals(o);
    }

    private static double clamp01(double v) {
        return Double.isNaN(v) ? 0.0 : Math.max(0.0, Math.min(1.0, v));
    }
}
