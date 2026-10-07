package io.meridian.fraud.domain.ml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Logistic regression and tree-ensemble inference against hand-computed outputs, and the contribution identities. */
class InferenceTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final double EPS = 1e-12;

    // ------------------------------------------------------------------ logistic regression

    @Test
    void logisticMatchesHandComputation() {
        // margin = -2 + 0.5*2 + (-1)*1 + 2*0.5 = -1 ; p = 1 / (1 + e) = 0.2689414213699951
        LogisticModel m = new LogisticModel(-2.0, new double[] {0.5, -1.0, 2.0}, null, null);
        CompiledModel.Prediction p = m.predict(new double[] {2.0, 1.0, 0.5});
        assertThat(p.margin()).isCloseTo(-1.0, offset(EPS));
        assertThat(p.probability()).isCloseTo(0.2689414213699951, offset(EPS));
        assertThat(p.score()).isEqualTo(269);
        assertThat(p.bias()).isEqualTo(-2.0);
        assertThat(p.contributions()).containsExactly(1.0, -1.0, 1.0);
    }

    @Test
    void logisticStandardisesAndTreatsMissingAsNoContribution() {
        // z = (x - mean) / std: (10 - 4) / 2 = 3 ; (NaN) -> 0 ; (1 - 1) / 5 = 0
        LogisticModel m = new LogisticModel(0.25, new double[] {0.4, 9.0, -3.0}, new double[] {4, 0, 1}, new double[] {2, 1, 5});
        CompiledModel.Prediction p = m.predict(new double[] {10, Double.NaN, 1});
        assertThat(p.contributions()[0]).isCloseTo(1.2, offset(EPS));
        assertThat(p.contributions()[1]).isZero();
        assertThat(p.contributions()[2]).isZero();
        assertThat(p.margin()).isCloseTo(1.45, offset(EPS));
        assertThat(p.probability()).isCloseTo(1 / (1 + Math.exp(-1.45)), offset(EPS));
    }

    @Test
    void logisticArtefactUsesNamedCoefficientsImputationAndReasonCodes() {
        String artefact = """
                {"format":"meridian-fraud-model/1","algorithm":"LOGISTIC_REGRESSION",
                 "features":["new_payee","amount_zscore","txn_amount"],
                 "missing_values":{"txn_amount":100},
                 "logistic":{"intercept":-3,"coefficients":{"new_payee":1.5,"amount_zscore":0.8,"txn_amount":0.001}},
                 "reason_codes":{"new_payee":{"code":"NEW_PAYEE","description":"First payment to the payee"}}}""";
        ModelArtifact a = ModelArtifact.parse(artefact, JSON, null);
        double[] x = a.vector(Map.of("new_payee", 1.0, "amount_zscore", 2.5));
        assertThat(x).containsExactly(1.0, 2.5, 100.0);
        CompiledModel.Prediction p = a.model().predict(x);
        // -3 + 1.5 + 2.0 + 0.1 = 0.6
        assertThat(p.margin()).isCloseTo(0.6, offset(1e-12));
        List<ModelArtifact.Contribution> c = a.contributions(x, p);
        assertThat(c).extracting(ModelArtifact.Contribution::feature).containsExactly("amount_zscore", "new_payee", "txn_amount");
        assertThat(c.get(1).reasonCode()).isEqualTo("NEW_PAYEE");
        assertThat(c.get(0).reasonCode()).isEqualTo("ML_AMOUNT_ZSCORE");
    }

    @Property(tries = 300)
    void logisticContributionsSumToMarginMinusIntercept(@ForAll @DoubleRange(min = -50, max = 50) double a,
                                                        @ForAll @DoubleRange(min = -50, max = 50) double b,
                                                        @ForAll @DoubleRange(min = -5, max = 5) double w) {
        LogisticModel m = new LogisticModel(w, new double[] {w, -0.3, 1.7}, new double[] {1, 2, 3}, new double[] {2, 3, 4});
        CompiledModel.Prediction p = m.predict(new double[] {a, b, a - b});
        double sum = p.bias();
        for (double c : p.contributions()) {
            sum += c;
        }
        assertThat(sum).isCloseTo(p.margin(), offset(1e-9));
    }

    // ------------------------------------------------------------------ trees

    /**
     * Two XGBoost-dump trees over (f0 = amount_zscore, f1 = new_payee), base_score 0.5:
     * <pre>
     * tree 0: amount_zscore < 2 ? (new_payee < 0.5 ? -0.4 [cover 60] : 0.2 [cover 20]) [cover 80] : 0.9 [cover 20]
     *         missing amount_zscore -> no (0.9)
     * tree 1: new_payee < 0.5 ? -0.1 [cover 70] : 0.3 [cover 30]
     * </pre>
     */
    static final String TWO_TREES = """
            {"format":"meridian-fraud-model/1","algorithm":"TREE_ENSEMBLE","features":["amount_zscore","new_payee"],
             "base_score":0.5,
             "trees":[
               {"nodeid":0,"split":"amount_zscore","split_condition":2,"yes":1,"no":2,"missing":2,"cover":100,"children":[
                 {"nodeid":1,"split":"new_payee","split_condition":0.5,"yes":3,"no":4,"missing":3,"cover":80,"children":[
                   {"nodeid":3,"leaf":-0.4,"cover":60},{"nodeid":4,"leaf":0.2,"cover":20}]},
                 {"nodeid":2,"leaf":0.9,"cover":20}]},
               {"nodeid":0,"split":"f1","split_condition":0.5,"yes":1,"no":2,"cover":100,"children":[
                 {"nodeid":2,"leaf":0.3,"cover":30},{"nodeid":1,"leaf":-0.1,"cover":70}]}
             ]}""";

    @Test
    void treeEnsembleMatchesHandComputation() {
        ModelArtifact a = ModelArtifact.parse(TWO_TREES, JSON, null);
        CompiledModel m = a.model();
        // x = (1, 1): tree0 -> node1 -> leaf 0.2 ; tree1 -> leaf 0.3 ; margin = 0.5 + 0.2 + 0.3 = 1.0
        CompiledModel.Prediction p = m.predict(new double[] {1, 1});
        assertThat(p.margin()).isCloseTo(1.0, offset(EPS));
        assertThat(p.probability()).isCloseTo(0.7310585786300049, offset(EPS));
        assertThat(p.score()).isEqualTo(731);
        // x = (2, 0): 2 < 2 is false -> 0.9 ; tree1 -> -0.1 ; margin = 1.3
        assertThat(m.predict(new double[] {2, 0}).margin()).isCloseTo(1.3, offset(EPS));
        // x = (NaN, NaN): tree0 missing -> no (0.9) ; tree1 missing (default yes) -> -0.1
        assertThat(m.predict(new double[] {Double.NaN, Double.NaN}).margin()).isCloseTo(1.3, offset(EPS));
    }

    @Test
    void saabasContributionsMatchHandComputation() {
        // expected values (cover weighted): tree0 node1 = (60*-0.4 + 20*0.2)/80 = -0.25 ; root = (80*-0.25 + 20*0.9)/100 = -0.02
        //                                   tree1 root = (70*-0.1 + 30*0.3)/100 = 0.02
        // bias = 0.5 - 0.02 + 0.02 = 0.5
        // x = (1, 1): tree0: amount_zscore += -0.25 - (-0.02) = -0.23 ; new_payee += 0.2 - (-0.25) = 0.45
        //             tree1: new_payee += 0.3 - 0.02 = 0.28
        ModelArtifact a = ModelArtifact.parse(TWO_TREES, JSON, null);
        CompiledModel.Prediction p = a.model().predict(new double[] {1, 1});
        assertThat(p.bias()).isCloseTo(0.5, offset(EPS));
        assertThat(p.contributions()[0]).isCloseTo(-0.23, offset(EPS));
        assertThat(p.contributions()[1]).isCloseTo(0.73, offset(EPS));
        assertThat(p.bias() + p.contributions()[0] + p.contributions()[1]).isCloseTo(p.margin(), offset(EPS));
    }

    @Test
    void lightGbmStructureUsesLessOrEqualAndDefaultLeft() {
        String artefact = """
                {"format":"meridian-fraud-model/1","algorithm":"TREE_ENSEMBLE","features":["txn_amount","new_payee"],
                 "trees":[{"tree_index":0,"tree_structure":{"split_feature":0,"threshold":100,"decision_type":"<=",
                   "default_left":false,"internal_count":10,
                   "left_child":{"leaf_value":-1.0,"leaf_count":5},
                   "right_child":{"split_feature":1,"threshold":0.5,"decision_type":"<=","default_left":true,"internal_count":5,
                     "left_child":{"leaf_value":0.5,"leaf_count":4},"right_child":{"leaf_value":2.0,"leaf_count":1}}}}]}""";
        CompiledModel m = ModelArtifact.parse(artefact, JSON, null).model();
        assertThat(m.predict(new double[] {100, 1}).margin()).isEqualTo(-1.0);      // 100 <= 100 -> left
        assertThat(m.predict(new double[] {100.01, 1}).margin()).isEqualTo(2.0);
        assertThat(m.predict(new double[] {Double.NaN, Double.NaN}).margin()).isEqualTo(0.5); // right, then default left
        // expected values: right = (4*0.5 + 1*2)/5 = 0.8 ; root = (5*-1 + 5*0.8)/10 = -0.1
        CompiledModel.Prediction p = m.predict(new double[] {200, 1});
        assertThat(p.bias()).isCloseTo(-0.1, offset(EPS));
        assertThat(p.contributions()[0]).isCloseTo(0.9, offset(EPS));
        assertThat(p.contributions()[1]).isCloseTo(1.2, offset(EPS));
    }

    @Property(tries = 300)
    void treeContributionsAlwaysSumToTheMargin(@ForAll @DoubleRange(min = -5, max = 5) double z,
                                               @ForAll @DoubleRange(min = 0, max = 1) double payee,
                                               @ForAll boolean missingZ) {
        CompiledModel m = ModelArtifact.parse(SAMPLE_GBDT.get(), JSON, null).model();
        double[] x = new double[m.size()];
        for (int i = 0; i < x.length; i++) {
            x[i] = (i % 3 == 0 ? z : payee) * (i + 1);
        }
        if (missingZ) {
            x[9] = Double.NaN;
        }
        CompiledModel.Prediction p = m.predict(x);
        double sum = p.bias();
        for (double c : p.contributions()) {
            sum += c;
        }
        assertThat(sum).isCloseTo(p.margin(), offset(1e-9));
        assertThat(p.probability()).isBetween(0.0, 1.0);
    }

    static final java.util.function.Supplier<String> SAMPLE_GBDT = () ->
            io.meridian.fraud.ml.SampleModels.load(io.meridian.fraud.ml.SampleModels.GBDT);

    @Test
    void inferenceIsDeterministic() {
        ModelArtifact a = ModelArtifact.parse(SAMPLE_GBDT.get(), JSON, null);
        ModelArtifact b = ModelArtifact.parse(SAMPLE_GBDT.get(), JSON, null);
        double[] x = new double[a.features().size()];
        java.util.Arrays.fill(x, 1.5);
        assertThat(Double.doubleToRawLongBits(a.model().predict(x).margin()))
                .isEqualTo(Double.doubleToRawLongBits(b.model().predict(x.clone()).margin()));
    }

    // ------------------------------------------------------------------ artefact validation

    @Test
    void invalidArtefactsAreRefusedWithStableCodes() {
        assertThatThrownBy(() -> ModelArtifact.parse("{\"format\":\"other\"}", JSON, null))
                .isInstanceOfSatisfying(ArtifactException.class, e -> assertThat(e.code()).isEqualTo("unsupported_format"));
        assertThatThrownBy(() -> ModelArtifact.parse("not json", JSON, null))
                .isInstanceOfSatisfying(ArtifactException.class, e -> assertThat(e.code()).isEqualTo("invalid_artifact"));
        assertThatThrownBy(() -> ModelArtifact.parse(TWO_TREES, JSON, java.util.Set.of("amount_zscore")))
                .isInstanceOfSatisfying(ArtifactException.class, e -> assertThat(e.code()).isEqualTo("unknown_feature"));
        assertThatThrownBy(() -> ModelArtifact.parse(TWO_TREES.replace("\"split\":\"f1\"", "\"split\":\"nope\""), JSON, null))
                .isInstanceOfSatisfying(ArtifactException.class, e -> assertThat(e.code()).isEqualTo("unknown_feature"));
        assertThatThrownBy(() -> ModelArtifact.parse(TWO_TREES.replace("\"leaf\":0.9", "\"leaf\":\"x\""), JSON, null))
                .isInstanceOf(ArtifactException.class);
        assertThatThrownBy(() -> ModelArtifact.parse("""
                {"format":"meridian-fraud-model/1","algorithm":"LOGISTIC_REGRESSION","features":["a_b"],
                 "logistic":{"intercept":0,"coefficients":[1,2]}}""", JSON, null))
                .isInstanceOfSatisfying(ArtifactException.class, e -> assertThat(e.code()).isEqualTo("invalid_logistic"));
        assertThatThrownBy(() -> ModelArtifact.parse("""
                {"format":"meridian-fraud-model/1","algorithm":"LOGISTIC_REGRESSION","features":["a_b"],
                 "logistic":{"intercept":0,"coefficients":[1]},
                 "thresholds":{"default":{"step_up":700,"review":600,"block":900}}}""", JSON, null))
                .isInstanceOfSatisfying(ArtifactException.class, e -> assertThat(e.code()).isEqualTo("invalid_thresholds"));
    }

    @Test
    void checksumIsSha256OfTheUtf8Content() {
        assertThat(ModelArtifact.sha256("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void distributionBinsPutEdgeValuesInTheUpperBin() {
        ModelArtifact.Distribution d = new ModelArtifact.Distribution(new double[] {10, 20}, new double[] {1, 1, 1});
        assertThat(d.bin(5)).isZero();
        assertThat(d.bin(10)).isEqualTo(1);
        assertThat(d.bin(19.9)).isEqualTo(1);
        assertThat(d.bin(20)).isEqualTo(2);
        assertThat(d.bin(1e9)).isEqualTo(2);
    }
}
