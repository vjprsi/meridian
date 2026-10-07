package io.meridian.fraud.domain.features;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns what the feature store holds for an evaluation (window sums, profiles, links) into model features. Pure and
 * deterministic; the definitions registered in ai-platform's feature catalogue ({@link FeatureCatalog}) describe
 * exactly this computation. Features of an entity the request does not name are {@code NaN} (missing).
 */
public final class StoreFeatures {
    private StoreFeatures() {}

    /** Velocity features: entity type, feature prefix, windows, whether amounts are exposed. */
    public record Velocity(String entityType, String prefix, List<Buckets.Window> countWindows,
                           List<Buckets.Window> amountWindows) {}

    public static final List<Buckets.Window> ALL = List.of(Buckets.Window.H1_WINDOW, Buckets.Window.H24_WINDOW,
            Buckets.Window.D7_WINDOW);

    public static final List<Velocity> VELOCITY = List.of(
            new Velocity("CUSTOMER", "customer_txn", ALL, ALL),
            new Velocity("CARD", "card_txn", ALL, ALL),
            new Velocity("ACCOUNT", "account_txn", ALL, ALL),
            new Velocity("DEVICE", "device_txn", ALL, ALL),
            new Velocity("MERCHANT", "merchant_txn", ALL, List.of()),
            new Velocity("BENEFICIARY", "beneficiary_txn", List.of(Buckets.Window.H24_WINDOW, Buckets.Window.D7_WINDOW),
                    List.of(Buckets.Window.H24_WINDOW)),
            new Velocity("CUSTOMER_LOGIN", "customer_login", List.of(Buckets.Window.H1_WINDOW, Buckets.Window.H24_WINDOW),
                    List.of()));

    /** Prior fraud rate and its weight (pseudo-events) for the smoothed merchant risk. */
    public static final double MERCHANT_PRIOR_RATE = 0.001;
    public static final double MERCHANT_PRIOR_WEIGHT = 200.0;

    /**
     * What the store returned for one evaluation.
     *
     * @param keys          entity type -> key present in the request (absent = the request has no such entity)
     * @param windows       "ENTITY:suffix" -> {count, amount in the request currency}; absent = 0
     * @param customer      customer profile in the request currency (or null)
     * @param customerFirstSeen earliest first event of the customer in any currency (or null)
     * @param merchant      merchant profile (or null)
     * @param beneficiary   beneficiary profile (or null = never seen)
     * @param links         link type -> exists, for the pairs the request names
     */
    public record Snapshot(Instant now, Double amount, Map<String, String> keys, Map<String, double[]> windows,
                           Profile customer, Instant customerFirstSeen, Profile merchant, Profile beneficiary,
                           Map<String, Boolean> links) {}

    public static Map<String, Double> compute(Snapshot s) {
        Map<String, Double> f = new LinkedHashMap<>();
        for (Velocity v : VELOCITY) {
            boolean present = s.keys().containsKey(v.entityType());
            for (Buckets.Window w : v.countWindows()) {
                f.put(v.prefix() + "_count_" + w.suffix, present ? window(s, v.entityType(), w)[0] : Double.NaN);
            }
            for (Buckets.Window w : v.amountWindows()) {
                f.put(v.prefix() + "_amount_" + w.suffix, present ? window(s, v.entityType(), w)[1] : Double.NaN);
            }
        }
        f.put("new_payee", flag(s.links(), "CUSTOMER_BENEFICIARY", "ACCOUNT_BENEFICIARY"));
        f.put("new_device_for_customer", flag(s.links(), "CUSTOMER_DEVICE"));
        f.put("new_country_for_customer", flag(s.links(), "CUSTOMER_COUNTRY", "CARD_COUNTRY"));
        f.put("new_merchant_for_card", flag(s.links(), "CARD_MERCHANT", "CUSTOMER_MERCHANT"));

        boolean customer = s.keys().containsKey("CUSTOMER");
        Profile cp = s.customer() == null ? Profile.EMPTY : s.customer();
        f.put("amount_zscore", customer && s.amount() != null ? cp.zScore(s.amount()) : Double.NaN);
        f.put("customer_history_count", customer ? (double) cp.count() : Double.NaN);
        f.put("customer_tenure_days", !customer ? Double.NaN
                : s.customerFirstSeen() == null ? 0.0 : days(s.customerFirstSeen(), s.now()));
        f.put("hour_deviation", customer ? cp.hourDeviation(s.now()) : Double.NaN);

        boolean merchant = s.keys().containsKey("MERCHANT");
        Profile mp = s.merchant() == null ? Profile.EMPTY : s.merchant();
        f.put("merchant_risk", merchant ? merchantRisk(mp) : Double.NaN);
        f.put("merchant_history_count", merchant ? (double) mp.count() : Double.NaN);

        boolean beneficiary = s.keys().containsKey("BENEFICIARY");
        f.put("beneficiary_age_days", !beneficiary ? Double.NaN
                : s.beneficiary() == null || s.beneficiary().firstSeen() == null ? 0.0 : days(s.beneficiary().firstSeen(), s.now()));

        f.put("txn_amount", s.amount() == null ? Double.NaN : s.amount());
        f.put("hour_of_day", Math.floorMod(s.now().getEpochSecond(), 86_400L) / 3600.0);
        return f;
    }

    /** Smoothed confirmed-fraud rate: (fraud + w * p0) / (events + w). */
    public static double merchantRisk(Profile p) {
        double events = Math.max(p.count(), p.labelCount());
        return (p.fraudCount() + MERCHANT_PRIOR_WEIGHT * MERCHANT_PRIOR_RATE) / (events + MERCHANT_PRIOR_WEIGHT);
    }

    private static double[] window(Snapshot s, String entity, Buckets.Window w) {
        double[] v = s.windows().get(entity + ":" + w.suffix);
        return v == null ? new double[] {0.0, 0.0} : v;
    }

    /** 1 when none of the applicable links exists, 0 when one does, NaN when no link applies. */
    private static double flag(Map<String, Boolean> links, String... types) {
        boolean applicable = false;
        for (String t : types) {
            Boolean exists = links.get(t);
            if (exists != null) {
                applicable = true;
                if (exists) {
                    return 0.0;
                }
            }
        }
        return applicable ? 1.0 : Double.NaN;
    }

    private static double days(Instant from, Instant now) {
        return Math.max(0.0, Duration.between(from, now).toSeconds() / 86_400.0);
    }
}
