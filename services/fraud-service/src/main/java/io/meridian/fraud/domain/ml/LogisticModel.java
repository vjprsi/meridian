package io.meridian.fraud.domain.ml;

/**
 * Logistic regression: {@code z_i = (x_i - mean_i) / std_i} (when standardisation is given, else {@code z_i = x_i}),
 * {@code margin = intercept + sum(w_i * z_i)}, {@code p = sigmoid(margin)}. The contribution of feature i is
 * {@code w_i * z_i} (coefficient times value), the bias is the intercept, so contributions sum to
 * {@code margin - intercept}. A missing value (NaN) contributes 0.
 */
public final class LogisticModel implements CompiledModel {
    private final double intercept;
    private final double[] weights;
    private final double[] mean;
    private final double[] invStd;

    public LogisticModel(double intercept, double[] weights, double[] mean, double[] std) {
        this.intercept = intercept;
        this.weights = weights.clone();
        int n = weights.length;
        this.mean = new double[n];
        this.invStd = new double[n];
        for (int i = 0; i < n; i++) {
            this.mean[i] = mean == null ? 0.0 : mean[i];
            double s = std == null ? 1.0 : std[i];
            if (!(s > 0) || Double.isInfinite(s)) {
                throw new ArtifactException("invalid_standardisation", "std must be positive and finite");
            }
            this.invStd[i] = 1.0 / s;
        }
    }

    @Override
    public int size() {
        return weights.length;
    }

    @Override
    public Prediction predict(double[] x) {
        double[] c = new double[weights.length];
        double margin = intercept;
        for (int i = 0; i < weights.length; i++) {
            double v = x[i];
            if (Double.isNaN(v)) {
                continue;
            }
            double ci = weights[i] * ((v - mean[i]) * invStd[i]);
            c[i] = ci;
            margin += ci;
        }
        return new Prediction(margin, CompiledModel.sigmoid(margin), intercept, c);
    }
}
