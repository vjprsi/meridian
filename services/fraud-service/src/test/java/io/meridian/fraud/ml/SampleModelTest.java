package io.meridian.fraud.ml;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.fraud.domain.features.FeatureCatalog;
import io.meridian.fraud.domain.ml.ModelArtifact;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shipped sample models are exactly what the synthetic trainer produces (deterministic), separate the synthetic
 * classes well, only use catalogued features and say loudly that they are synthetic. Set the environment variable
 * {@code REGENERATE_SAMPLE_MODELS=true} to rewrite the files after changing the generator or trainer.
 */
class SampleModelTest {
    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void shippedArtefactsMatchTheDeterministicTrainer() throws Exception {
        Map<String, String> built = SampleModels.build(json);
        boolean regenerate = "true".equalsIgnoreCase(System.getenv("REGENERATE_SAMPLE_MODELS"));
        for (Map.Entry<String, String> e : built.entrySet()) {
            Path file = Path.of("src/main/resources").resolve(e.getKey());
            if (regenerate || !Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, e.getValue(), StandardCharsets.UTF_8);
            }
            assertThat(Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n"))
                    .as("%s is stale: run with REGENERATE_SAMPLE_MODELS=true", e.getKey())
                    .isEqualTo(e.getValue());
        }
    }

    @Test
    void sampleModelsAreSyntheticCataloguedAndSeparateTheClasses() {
        for (String resource : List.of(SampleModels.GBDT, SampleModels.LR)) {
            String content = SampleModels.load(resource);
            ModelArtifact a = ModelArtifact.parse(content, json, FeatureCatalog.names());
            JsonNode meta = a.metadata();
            assertThat(meta.get("synthetic").asBoolean()).isTrue();
            assertThat(meta.get("note").asString()).contains("SYNTHETIC");
            assertThat(meta.get("holdout_auc").asDouble()).as(resource).isGreaterThan(0.8);
            assertThat(a.distributions()).containsKeys("score", "amount_zscore");
            assertThat(a.thresholds("ECOMMERCE").review()).isEqualTo(600);
            assertThat(a.thresholds("POS").review()).isEqualTo(650);
        }
    }

    @Test
    void treeInferenceReproducesTheTrainersOwnMargins() {
        JsonNode artefact = json.readTree(SampleModels.load(SampleModels.GBDT));
        ModelArtifact a = ModelArtifact.parse(SampleModels.load(SampleModels.GBDT), json, null);
        for (SyntheticFraudData.Row r : SyntheticFraudData.generate(300, 99)) {
            double margin = artefact.get("base_score").asDouble();
            for (JsonNode t : artefact.get("trees")) {
                JsonNode n = t;
                while (!n.has("leaf")) {
                    double v = r.x()[SyntheticFraudData.FEATURES.indexOf(n.get("split").asString())];
                    boolean yes = Double.isNaN(v) || v < n.get("split_condition").asDouble();
                    int next = yes ? n.get("yes").asInt() : n.get("no").asInt();
                    JsonNode c0 = n.get("children").get(0);
                    n = c0.get("nodeid").asInt() == next ? c0 : n.get("children").get(1);
                }
                margin += n.get("leaf").asDouble();
            }
            var p = a.model().predict(a.vector(SampleModels.asMap(r.x())));
            assertThat(p.margin()).isCloseTo(margin, org.assertj.core.data.Offset.offset(1e-9));
            double sum = p.bias();
            for (double c : p.contributions()) {
                sum += c;
            }
            assertThat(sum).isCloseTo(p.margin(), org.assertj.core.data.Offset.offset(1e-9));
        }
    }
}
