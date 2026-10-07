package io.meridian.fraud.domain.ml;

/**
 * An inference-ready model over a dense feature vector (indexes follow the artefact's feature list). Implementations
 * are immutable, thread-safe, allocation-light and deterministic: the same input always gives the same output bits.
 */
public interface CompiledModel {
    /** Number of input features. */
    int size();

    /**
     * Scores one vector. {@code Double.NaN} marks a missing value (trees follow the node's missing branch, logistic
     * regression treats it as contributing nothing).
     */
    Prediction predict(double[] x);

    /**
     * Raw output of the model: {@code margin = bias + sum(contributions)} (exactly, up to floating-point rounding),
     * {@code probability = sigmoid(margin)}.
     *
     * @param bias          expected margin before any feature is known (intercept, or the trees' root expectations
     *                      plus base score)
     * @param contributions per-feature contribution to the margin, aligned with the feature list
     */
    record Prediction(double margin, double probability, double bias, double[] contributions) {
        public int score() {
            long s = Math.round(probability * 1000.0);
            return (int) Math.max(0, Math.min(1000, s));
        }
    }

    static double sigmoid(double margin) {
        if (margin >= 0) {
            return 1.0 / (1.0 + Math.exp(-margin));
        }
        double e = Math.exp(margin);
        return e / (1.0 + e);
    }
}
