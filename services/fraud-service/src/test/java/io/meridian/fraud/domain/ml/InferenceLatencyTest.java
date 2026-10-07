package io.meridian.fraud.domain.ml;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.fraud.ml.SampleModels;
import io.meridian.fraud.ml.SyntheticFraudData;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Micro-benchmark of in-process inference (vector building, prediction, contributions and reason ordering) on the
 * shipped sample models plus a larger 300-tree ensemble. Target: p99 below 2 ms per score. The assertion is relaxed
 * to 20 ms when {@code CI} is set (shared runners), the measured numbers are printed either way.
 */
class InferenceLatencyTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void p99IsBelowTwoMilliseconds() {
        double limitMs = System.getenv("CI") != null ? 20.0 : 2.0;
        List<Map<String, Double>> inputs = SyntheticFraudData.generate(2_000, 7).stream()
                .map(r -> SampleModels.asMap(r.x())).toList();
        ModelArtifact gbdt = ModelArtifact.parse(SampleModels.load(SampleModels.GBDT), JSON, null);
        ModelArtifact lr = ModelArtifact.parse(SampleModels.load(SampleModels.LR), JSON, null);
        ModelArtifact big = bigEnsemble(gbdt);
        for (ModelArtifact a : List.of(gbdt, lr, big)) {
            long[] t = measure(a, inputs, 20_000, 50_000);
            double p50 = t[t.length / 2] / 1e6;
            double p99 = t[(int) (t.length * 0.99)] / 1e6;
            double max = t[t.length - 1] / 1e6;
            System.out.printf("inference %s (%d trees): p50 %.4f ms, p99 %.4f ms, max %.3f ms%n", a.algorithm(),
                    a.model() instanceof TreeEnsemble te ? te.trees() : 0, p50, p99, max);
            assertThat(p99).as("p99 of %s", a.algorithm()).isLessThan(limitMs);
        }
        // the full deciding path (champion + two observers) stays within the budget as well
        ModelSet set = new ModelSet(UUID.randomUUID(), "FRAUD", List.of(
                new ModelSet.Deployment(UUID.randomUUID(), UUID.randomUUID(), "1", ModelSet.Role.CHAMPION, 100, "", gbdt, null, null),
                new ModelSet.Deployment(UUID.randomUUID(), UUID.randomUUID(), "2", ModelSet.Role.CHALLENGER, 10, "", big, null, null),
                new ModelSet.Deployment(UUID.randomUUID(), UUID.randomUUID(), "3", ModelSet.Role.SHADOW, 0, "", lr, null, null)));
        long[] t = new long[20_000];
        for (int i = 0; i < 40_000; i++) {
            long t0 = System.nanoTime();
            MlExecution.Result r = MlExecution.execute(set, UUID.randomUUID(), inputs.get(i % inputs.size()), 50_000_000L, System::nanoTime);
            long dt = System.nanoTime() - t0;
            assertThat(r.deciding()).isNotNull();
            if (i >= 20_000) {
                t[i - 20_000] = dt;
            }
        }
        Arrays.sort(t);
        double p99 = t[(int) (t.length * 0.99)] / 1e6;
        System.out.printf("champion + challenger + shadow: p50 %.4f ms, p99 %.4f ms%n", t[t.length / 2] / 1e6, p99);
        assertThat(p99).isLessThan(limitMs);
    }

    private static long[] measure(ModelArtifact a, List<Map<String, Double>> inputs, int warmup, int runs) {
        double sink = 0;
        for (int i = 0; i < warmup; i++) {
            sink += score(a, inputs.get(i % inputs.size()));
        }
        long[] t = new long[runs];
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            sink += score(a, inputs.get(i % inputs.size()));
            t[i] = System.nanoTime() - t0;
        }
        assertThat(sink).isNotNaN();
        Arrays.sort(t);
        return t;
    }

    private static double score(ModelArtifact a, Map<String, Double> in) {
        double[] x = a.vector(in);
        CompiledModel.Prediction p = a.model().predict(x);
        return p.probability() + a.contributions(x, p).size();
    }

    /** 300 trees (the sample's 40 depth-3 trees repeated) as a heavier load. */
    private static ModelArtifact bigEnsemble(ModelArtifact sample) {
        var root = JSON.readTree(SampleModels.load(SampleModels.GBDT));
        var trees = (tools.jackson.databind.node.ArrayNode) root.get("trees");
        var copy = trees.deepCopy();
        while (trees.size() < 300) {
            copy.forEach(n -> trees.add(n.deepCopy()));
        }
        while (trees.size() > 300) {
            trees.remove(trees.size() - 1);
        }
        return ModelArtifact.parse(JSON.writeValueAsString(root), JSON, null);
    }
}
