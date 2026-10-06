package io.meridian.aiplatform.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Population Stability Index and monitoring thresholds.
 *
 * <p>PSI = sum over bins of (actual% - expected%) * ln(actual% / expected%), where bins are given as counts or
 * proportions (each side is normalised). Empty bins are floored at 0.0001 so the logarithm stays finite. Conventional
 * reading: below 0.10 stable, 0.10-0.25 moderate shift, 0.25 and above significant shift.
 */
public final class Drift {
    public static final double EPSILON = 1e-4;
    public static final int MAX_BINS = 100;
    /** Metrics where a higher value is worse; for all others (AUC, KS, precision, recall) lower is worse. */
    public static final Set<String> HIGHER_IS_WORSE = Set.of("PSI", "CSI", "LATENCY_P99", "ERROR_RATE");
    public static final Set<String> METRICS = Set.of("PSI", "CSI", "AUC", "KS", "PRECISION", "RECALL", "LATENCY_P99",
            "ERROR_RATE", "GINI", "ACCURACY");
    /** Platform defaults when neither the metric nor the deployment carries a threshold. */
    public static final Map<String, BigDecimal> DEFAULT_THRESHOLDS = Map.of(
            "PSI", new BigDecimal("0.25"), "CSI", new BigDecimal("0.25"), "ERROR_RATE", new BigDecimal("0.05"));

    private Drift() {}

    public static BigDecimal psi(List<BigDecimal> expected, List<BigDecimal> actual) {
        if (expected == null || actual == null || expected.size() != actual.size()) {
            throw new IllegalArgumentException("expected and actual must have the same number of bins");
        }
        if (expected.size() < 2 || expected.size() > MAX_BINS) {
            throw new IllegalArgumentException("between 2 and " + MAX_BINS + " bins are required");
        }
        double[] e = normalise(expected, "expected");
        double[] a = normalise(actual, "actual");
        double psi = 0;
        for (int i = 0; i < e.length; i++) {
            double ei = Math.max(e[i], EPSILON);
            double ai = Math.max(a[i], EPSILON);
            psi += (ai - ei) * Math.log(ai / ei);
        }
        return BigDecimal.valueOf(psi).setScale(6, RoundingMode.HALF_EVEN);
    }

    private static double[] normalise(List<BigDecimal> bins, String side) {
        double total = 0;
        double[] out = new double[bins.size()];
        for (int i = 0; i < bins.size(); i++) {
            BigDecimal b = bins.get(i);
            if (b == null || b.signum() < 0) {
                throw new IllegalArgumentException(side + " bins must be non-negative numbers");
            }
            out[i] = b.doubleValue();
            total += out[i];
        }
        if (total <= 0) {
            throw new IllegalArgumentException(side + " bins must not all be zero");
        }
        for (int i = 0; i < out.length; i++) {
            out[i] /= total;
        }
        return out;
    }

    public static String stability(BigDecimal psi) {
        if (psi.compareTo(new BigDecimal("0.10")) < 0) {
            return "STABLE";
        }
        return psi.compareTo(new BigDecimal("0.25")) < 0 ? "MODERATE" : "SIGNIFICANT";
    }

    /** Whether a value breaches its threshold, by the metric's direction; no threshold never breaches. */
    public static boolean breached(String metric, BigDecimal value, BigDecimal threshold) {
        if (threshold == null || value == null) {
            return false;
        }
        return HIGHER_IS_WORSE.contains(metric) ? value.compareTo(threshold) > 0 : value.compareTo(threshold) < 0;
    }
}
