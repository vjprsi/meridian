package io.meridian.fraud.domain.ml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Additive ensemble of binary decision trees (gradient boosting): {@code margin = base_score + sum(leaf_t(x))},
 * {@code p = sigmoid(margin)}. Trees are compiled into flat arrays (no objects on the scoring path).
 *
 * <p>Accepted node structures (one JSON object per tree):
 * <ul>
 *   <li>XGBoost JSON dump ({@code dump_model(..., dump_format="json", with_stats=True)}): internal nodes
 *       {@code {nodeid, split, split_condition, yes, no, missing, cover, children:[...]}}, leaves
 *       {@code {nodeid, leaf, cover}}; {@code x < split_condition} goes to {@code yes}. {@code split} is a feature name
 *       or {@code f<index>}.</li>
 *   <li>LightGBM {@code dump_model()} trees ({@code {tree_structure: ...}} or the structure itself): internal nodes
 *       {@code {split_feature, threshold, decision_type, default_left, left_child, right_child, internal_count}},
 *       leaves {@code {leaf_value, leaf_count}}; {@code x <= threshold} goes left. {@code split_feature} is an index
 *       into the feature list (or a name).</li>
 * </ul>
 * A node may set {@code decision_type} to {@code "<"} or {@code "<="} explicitly. Missing values (NaN) follow the
 * {@code missing} child (XGBoost) or {@code default_left} (LightGBM; default: left).
 *
 * <p>Contributions use the Saabas path attribution: every node carries the expected leaf value below it (cover
 * weighted; equal weights when the dump has no cover statistics); walking from the root to the leaf, each split adds
 * {@code E(child) - E(node)} to its feature. The bias is {@code base_score + sum(E(root_t))}, so
 * {@code bias + sum(contributions) == margin} for every input (telescoping sums).
 */
public final class TreeEnsemble implements CompiledModel {
    static final int MAX_NODES = 1_000_000;
    static final int MAX_DEPTH = 64;

    private final int features;
    private final double baseScore;
    private final int[] roots;
    private final int[] feature;     // -1 = leaf
    private final double[] threshold;
    private final boolean[] lessOrEqual;
    private final int[] left;
    private final int[] right;
    private final int[] missing;
    private final double[] value;    // leaf value, or expected value of the subtree for internal nodes
    private final double bias;

    private TreeEnsemble(int features, double baseScore, int[] roots, int[] feature, double[] threshold,
                         boolean[] lessOrEqual, int[] left, int[] right, int[] missing, double[] value) {
        this.features = features;
        this.baseScore = baseScore;
        this.roots = roots;
        this.feature = feature;
        this.threshold = threshold;
        this.lessOrEqual = lessOrEqual;
        this.left = left;
        this.right = right;
        this.missing = missing;
        this.value = value;
        double b = baseScore;
        for (int r : roots) {
            b += value[r];
        }
        this.bias = b;
    }

    public int trees() {
        return roots.length;
    }

    public int nodes() {
        return feature.length;
    }

    @Override
    public int size() {
        return features;
    }

    @Override
    public Prediction predict(double[] x) {
        double[] c = new double[features];
        double margin = baseScore;
        for (int root : roots) {
            int n = root;
            while (feature[n] >= 0) {
                double v = x[feature[n]];
                int next;
                if (Double.isNaN(v)) {
                    next = missing[n];
                } else if (lessOrEqual[n] ? v <= threshold[n] : v < threshold[n]) {
                    next = left[n];
                } else {
                    next = right[n];
                }
                c[feature[n]] += value[next] - value[n];
                n = next;
            }
            margin += value[n];
        }
        return new Prediction(margin, CompiledModel.sigmoid(margin), bias, c);
    }

    /** Compiles trees given as JSON (see class comment) for the given feature list. */
    public static TreeEnsemble compile(List<String> featureNames, JsonNode trees, double baseScore, String defaultDecision) {
        if (trees == null || !trees.isArray() || trees.isEmpty()) {
            throw new ArtifactException("invalid_trees", "trees must be a non-empty array");
        }
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < featureNames.size(); i++) {
            index.put(featureNames.get(i), i);
        }
        boolean defaultLe = "<=".equals(defaultDecision);
        Builder b = new Builder(index, featureNames.size(), defaultLe);
        int[] roots = new int[trees.size()];
        for (int t = 0; t < trees.size(); t++) {
            JsonNode tree = trees.get(t);
            if (tree.has("tree_structure")) {
                tree = tree.get("tree_structure");
            }
            roots[t] = b.add(tree, 0, "trees[" + t + "]");
        }
        int n = b.feature.size();
        int[] feature = new int[n];
        double[] threshold = new double[n];
        boolean[] le = new boolean[n];
        int[] left = new int[n];
        int[] right = new int[n];
        int[] missing = new int[n];
        double[] value = new double[n];
        double[] cover = new double[n];
        for (int i = 0; i < n; i++) {
            feature[i] = b.feature.get(i);
            threshold[i] = b.threshold.get(i);
            le[i] = b.le.get(i);
            left[i] = b.left.get(i);
            right[i] = b.right.get(i);
            missing[i] = b.missing.get(i);
            value[i] = b.value.get(i);
            cover[i] = b.cover.get(i);
        }
        // expected values bottom-up: children are always added after their parent, so walk backwards
        for (int i = n - 1; i >= 0; i--) {
            if (feature[i] >= 0) {
                double cl = cover[left[i]];
                double cr = cover[right[i]];
                if (!(cl > 0) || !(cr > 0)) {
                    cl = 1;
                    cr = 1;
                }
                value[i] = (cl * value[left[i]] + cr * value[right[i]]) / (cl + cr);
                if (!(cover[i] > 0)) {
                    cover[i] = cl + cr;
                }
            }
        }
        return new TreeEnsemble(featureNames.size(), baseScore, roots, feature, threshold, le, left, right, missing, value);
    }

    private static final class Builder {
        final Map<String, Integer> index;
        final int featureCount;
        final boolean defaultLe;
        final List<Integer> feature = new ArrayList<>();
        final List<Double> threshold = new ArrayList<>();
        final List<Boolean> le = new ArrayList<>();
        final List<Integer> left = new ArrayList<>();
        final List<Integer> right = new ArrayList<>();
        final List<Integer> missing = new ArrayList<>();
        final List<Double> value = new ArrayList<>();
        final List<Double> cover = new ArrayList<>();

        Builder(Map<String, Integer> index, int featureCount, boolean defaultLe) {
            this.index = index;
            this.featureCount = featureCount;
            this.defaultLe = defaultLe;
        }

        int add(JsonNode node, int depth, String path) {
            if (node == null || !node.isObject()) {
                throw new ArtifactException("invalid_tree", path + " is not a node object");
            }
            if (depth > MAX_DEPTH) {
                throw new ArtifactException("invalid_tree", path + " is deeper than " + MAX_DEPTH);
            }
            if (feature.size() >= MAX_NODES) {
                throw new ArtifactException("invalid_tree", "more than " + MAX_NODES + " nodes");
            }
            int id = feature.size();
            feature.add(-1);
            threshold.add(0.0);
            le.add(false);
            left.add(-1);
            right.add(-1);
            missing.add(-1);
            value.add(0.0);
            cover.add(number(node, "cover", "leaf_count", "internal_count"));
            if (node.has("leaf") || node.has("leaf_value")) {
                JsonNode leaf = node.has("leaf") ? node.get("leaf") : node.get("leaf_value");
                double v = leaf != null && leaf.isNumber() ? leaf.asDouble() : Double.NaN;
                if (!Double.isFinite(v)) {
                    throw new ArtifactException("invalid_tree", path + ": leaf value is not finite");
                }
                value.set(id, v);
                return id;
            }
            if (node.has("children") || node.has("split")) {
                return xgboost(node, id, depth, path);
            }
            if (node.has("split_feature")) {
                return lightgbm(node, id, depth, path);
            }
            throw new ArtifactException("invalid_tree", path + ": neither a leaf nor a split node");
        }

        private int xgboost(JsonNode node, int id, int depth, String path) {
            int f = featureIndex(node.get("split"), path);
            double t = threshold(node.get("split_condition"), path);
            JsonNode children = node.get("children");
            if (children == null || !children.isArray() || children.size() != 2) {
                throw new ArtifactException("invalid_tree", path + ": a split needs two children");
            }
            Map<Integer, JsonNode> byId = new HashMap<>();
            for (JsonNode ch : children) {
                byId.put(ch.path("nodeid").asInt(Integer.MIN_VALUE), ch);
            }
            JsonNode yes = byId.get(node.path("yes").asInt(Integer.MIN_VALUE));
            JsonNode no = byId.get(node.path("no").asInt(Integer.MIN_VALUE));
            if (yes == null || no == null || yes == no) {
                throw new ArtifactException("invalid_tree", path + ": yes / no must name the two children");
            }
            boolean missingYes = !node.has("missing") || node.get("missing").asInt() == node.get("yes").asInt();
            boolean decisionLe = decision(node.path("decision_type").asString(null), path);
            int l = add(yes, depth + 1, path + ".yes");
            int r = add(no, depth + 1, path + ".no");
            set(id, f, t, decisionLe, l, r, missingYes ? l : r);
            return id;
        }

        private int lightgbm(JsonNode node, int id, int depth, String path) {
            int f = featureIndex(node.get("split_feature"), path);
            double t = threshold(node.get("threshold"), path);
            String dt = node.path("decision_type").asString("<=");
            boolean decisionLe = !"<".equals(dt);
            if (!"<".equals(dt) && !"<=".equals(dt)) {
                throw new ArtifactException("unsupported_split", path + ": decision_type " + dt + " is not supported");
            }
            boolean defaultLeft = node.path("default_left").asBoolean(true);
            int l = add(node.get("left_child"), depth + 1, path + ".left_child");
            int r = add(node.get("right_child"), depth + 1, path + ".right_child");
            set(id, f, t, decisionLe, l, r, defaultLeft ? l : r);
            return id;
        }

        private boolean decision(String dt, String path) {
            if (dt == null) {
                return defaultLe;
            }
            if ("<".equals(dt)) {
                return false;
            }
            if ("<=".equals(dt)) {
                return true;
            }
            throw new ArtifactException("unsupported_split", path + ": decision_type " + dt + " is not supported");
        }

        private void set(int id, int f, double t, boolean decisionLe, int l, int r, int m) {
            feature.set(id, f);
            threshold.set(id, t);
            le.set(id, decisionLe);
            left.set(id, l);
            right.set(id, r);
            missing.set(id, m);
        }

        private int featureIndex(JsonNode n, String path) {
            if (n == null || n.isNull()) {
                throw new ArtifactException("invalid_tree", path + ": split feature missing");
            }
            if (n.isIntegralNumber()) {
                int i = n.asInt();
                if (i < 0 || i >= featureCount) {
                    throw new ArtifactException("unknown_feature", path + ": feature index " + i + " out of range");
                }
                return i;
            }
            String name = n.asString();
            Integer i = index.get(name);
            if (i == null && name.matches("f\\d+")) {
                int k = Integer.parseInt(name.substring(1));
                if (k < featureCount) {
                    return k;
                }
            }
            if (i == null) {
                throw new ArtifactException("unknown_feature", path + ": split on unknown feature " + name);
            }
            return i;
        }

        private static double threshold(JsonNode n, String path) {
            if (n == null || !n.isNumber() || !Double.isFinite(n.asDouble())) {
                throw new ArtifactException("invalid_tree", path + ": split threshold must be a finite number");
            }
            return n.asDouble();
        }

        private static double number(JsonNode n, String... names) {
            for (String k : names) {
                if (n.hasNonNull(k) && n.get(k).isNumber()) {
                    return n.get(k).asDouble();
                }
            }
            return 0.0;
        }
    }
}
