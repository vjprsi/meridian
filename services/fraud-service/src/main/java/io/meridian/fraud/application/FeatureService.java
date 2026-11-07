package io.meridian.fraud.application;

import io.meridian.common.time.Clocks;
import io.meridian.fraud.domain.Pseudonyms;
import io.meridian.fraud.domain.features.FeatureEvent;
import io.meridian.fraud.domain.features.StoreFeatures;
import io.meridian.fraud.infrastructure.Db;
import io.meridian.fraud.infrastructure.FeatureStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Real-time features of an evaluation request, read from the feature store (one statement, outside the scoring transaction). */
@Service
public class FeatureService {
    private final FeatureStore store;
    private final Pseudonymizer pseudonyms;

    public FeatureService(FeatureStore store, Pseudonymizer pseudonyms) {
        this.store = store;
        this.pseudonyms = pseudonyms;
    }

    /**
     * Entity keys of an evaluate request (the raw request: identifiers are turned into pseudonyms here and never
     * leave this method in clear); see {@code FeatureCatalog} for how each maps to the event keys.
     */
    public static FeatureStore.Keys keys(JsonNode req, Pseudonyms ps) {
        JsonNode ctx = req.path("context");
        String account = first(Db.str(ctx, "wallet_id"), Db.str(ctx, "debtor_account_id"), Db.str(req, "account_id"));
        String digest = Db.str(ctx, "beneficiary_account_digest");
        if (digest == null || !Pseudonyms.isDigest(digest)) {
            digest = FeatureEvent.Keys.accountDigest(Db.str(ctx, "beneficiary_account"));
        }
        String beneficiary = FeatureEvent.Keys.beneficiary(Db.str(ctx, "payee_id"), digest,
                Db.str(ctx, "beneficiary_bic"), Db.str(ctx, "payee_wallet_id"), Db.str(ctx, "payee_msisdn"), ps);
        String country = first(Db.str(req, "counterparty_country"), Db.str(ctx, "merchant_country"));
        return new FeatureStore.Keys(Db.str(req, "customer_id"), Db.str(req, "card_id"), account,
                blankToNull(Db.str(req, "device_fingerprint")), Db.str(ctx, "merchant_id"), beneficiary,
                country == null ? null : country.trim().toUpperCase(Locale.ROOT), Db.str(req, "currency"));
    }

    /** Entity keys of a request for the bound tenant (pseudonymised). */
    public FeatureStore.Keys keys(JsonNode req) {
        return keys(req, pseudonyms.current());
    }

    public Map<String, Double> compute(JsonNode req) {
        return compute(req, Clocks.now().toInstant());
    }

    public Map<String, Double> compute(JsonNode req, Instant now) {
        BigDecimal amount = Db.dec(req, "amount");
        Map<String, Double> f = new LinkedHashMap<>(StoreFeatures.compute(
                store.lookup(keys(req, pseudonyms.current()), amount == null ? null : amount.doubleValue(), now)));
        // history (z-score) stays in raw units; only the amount-valued features follow the tenant's calibration
        double scale = AmountScale.of(Db.str(req, "currency"));
        if (scale != 1.0) {
            f.replaceAll((k, v) -> v != null && !v.isNaN() && (k.equals("txn_amount") || k.contains("_amount_")) ? v * scale : v);
        }
        return f;
    }

    /** JSON-safe copy (NaN = missing becomes null). */
    public static Map<String, Object> jsonSafe(Map<String, Double> f) {
        Map<String, Object> out = new LinkedHashMap<>();
        f.forEach((k, v) -> out.put(k, v == null || v.isNaN() || v.isInfinite() ? null : round6(v)));
        return out;
    }

    static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }

    private static String first(String... vs) {
        for (String v : vs) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
