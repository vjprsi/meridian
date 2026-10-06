package io.meridian.fraud.domain.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A pluggable fraud scoring model. Implementations must be deterministic, thread-safe and fast (no I/O on the
 * scoring path): remote models belong behind an adapter with its own timeout and are evaluated in shadow first.
 */
public interface FraudModel {
    /** Stable id referenced by fraud_decision_policy.model_id / shadow_model_id. */
    UUID id();

    String name();

    String version();

    /** Scores engineered features; returns 0-1000 plus per-feature contributions for explanations. */
    Output score(Map<String, Double> features);

    /** Names of the engineered features the model reads (in a stable order). */
    List<String> featureNames();

    record Contribution(String feature, double value, double weight, double contribution, String reasonCode,
                        String description) {}

    record Output(int score, double probability, double logit, List<Contribution> contributions) {}
}
