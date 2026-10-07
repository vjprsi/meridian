package io.meridian.fraud.domain.features;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every feature a fraud model may use, with the exact definition registered in ai-platform's feature catalogue
 * ({@code feature_set = fraud-realtime-v1}). Models naming anything else are refused at load time.
 */
public final class FeatureCatalog {
    public static final String FEATURE_SET = "fraud-realtime-v1";
    static final List<String> TXN_EVENTS = List.of(FeatureEvent.PAYMENT_INITIATED + " (OUTBOUND)",
            FeatureEvent.CARD_APPROVED, FeatureEvent.CARD_DECLINED, FeatureEvent.WALLET_INITIATED);
    static final String WINDOW_SEMANTICS = "trailing window aligned to buckets: buckets whose start >= floor(now, bucket) - "
            + "window + bucket (55-60 min for 1h with 5-minute buckets, 23-24 h / 167-168 h with hourly buckets); "
            + "events are bucketed by their own time (payload created_at, else ce_time)";

    /** A catalogue entry: name, entity, dtype (INT, FLOAT, BOOL), description, exact definition. */
    public record Def(String name, String entity, String dtype, String description, boolean pii,
                      Map<String, Object> definition) {}

    private static final List<Def> DEFS = build();
    private static final Set<String> NAMES = names();

    private FeatureCatalog() {}

    public static List<Def> all() {
        return DEFS;
    }

    public static Set<String> names() {
        if (NAMES != null) {
            return NAMES;
        }
        Set<String> s = new LinkedHashSet<>();
        DEFS.forEach(d -> s.add(d.name()));
        return Collections.unmodifiableSet(s);
    }

    private static List<Def> build() {
        List<Def> out = new ArrayList<>();
        Map<String, String> keyOf = Map.of(
                "CUSTOMER", "customer_id (request) = customer_id (event)",
                "CARD", "card_id (request) = card_id (card authorisation event)",
                "ACCOUNT", "context.wallet_id, else context.debtor_account_id, else account_id (request) = debtor_account_id / debtor_wallet_id (payment), payer_wallet_id (wallet)",
                "DEVICE", "device_fingerprint (request) -> device_fingerprint.id = device_fingerprint_id (risk.fraud_score.evaluated)",
                "MERCHANT", "context.merchant_id (request) = merchant_id (card, wallet) / creditor_merchant_id (payment)",
                "BENEFICIARY", "payee:<payee_id>, else acct:h1:<HMAC-SHA256(tenant key, 'acct|' + sha256('BENEFICIARY_ACCOUNT:' + "
                        + "account without spaces/dashes, upper) + '|' + BIC)[0..40]>, else wallet:<wallet id>, else "
                        + "msisdn:h1:<HMAC-SHA256(tenant key, 'msisdn|' + digits)[0..40]>; request: context.payee_id, "
                        + "beneficiary_account (or beneficiary_account_digest) + beneficiary_bic, payee_wallet_id, payee_msisdn",
                "CUSTOMER_LOGIN", "customer_id of LOGIN evaluations");
        for (StoreFeatures.Velocity v : StoreFeatures.VELOCITY) {
            boolean login = v.entityType().equals("CUSTOMER_LOGIN");
            boolean device = v.entityType().equals("DEVICE");
            List<String> sources = login ? List.of(FeatureEvent.FRAUD_EVALUATED + " (subject_type LOGIN)")
                    : device ? List.of(FeatureEvent.FRAUD_EVALUATED + " (any subject type except LOGIN, with a device)") : TXN_EVENTS;
            String entity = switch (v.entityType()) {
                case "CUSTOMER_LOGIN" -> "CUSTOMER";
                default -> v.entityType();
            };
            for (Buckets.Window w : v.countWindows()) {
                out.add(new Def(v.prefix() + "_count_" + w.suffix, entity, "INT",
                        "Number of " + (login ? "logins" : "transactions") + " of the " + entity.toLowerCase() + " in the last " + w.suffix,
                        false, velocity(sources, keyOf.get(v.entityType()), "count(*)", w)));
            }
            for (Buckets.Window w : v.amountWindows()) {
                Map<String, Object> d = velocity(sources, keyOf.get(v.entityType()), "sum(amount)", w);
                d.put("currency", "only events in the request currency (card: billing currency) are summed");
                out.add(new Def(v.prefix() + "_amount_" + w.suffix, entity, "FLOAT",
                        "Amount of the " + entity.toLowerCase() + "'s transactions in the last " + w.suffix + " (request currency)",
                        false, d));
            }
        }
        out.add(flag("new_payee", "BENEFICIARY", "No earlier transaction from this customer (or account) to the beneficiary",
                List.of("CUSTOMER_BENEFICIARY", "ACCOUNT_BENEFICIARY")));
        out.add(flag("new_device_for_customer", "DEVICE", "The customer was never scored on this device before",
                List.of("CUSTOMER_DEVICE")));
        out.add(flag("new_country_for_customer", "CUSTOMER",
                "No earlier transaction of the customer (or card) with this counterparty / merchant country "
                        + "(request counterparty_country, else context.merchant_country)", List.of("CUSTOMER_COUNTRY", "CARD_COUNTRY")));
        out.add(flag("new_merchant_for_card", "CARD", "No earlier transaction of the card (or customer) at this merchant",
                List.of("CARD_MERCHANT", "CUSTOMER_MERCHANT")));
        out.add(new Def("amount_zscore", "CUSTOMER", "FLOAT", "Amount z-score against the customer's history in the same currency",
                false, map("source_events", TXN_EVENTS, "entity_key", keyOf.get("CUSTOMER"),
                        "statistic", "exponentially weighted mean m and variance v of amounts per (customer, currency): a = max(1/(n+1), "
                                + Profile.ALPHA_MIN + "), d = x - m, m += a*d, v = (1-a)*(v + a*d*d)",
                        "transform", "(amount - m) / max(sqrt(v), 0.01*|m|, 1e-9), clamped to [-10, 10]; 0 with fewer than "
                                + Profile.MIN_HISTORY + " events",
                        "missing", "NaN without customer or amount")));
        out.add(new Def("customer_history_count", "CUSTOMER", "INT", "Transactions of the customer seen in the request currency (all time)",
                false, map("source_events", TXN_EVENTS, "aggregation", "count per (customer, currency) profile", "missing", "NaN without customer")));
        out.add(new Def("customer_tenure_days", "CUSTOMER", "FLOAT", "Days since the customer's first transaction in any currency",
                false, map("source_events", TXN_EVENTS, "aggregation", "now - min(first_seen_at)", "missing", "NaN without customer; 0 when never seen")));
        out.add(new Def("hour_deviation", "CUSTOMER", "FLOAT", "How unusual the time of day is for the customer (0 usual .. 1 opposite)",
                false, map("source_events", TXN_EVENTS, "statistic", "exponentially weighted mean (sin, cos) of the UTC time-of-day angle, same weights as amount_zscore",
                        "transform", "R * (1 - cos(theta - mu)) / 2 with R, mu the length and direction of the mean vector; 0 with fewer than "
                                + Profile.MIN_HISTORY + " events", "missing", "NaN without customer")));
        out.add(new Def("merchant_risk", "MERCHANT", "FLOAT", "Smoothed confirmed-fraud rate of the merchant",
                false, map("source_events", List.of("fraud_feedback (label FRAUD / NOT_FRAUD with context.merchant_id)", "transactions for the event count"),
                        "transform", "(fraud_labels + " + StoreFeatures.MERCHANT_PRIOR_WEIGHT + " * " + StoreFeatures.MERCHANT_PRIOR_RATE
                                + ") / (max(events, labels) + " + StoreFeatures.MERCHANT_PRIOR_WEIGHT + ")", "missing", "NaN without merchant")));
        out.add(new Def("merchant_history_count", "MERCHANT", "INT", "Transactions seen at the merchant (all time)",
                false, map("source_events", TXN_EVENTS, "missing", "NaN without merchant")));
        out.add(new Def("beneficiary_age_days", "BENEFICIARY", "FLOAT", "Days since anyone in the tenant first paid the beneficiary",
                false, map("source_events", List.of(FeatureEvent.PAYMENT_INITIATED, FeatureEvent.WALLET_INITIATED), "entity_key", keyOf.get("BENEFICIARY"),
                        "missing", "NaN without beneficiary; 0 when never seen")));
        out.add(new Def("txn_amount", "TRANSACTION", "FLOAT", "Amount of the event being scored (request currency)", false,
                map("source", "evaluate request amount", "missing", "NaN without amount")));
        out.add(new Def("hour_of_day", "TRANSACTION", "FLOAT", "UTC time of day of the evaluation in hours (0-24)", false,
                map("source", "evaluation time")));
        Map<String, String> engineered = new LinkedHashMap<>();
        engineered.put("amount_log", "min(log10(1 + amount) / 6, 1)");
        engineered.put("amount_to_avg_ratio", "clamp((amount / avg_amount_30d - 1) / 9, 0, 1); 0.3 for a first transaction");
        engineered.put("velocity_1h", "min(scored events of the customer in 1 h / 5, 1) (fraud_score history)");
        engineered.put("velocity_24h", "min(scored events of the customer in 24 h / 20, 1) (fraud_score history)");
        engineered.put("new_device", "1 when the device fingerprint is seen for the first time");
        engineered.put("device_emulator", "1 when the SDK reports an emulator");
        engineered.put("device_rooted", "1 when the SDK reports a rooted / jailbroken device");
        engineered.put("attestation_failed", "1 when app attestation failed");
        engineered.put("remote_access", "1 when a remote-access tool is active (device or session signal)");
        engineered.put("shared_device", "clamp((customers seen on the device - 1) / 3, 0, 1)");
        engineered.put("on_active_call", "1 when the session reports an active phone call");
        engineered.put("cross_border", "1 when counterparty_country differs from ip_country");
        engineered.put("unusual_hour", "1 when the UTC hour is 1-4");
        engineered.put("multiple_devices_24h", "clamp((distinct devices of the customer in 24 h - 1) / 3, 0, 1)");
        engineered.put("watch_list_hit", "1 when a value of the event is on an active WATCH list");
        engineered.put("device_risk", "device reputation / 1000");
        engineered.put("trusted_device", "1 when the device was marked trusted after strong authentication");
        Set<String> customerLevel = Set.of("velocity_1h", "velocity_24h", "amount_to_avg_ratio", "multiple_devices_24h");
        Set<String> eventLevel = Set.of("amount_log", "cross_border", "unusual_hour", "watch_list_hit", "on_active_call", "remote_access");
        engineered.forEach((name, def) -> out.add(new Def(name, customerLevel.contains(name) ? "CUSTOMER"
                : eventLevel.contains(name) ? "TRANSACTION" : "DEVICE", "FLOAT", def, false,
                map("source", "fraud-service evaluation facts (FeatureEngineering)", "transform", def))));
        return List.copyOf(out);
    }

    private static Map<String, Object> velocity(List<String> sources, String key, String aggregation, Buckets.Window w) {
        return map("source_events", sources, "entity_key", key, "aggregation", aggregation, "window", w.suffix,
                "bucket", w.bucket.equals(Buckets.M5) ? "5m" : "1h", "window_semantics", WINDOW_SEMANTICS,
                "dedupe", "CloudEvent id (ce_id)", "missing", "NaN when the request has no such entity; 0 when nothing was seen");
    }

    private static Def flag(String name, String entity, String description, List<String> links) {
        return new Def(name, entity, "BOOL", description, false, map("source_events", TXN_EVENTS, "links", links,
                "transform", "1 when none of the applicable links was seen before, 0 when one was", "missing",
                "NaN when the request names no such pair"));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
