package io.meridian.aiplatform;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registering the SYNTHETIC sample fraud model shipped by fraud-service ({@code services/fraud-service/src/main/
 * resources/ml/sample/}) through the model registry API: version with the artefact checksum, inline artefact upload
 * (checksum enforced, write-once, locked checksum), model-risk approval under four eyes, shadow deployment promoted to
 * canary and champion, artefact download as fraud-service reads it, and the feature catalogue entries with exact
 * definitions and feature sets.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FraudModelRegistrationIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000a5";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000a6";
    static final Path SAMPLE = Path.of("../fraud-service/src/main/resources/ml/sample/sample-fraud-gbdt.json");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @MockitoBean
    ServiceClient services;

    final HttpClient http = HttpClient.newHttpClient();
    String content;
    String sha;
    String model;
    String version;
    String deployment;

    @Test
    @Order(1)
    void theSampleArtefactIsRegisteredWithItsChecksum() throws Exception {
        content = Files.readString(SAMPLE, StandardCharsets.UTF_8);
        JsonNode artefact = json.readTree(content);
        assertThat(artefact.get("metadata").get("synthetic").asBoolean()).isTrue();
        sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        model = post("/v1/ai/models", """
                {"code":"FRAUD-CARD-SAMPLE","name":"Card fraud (synthetic sample)","model_type":"FRAUD","risk_tier":"MEDIUM",
                 "owner_team":"fraud-ds","intended_use":"Demonstration only: trained on synthetic data"}""", "dev", 201)
                .get("id").asString();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ml_model_id", model);
        v.put("model_version", "1.0.0-synthetic");
        v.put("framework", "XGBOOST");
        v.put("artifact_uri", "inline:meridian-fraud-model/1");
        v.put("artifact_sha256", sha);
        v.put("training_dataset_ref", "synthetic:SyntheticFraudData(seed=20261007)");
        List<String> features = json.convertValue(artefact.get("features"), new tools.jackson.core.type.TypeReference<List<String>>() {});
        v.put("features", features);
        v.put("metrics", Map.of("AUC", artefact.get("metadata").get("holdout_auc").asDouble()));
        v.put("bias_test_results", List.of(Map.of("attribute", "none (synthetic)", "metric", "N/A", "value", 1, "threshold", 1,
                "passed", true)));
        v.put("explainability_notes", "Saabas path contributions per feature; reason codes in the artefact");
        version = post("/v1/ai/model-versions", json.writeValueAsString(v), "dev", 201).get("id").asString();

        // the content must hash to the registered checksum
        JsonNode mismatch = post("/v1/ai/model-versions/" + version + "/artifact",
                body("meridian-fraud-model/1", content + " "), "dev", 422);
        assertThat(mismatch.get("code").asString()).isEqualTo("checksum_mismatch");
        JsonNode notJson = post("/v1/ai/model-versions/" + version + "/artifact", body("meridian-fraud-model/1", "x"), "dev", 422);
        assertThat(notJson.get("code").asString()).isIn("invalid_artifact", "checksum_mismatch");
        JsonNode uploaded = post("/v1/ai/model-versions/" + version + "/artifact", body("meridian-fraud-model/1", content), "dev", 201);
        assertThat(uploaded.get("sha256").asString()).isEqualTo(sha);
        assertThat(uploaded.get("size_bytes").asInt()).isEqualTo(content.getBytes(StandardCharsets.UTF_8).length);
        // write-once, and the checksum of a version with an artefact is locked
        assertThat(post("/v1/ai/model-versions/" + version + "/artifact", body("meridian-fraud-model/1", content), "dev", 409)
                .get("code").asString()).isEqualTo("artifact_exists");
        HttpResponse<String> relabel = send("PATCH", "/v1/ai/model-versions/" + version, "{\"artifact_sha256\":\"" + "b".repeat(64) + "\"}",
                null, "\"1\"", TENANT, "dev");
        assertThat(relabel.statusCode()).as(relabel.body()).isEqualTo(409);
        assertThat(json.readTree(relabel.body()).get("code").asString()).isEqualTo("artifact_locked");
    }

    @Test
    @Order(2)
    void approvedAndDeployedTheArtefactIsServedByteForByte() throws Exception {
        post("/v1/ai/model-versions/" + version + "/submit-for-approval", "{\"comment\":\"synthetic sample\"}", "dev", 200);
        assertThat(post("/v1/ai/model-versions/" + version + "/approve", "{}", "dev", 422).get("code").asString())
                .isEqualTo("four_eyes_violation");
        post("/v1/ai/model-versions/" + version + "/approve", "{\"comment\":\"MRM: demo only\"}", "risk", 200);
        // an approved version's artefact can no longer be set
        assertThat(post("/v1/ai/model-versions/" + version + "/artifact", body("meridian-fraud-model/1", content), "dev", 409)
                .get("code").asString()).isEqualTo("version_locked");
        deployment = post("/v1/ai/model-versions/" + version + "/deploy",
                "{\"environment\":\"PRODUCTION\",\"mode\":\"SHADOW\",\"thresholds\":{\"PSI\":0.2}}", "ops", 201).get("id").asString();
        JsonNode canary = post("/v1/ai/deployments/" + deployment + "/promote", "{\"traffic_percent\":10}", "ops", 200);
        assertThat(canary.get("mode").asString()).isEqualTo("CANARY");
        JsonNode champion = post("/v1/ai/deployments/" + deployment + "/promote", "{}", "ops", 200);
        assertThat(champion.get("mode").asString()).isEqualTo("CHAMPION");

        // what fraud-service reads: active production deployments of FRAUD models, the version and its artefact
        JsonNode models = get("/v1/ai/models?model_type=FRAUD&limit=200", TENANT).get("data");
        assertThat(models).anySatisfy(m -> assertThat(m.get("id").asString()).isEqualTo(model));
        JsonNode deployments = get("/v1/ai/deployments?environment=PRODUCTION&status=ACTIVE&limit=200", TENANT).get("data");
        assertThat(deployments).anySatisfy(d -> assertThat(d.get("ml_model_version_id").asString()).isEqualTo(version));
        JsonNode v = get("/v1/ai/model-versions/" + version, TENANT);
        assertThat(v.get("artifact_sha256").asString()).isEqualTo(sha);
        assertThat(v.has("content")).isFalse(); // the artefact is not part of the version resource
        JsonNode a = get("/v1/ai/model-versions/" + version + "/artifact", TENANT);
        assertThat(a.get("content").asString()).isEqualTo(content);
        assertThat(a.get("sha256").asString()).isEqualTo(sha).isEqualTo(a.get("artifact_sha256").asString());
        assertThat(a.get("format").asString()).isEqualTo("meridian-fraud-model/1");
        HttpResponse<String> other = send("GET", "/v1/ai/model-versions/" + version + "/artifact", null, null, null, OTHER_TENANT, "x");
        assertThat(other.statusCode()).isEqualTo(404);
    }

    @Test
    @Order(3)
    void featureDefinitionsCarryTheExactComputation() throws Exception {
        JsonNode f = post("/v1/ai/features", """
                {"name":"card_txn_count_1h","entity":"CARD","dtype":"INT","description":"Transactions of the card in the last 1h",
                 "source_dataset":"kafka:meridian.cards.v1","online":true,"freshness_sla_seconds":60,"owner":"fraud-service",
                 "feature_set":"fraud-realtime-v1",
                 "definition":{"aggregation":"count(*)","window":"1h","bucket":"5m","entity_key":"card_id"}}""", "fraud-svc", 201);
        assertThat(f.get("definition").get("bucket").asString()).isEqualTo("5m");
        post("/v1/ai/features", """
                {"name":"txn_amount","entity":"TRANSACTION","dtype":"FLOAT","source_dataset":"fraud-service:evaluation",
                 "feature_set":"fraud-realtime-v1","definition":{"source":"evaluate request amount"}}""", "fraud-svc", 201);
        assertThat(post("/v1/ai/features", """
                {"name":"bad_definition","entity":"CARD","dtype":"INT","source_dataset":"x","definition":"count"}""", "fraud-svc", 400)
                .get("code").asString()).isEqualTo("malformed_request");
        JsonNode set = get("/v1/ai/features?feature_set=fraud-realtime-v1", TENANT).get("data");
        assertThat(set).hasSize(2);
        assertThat(get("/v1/ai/features?name=txn_amount", TENANT).get("data")).singleElement()
                .satisfies(x -> assertThat(x.get("entity").asString()).isEqualTo("TRANSACTION"));
    }

    // ------------------------------------------------------------------ helpers

    private String body(String format, String artefact) {
        return json.writeValueAsString(Map.of("format", format, "content", artefact));
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), null, TENANT, user);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, null, tenant, "reader");
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String key, String ifMatch, String tenant,
                                      String user) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("X-Tenant-Id", tenant).header("X-User", user);
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
