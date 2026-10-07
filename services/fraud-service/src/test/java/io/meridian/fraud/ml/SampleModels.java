package io.meridian.fraud.ml;

import io.meridian.fraud.domain.ml.CompiledModel;
import io.meridian.fraud.domain.ml.ModelArtifact;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The shipped SYNTHETIC sample models ({@code src/main/resources/ml/sample/}): how they are produced from
 * {@link SyntheticFraudData} and how tests load them. {@code SampleModelTest} regenerates them and fails when the
 * committed files differ (run it with {@code -DregenerateSampleModels=true} to rewrite them).
 */
public final class SampleModels {
    public static final String GBDT = "ml/sample/sample-fraud-gbdt.json";
    public static final String LR = "ml/sample/sample-fraud-lr.json";
    static final int TRAIN = 12_000;
    static final int HOLDOUT = 4_000;

    private SampleModels() {}

    public static String load(String resource) {
        try (InputStream in = SampleModels.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " not found");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Builds both artefacts (pretty JSON, LF line endings), keyed by resource path. */
    public static Map<String, String> build(JsonMapper json) {
        List<SyntheticFraudData.Row> train = SyntheticFraudData.generate(TRAIN, SyntheticFraudData.SEED);
        List<SyntheticFraudData.Row> holdout = SyntheticFraudData.generate(HOLDOUT, SyntheticFraudData.SEED + 1);
        ObjectNode gbdt = SampleModelTrainer.trees(train, 40, 3, 0.3, json);
        ObjectNode lr = SampleModelTrainer.logistic(train, holdout, json);
        finish(gbdt, holdout, json);
        finish(lr, holdout, json);
        return Map.of(GBDT, pretty(gbdt, json), LR, pretty(lr, json));
    }

    /** Adds holdout metrics and reference distributions (score + key features) for PSI monitoring. */
    static void finish(ObjectNode a, List<SyntheticFraudData.Row> holdout, JsonMapper json) {
        ModelArtifact m = ModelArtifact.parse(json.writeValueAsString(a), json, null);
        int[] scores = new int[holdout.size()];
        double[] probs = new double[holdout.size()];
        for (int i = 0; i < holdout.size(); i++) {
            CompiledModel.Prediction p = m.model().predict(m.vector(asMap(holdout.get(i).x())));
            scores[i] = p.score();
            probs[i] = p.probability();
        }
        ObjectNode meta = (ObjectNode) a.get("metadata");
        meta.put("training_rows", TRAIN);
        meta.put("holdout_rows", HOLDOUT);
        meta.put("holdout_auc", SampleModelTrainer.round6(auc(probs, holdout)));
        ObjectNode dist = a.putObject("training_distributions");
        double[] scoreEdges = {100, 200, 300, 400, 500, 600, 700, 800, 900};
        double[] scoreValues = Arrays.stream(scores).asDoubleStream().toArray();
        put(dist, "score", scoreEdges, scoreValues);
        for (String f : List.of("customer_txn_count_1h", "amount_zscore", "new_payee", "merchant_risk", "txn_amount")) {
            int j = SyntheticFraudData.FEATURES.indexOf(f);
            double[] v = holdout.stream().mapToDouble(r -> r.x()[j]).filter(x -> !Double.isNaN(x)).sorted().toArray();
            TreeSet<Double> edges = new TreeSet<>();
            for (int k = 1; k < 10; k++) {
                edges.add(SampleModelTrainer.round6(v[k * (v.length - 1) / 10] + 1e-6));
            }
            put(dist, f, edges.stream().mapToDouble(Double::doubleValue).toArray(), v);
        }
    }

    private static void put(ObjectNode dist, String name, double[] edges, double[] values) {
        ModelArtifact.Distribution d = new ModelArtifact.Distribution(edges, new double[edges.length + 1]);
        double[] counts = new double[edges.length + 1];
        for (double v : values) {
            counts[d.bin(v)]++;
        }
        ObjectNode n = dist.putObject(name);
        ArrayNode e = n.putArray("edges");
        for (double x : edges) {
            e.add(x);
        }
        ArrayNode c = n.putArray("counts");
        for (double x : counts) {
            c.add((long) x);
        }
    }

    public static Map<String, Double> asMap(double[] x) {
        Map<String, Double> m = new java.util.HashMap<>();
        for (int i = 0; i < x.length; i++) {
            m.put(SyntheticFraudData.FEATURES.get(i), x[i]);
        }
        return m;
    }

    static double auc(double[] p, List<SyntheticFraudData.Row> rows) {
        List<double[]> l = new ArrayList<>();
        for (int i = 0; i < p.length; i++) {
            l.add(new double[] {p[i], rows.get(i).label()});
        }
        l.sort((a, b) -> Double.compare(a[0], b[0]));
        long pos = rows.stream().filter(r -> r.label() == 1).count();
        long neg = rows.size() - pos;
        double rank = 0;
        int i = 0;
        while (i < l.size()) {
            int j = i;
            while (j + 1 < l.size() && l.get(j + 1)[0] == l.get(i)[0]) {
                j++;
            }
            for (int k = i; k <= j; k++) {
                if (l.get(k)[1] == 1) {
                    rank += (i + j) / 2.0 + 1;
                }
            }
            i = j + 1;
        }
        return (rank - pos * (pos + 1) / 2.0) / (pos * (double) neg);
    }

    static String pretty(ObjectNode a, JsonMapper json) {
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(a).replace("\r\n", "\n") + "\n";
    }
}
