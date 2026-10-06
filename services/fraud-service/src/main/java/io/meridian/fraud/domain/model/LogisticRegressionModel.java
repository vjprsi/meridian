package io.meridian.fraud.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic logistic-regression model over engineered features (each scaled to roughly 0..1):
 * {@code p = 1 / (1 + e^-(b + sum(w_i * x_i)))}, score = round(1000 * p). Contributions {@code w_i * x_i} explain
 * the score; the largest positive ones become reason codes.
 */
public final class LogisticRegressionModel implements FraudModel {
    /** One weighted input: feature name, weight, reason code and plain-language description. */
    public record Weight(String feature, double weight, String reasonCode, String description) {}

    private final UUID id;
    private final String name;
    private final String version;
    private final double intercept;
    private final List<Weight> weights;

    public LogisticRegressionModel(String name, String version, double intercept, List<Weight> weights) {
        this.id = UUID.nameUUIDFromBytes(("fraud-model:" + name + ":" + version).getBytes(StandardCharsets.UTF_8));
        this.name = name;
        this.version = version;
        this.intercept = intercept;
        this.weights = List.copyOf(weights);
    }

    /** Champion model shipped with the service (weights fitted offline on labelled outcomes). */
    public static LogisticRegressionModel champion() {
        return new LogisticRegressionModel("meridian-lr", "1.0.0", -4.2, List.of(
                new Weight("amount_log", 0.45, "HIGH_AMOUNT", "Transaction amount is high"),
                new Weight("amount_to_avg_ratio", 2.2, "AMOUNT_ABOVE_CUSTOMER_AVERAGE",
                        "Amount is far above the customer's 30-day average"),
                new Weight("velocity_1h", 1.8, "HIGH_VELOCITY_1H", "Many transactions in the last hour"),
                new Weight("velocity_24h", 0.9, "HIGH_VELOCITY_24H", "Many transactions in the last 24 hours"),
                new Weight("new_device", 1.1, "NEW_DEVICE", "First time this device is seen"),
                new Weight("device_emulator", 1.9, "EMULATOR", "Device is an emulator"),
                new Weight("device_rooted", 0.9, "ROOTED_DEVICE", "Device is rooted or jailbroken"),
                new Weight("attestation_failed", 1.2, "ATTESTATION_FAILED", "App attestation failed"),
                new Weight("remote_access", 2.1, "REMOTE_ACCESS_TOOL", "Remote-access or screen-sharing tool active"),
                new Weight("shared_device", 1.3, "SHARED_DEVICE", "Device used by several customers"),
                new Weight("on_active_call", 1.6, "ON_ACTIVE_CALL", "Customer is on a phone call (scam indicator)"),
                new Weight("cross_border", 0.6, "CROSS_BORDER", "Counterparty country differs from the IP country"),
                new Weight("unusual_hour", 0.35, "UNUSUAL_HOUR", "Transaction at an unusual hour"),
                new Weight("multiple_devices_24h", 0.7, "MULTIPLE_DEVICES", "Several devices used in 24 hours"),
                new Weight("watch_list_hit", 1.2, "WATCH_LIST_HIT", "A value is on a watch list"),
                new Weight("device_risk", 1.0, "RISKY_DEVICE", "Device reputation is poor"),
                new Weight("trusted_device", -1.2, "TRUSTED_DEVICE", "Device was trusted after strong authentication")));
    }

    /** Challenger model evaluated in shadow (more weight on behavioural and device signals). */
    public static LogisticRegressionModel challenger() {
        return new LogisticRegressionModel("meridian-lr-challenger", "1.1.0", -4.4, List.of(
                new Weight("amount_log", 0.35, "HIGH_AMOUNT", "Transaction amount is high"),
                new Weight("amount_to_avg_ratio", 2.4, "AMOUNT_ABOVE_CUSTOMER_AVERAGE",
                        "Amount is far above the customer's 30-day average"),
                new Weight("velocity_1h", 2.0, "HIGH_VELOCITY_1H", "Many transactions in the last hour"),
                new Weight("velocity_24h", 0.8, "HIGH_VELOCITY_24H", "Many transactions in the last 24 hours"),
                new Weight("new_device", 1.4, "NEW_DEVICE", "First time this device is seen"),
                new Weight("device_emulator", 2.2, "EMULATOR", "Device is an emulator"),
                new Weight("device_rooted", 1.0, "ROOTED_DEVICE", "Device is rooted or jailbroken"),
                new Weight("attestation_failed", 1.3, "ATTESTATION_FAILED", "App attestation failed"),
                new Weight("remote_access", 2.6, "REMOTE_ACCESS_TOOL", "Remote-access or screen-sharing tool active"),
                new Weight("shared_device", 1.5, "SHARED_DEVICE", "Device used by several customers"),
                new Weight("on_active_call", 2.0, "ON_ACTIVE_CALL", "Customer is on a phone call (scam indicator)"),
                new Weight("cross_border", 0.5, "CROSS_BORDER", "Counterparty country differs from the IP country"),
                new Weight("unusual_hour", 0.3, "UNUSUAL_HOUR", "Transaction at an unusual hour"),
                new Weight("multiple_devices_24h", 0.9, "MULTIPLE_DEVICES", "Several devices used in 24 hours"),
                new Weight("watch_list_hit", 1.0, "WATCH_LIST_HIT", "A value is on a watch list"),
                new Weight("device_risk", 1.2, "RISKY_DEVICE", "Device reputation is poor"),
                new Weight("trusted_device", -1.0, "TRUSTED_DEVICE", "Device was trusted after strong authentication")));
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public List<String> featureNames() {
        return weights.stream().map(Weight::feature).toList();
    }

    @Override
    public Output score(Map<String, Double> features) {
        double logit = intercept;
        List<Contribution> contributions = new ArrayList<>(weights.size());
        for (Weight w : weights) {
            Double v = features.get(w.feature());
            double x = v == null || v.isNaN() ? 0.0 : clamp(v, -1.0, 1.0);
            double c = w.weight() * x;
            logit += c;
            contributions.add(new Contribution(w.feature(), x, w.weight(), round4(c), w.reasonCode(), w.description()));
        }
        double p = 1.0 / (1.0 + Math.exp(-logit));
        int score = (int) Math.round(p * 1000.0);
        contributions.sort(Comparator.comparingDouble(Contribution::contribution).reversed());
        return new Output(Math.max(0, Math.min(1000, score)), round4(p), round4(logit), List.copyOf(contributions));
    }

    /** The model's weights as a map (for the explanation endpoint). */
    public Map<String, Double> weights() {
        Map<String, Double> m = new LinkedHashMap<>();
        weights.forEach(w -> m.put(w.feature(), w.weight()));
        m.put("intercept", intercept);
        return m;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
