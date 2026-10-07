package io.meridian.fraud.domain.ml;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A fraud model artefact ({@code format: meridian-fraud-model/1}) as stored in ai-platform-service, parsed and
 * compiled for in-process scoring.
 *
 * <pre>{@code
 * {
 *   "format": "meridian-fraud-model/1",
 *   "algorithm": "LOGISTIC_REGRESSION" | "TREE_ENSEMBLE",
 *   "features": ["customer_txn_count_1h", ...],              // input order; must be catalogued features
 *   "missing_values": {"feature": 0.0},                       // optional imputation (else NaN = missing)
 *   "logistic": {"intercept": -3.2, "coefficients": [..] | {"feature": w},
 *                "standardize": {"mean": [..], "std": [..]}},  // optional
 *   "trees": [ <XGBoost or LightGBM JSON tree>, ... ], "base_score": 0.0, "decision_type": "<" | "<=",
 *   "reason_codes": {"feature": {"code": "HIGH_VELOCITY", "description": "..."}},
 *   "reason_min_contribution": 0.25,                          // margin units
 *   "thresholds": {"default": {"step_up": 450, "review": 650, "block": 850}, "channels": {"ECOMMERCE": {...}}},
 *   "training_distributions": {"score": {"edges": [..], "counts": [..]}, "feature": {...}},  // PSI reference
 *   "metadata": {...}
 * }
 * }</pre>
 */
public record ModelArtifact(String algorithm, List<String> features, double[] imputation, CompiledModel model,
                            Map<String, ReasonCode> reasonCodes, double reasonMinContribution, Thresholds defaultThresholds,
                            Map<String, Thresholds> channelThresholds, Map<String, Distribution> distributions,
                            JsonNode metadata) {
    public static final String FORMAT = "meridian-fraud-model/1";
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_FEATURES = 512;

    public record ReasonCode(String code, String description) {}

    /** Score thresholds 0-1000: step_up <= review <= block. */
    public record Thresholds(int stepUp, int review, int block) {}

    /** Reference histogram: {@code edges} (ascending, n-1 inner cut points) and {@code counts} (n bins). */
    public record Distribution(double[] edges, double[] counts) {
        public int bin(double v) {
            int i = Arrays.binarySearch(edges, v);
            // values on an edge belong to the upper bin
            return i >= 0 ? i + 1 : -i - 1;
        }
    }

    /** One feature's share of an explanation. */
    public record Contribution(String feature, double value, double contribution, String reasonCode, String description) {}

    public static String sha256(String content) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Parses and compiles an artefact.
     *
     * @param known feature names the platform can compute (artefacts naming anything else are refused); null = no check
     */
    public static ModelArtifact parse(String content, JsonMapper json, Set<String> known) {
        try {
            return parseChecked(content, json, known);
        } catch (ArtifactException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ArtifactException("invalid_artifact", "artefact cannot be read: " + e.getMessage());
        }
    }

    private static ModelArtifact parseChecked(String content, JsonMapper json, Set<String> known) {
        if (content == null || content.isBlank()) {
            throw new ArtifactException("invalid_artifact", "artefact is empty");
        }
        if (content.length() > MAX_BYTES) {
            throw new ArtifactException("artifact_too_large", "artefact exceeds " + MAX_BYTES + " bytes");
        }
        JsonNode root;
        try {
            root = json.readTree(content);
        } catch (JacksonException e) {
            throw new ArtifactException("invalid_artifact", "artefact is not valid JSON: " + e.getOriginalMessage());
        }
        if (!FORMAT.equals(root.path("format").asString(""))) {
            throw new ArtifactException("unsupported_format", "format must be " + FORMAT);
        }
        List<String> features = new ArrayList<>();
        JsonNode fs = root.get("features");
        if (fs == null || !fs.isArray() || fs.isEmpty() || fs.size() > MAX_FEATURES) {
            throw new ArtifactException("invalid_features", "features must list 1-" + MAX_FEATURES + " names");
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode f : fs) {
            String name = f.asString("");
            if (!name.matches("[a-z][a-z0-9_]{0,127}") || !seen.add(name)) {
                throw new ArtifactException("invalid_features", "feature names must be unique snake_case: " + name);
            }
            if (known != null && !known.contains(name)) {
                throw new ArtifactException("unknown_feature", "feature " + name + " is not computed by the feature store");
            }
            features.add(name);
        }
        double[] imputation = new double[features.size()];
        Arrays.fill(imputation, Double.NaN);
        JsonNode mv = root.path("missing_values");
        for (int i = 0; i < features.size(); i++) {
            if (mv.hasNonNull(features.get(i))) {
                imputation[i] = finite(mv.get(features.get(i)), "missing_values." + features.get(i));
            }
        }
        String algorithm = root.path("algorithm").asString("").toUpperCase(Locale.ROOT);
        CompiledModel model = switch (algorithm) {
            case "LOGISTIC_REGRESSION" -> logistic(root.path("logistic"), features);
            case "TREE_ENSEMBLE" -> TreeEnsemble.compile(features, root.get("trees"),
                    root.has("base_score") ? finite(root.get("base_score"), "base_score") : 0.0,
                    root.path("decision_type").asString("<"));
            default -> throw new ArtifactException("unsupported_algorithm", "algorithm must be LOGISTIC_REGRESSION or TREE_ENSEMBLE");
        };
        Map<String, ReasonCode> reasons = new LinkedHashMap<>();
        for (String f : features) {
            JsonNode r = root.path("reason_codes").path(f);
            String code = r.path("code").asString("ML_" + f.toUpperCase(Locale.ROOT));
            reasons.put(f, new ReasonCode(code, r.path("description").asString(f.replace('_', ' '))));
        }
        double minContribution = root.has("reason_min_contribution")
                ? finite(root.get("reason_min_contribution"), "reason_min_contribution") : 0.25;
        JsonNode th = root.path("thresholds");
        Thresholds def = th.has("default") ? thresholds(th.get("default"), "thresholds.default") : null;
        Map<String, Thresholds> channels = new LinkedHashMap<>();
        th.path("channels").properties().forEach(e ->
                channels.put(e.getKey(), thresholds(e.getValue(), "thresholds.channels." + e.getKey())));
        Map<String, Distribution> dists = new LinkedHashMap<>();
        root.path("training_distributions").properties().forEach(e -> dists.put(e.getKey(), distribution(e.getValue(), e.getKey())));
        return new ModelArtifact(algorithm, List.copyOf(features), imputation, model, Map.copyOf(reasons), minContribution,
                def, Map.copyOf(channels), Map.copyOf(dists), root.path("metadata"));
    }

    /** Dense input vector in the artefact's feature order (imputation applied, absent = NaN). */
    public double[] vector(Map<String, Double> values) {
        double[] x = new double[features.size()];
        for (int i = 0; i < x.length; i++) {
            Double v = values.get(features.get(i));
            x[i] = v == null || v.isNaN() || v.isInfinite() ? imputation[i] : v;
        }
        return x;
    }

    /** Contributions sorted by size (largest risk-increasing first), zero contributions dropped. */
    public List<Contribution> contributions(double[] x, CompiledModel.Prediction p) {
        List<Contribution> out = new ArrayList<>();
        for (int i = 0; i < x.length; i++) {
            double c = p.contributions()[i];
            if (c != 0.0) {
                ReasonCode r = reasonCodes.get(features.get(i));
                out.add(new Contribution(features.get(i), x[i], c, r.code(), r.description()));
            }
        }
        out.sort(Comparator.comparingDouble(Contribution::contribution).reversed().thenComparing(Contribution::feature));
        return out;
    }

    public Thresholds thresholds(String channel) {
        Thresholds t = channel == null ? null : channelThresholds.get(channel);
        return t != null ? t : defaultThresholds;
    }

    private static LogisticModel logistic(JsonNode l, List<String> features) {
        if (!l.isObject()) {
            throw new ArtifactException("invalid_logistic", "logistic section is required");
        }
        double intercept = finite(l.get("intercept"), "logistic.intercept");
        double[] w = vector(l.get("coefficients"), features, "logistic.coefficients", 0.0);
        double[] mean = null;
        double[] std = null;
        if (l.has("standardize")) {
            mean = vector(l.path("standardize").get("mean"), features, "logistic.standardize.mean", 0.0);
            std = vector(l.path("standardize").get("std"), features, "logistic.standardize.std", 1.0);
        }
        return new LogisticModel(intercept, w, mean, std);
    }

    /** Per-feature values given as an array (feature order) or an object by name ({@code absent} for unnamed ones). */
    private static double[] vector(JsonNode n, List<String> features, String path, double absent) {
        double[] out = new double[features.size()];
        if (n != null && n.isArray()) {
            if (n.size() != features.size()) {
                throw new ArtifactException("invalid_logistic", path + " must have one value per feature");
            }
            for (int i = 0; i < out.length; i++) {
                out[i] = finite(n.get(i), path + "[" + i + "]");
            }
            return out;
        }
        if (n != null && n.isObject()) {
            for (String k : n.propertyNames()) {
                if (!features.contains(k)) {
                    throw new ArtifactException("unknown_feature", path + " names " + k + ", which is not in features");
                }
            }
            for (int i = 0; i < out.length; i++) {
                JsonNode v = n.get(features.get(i));
                out[i] = v == null ? absent : finite(v, path + "." + features.get(i));
            }
            return out;
        }
        throw new ArtifactException("invalid_logistic", path + " must be an array or an object");
    }

    private static Thresholds thresholds(JsonNode n, String path) {
        int s = n.path("step_up").asInt(-1);
        int r = n.path("review").asInt(-1);
        int b = n.path("block").asInt(-1);
        if (s < 0 || r < s || b < r || b > 1000) {
            throw new ArtifactException("invalid_thresholds", path + " needs 0 <= step_up <= review <= block <= 1000");
        }
        return new Thresholds(s, r, b);
    }

    private static Distribution distribution(JsonNode n, String name) {
        JsonNode e = n.get("edges");
        JsonNode c = n.get("counts");
        if (e == null || c == null || !e.isArray() || !c.isArray() || c.size() != e.size() + 1 || c.size() < 2
                || c.size() > 100) {
            throw new ArtifactException("invalid_distribution", "training_distributions." + name
                    + " needs edges (n-1) and counts (n, 2..100)");
        }
        double[] edges = new double[e.size()];
        for (int i = 0; i < edges.length; i++) {
            edges[i] = finite(e.get(i), "edges");
            if (i > 0 && edges[i] <= edges[i - 1]) {
                throw new ArtifactException("invalid_distribution", "training_distributions." + name + " edges must ascend");
            }
        }
        double[] counts = new double[c.size()];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = finite(c.get(i), "counts");
            if (counts[i] < 0) {
                throw new ArtifactException("invalid_distribution", "counts must not be negative");
            }
        }
        return new Distribution(edges, counts);
    }

    private static double finite(JsonNode n, String path) {
        if (n == null || !n.isNumber() || !Double.isFinite(n.asDouble())) {
            throw new ArtifactException("invalid_number", path + " must be a finite number");
        }
        return n.asDouble();
    }
}
