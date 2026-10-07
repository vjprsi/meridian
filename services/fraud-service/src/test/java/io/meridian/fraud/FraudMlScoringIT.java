package io.meridian.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.fraud.domain.features.FeatureCatalog;
import io.meridian.fraud.domain.ml.CompiledModel;
import io.meridian.fraud.domain.ml.ModelArtifact;
import io.meridian.fraud.ml.SampleModels;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ML scoring end to end against PostgreSQL 18 and Kafka: features fed by domain events on the real topics (with a
 * duplicate delivery and another tenant's events), models loaded from a stateful fake of ai-platform-service's
 * registry API (the shipped SYNTHETIC sample models, checksum verified), champion + shadow execution through the
 * existing evaluate endpoint, explanation with ML contributions, checksum failure and ai-platform outage degrading to
 * rules, hot reload on a deployment event, feature catalogue registration, monitoring feed and the training set.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.fraud.ml.feature-group=fraud-ml-it-features", "meridian.fraud.ml.refresh-ttl-ms=3600000"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FraudMlScoringIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000f5";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000f6";
    static final String DOWN_TENANT = "0199a000-0000-7000-8000-0000000000f7";
    static final String AI = "ai-platform-service";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final FakeAiPlatform ai = new FakeAiPlatform();
    final String customer = UUID.randomUUID().toString();
    final String card = UUID.randomUUID().toString();
    final String model = UUID.randomUUID().toString();
    final String gbdtVersion = UUID.randomUUID().toString();
    final String lrVersion = UUID.randomUUID().toString();
    final String championDeployment = UUID.randomUUID().toString();
    final String shadowDeployment = UUID.randomUUID().toString();
    String mlScore;

    @BeforeAll
    void setUp() throws Exception {
        String gbdt = SampleModels.load(SampleModels.GBDT);
        String lr = SampleModels.load(SampleModels.LR);
        ai.model(model, "CARD-FRAUD");
        ai.version(gbdtVersion, "1.0.0-synthetic", gbdt);
        ai.version(lrVersion, "0.9.0-synthetic", lr);
        ai.deployment(championDeployment, model, gbdtVersion, "CHAMPION", 100);
        ai.deployment(shadowDeployment, model, lrVersion, "SHADOW", 0);
        for (String t : List.of(TENANT, DOWN_TENANT)) {
            HttpResponse<String> r = send("POST", "/v1/risk/fraud/decision-policies", """
                    {"subject_type":"CARD_AUTHORIZATION","step_up_threshold":450,"review_threshold":650,"block_threshold":850,
                     "model_id":"%s","combination":"ADDITIVE","model_timeout_ms":20,
                     "channel_thresholds":{"ATM":{"step_up":300,"review":500,"block":700}}}""".formatted(model),
                    UUID.randomUUID().toString(), t, "alice");
            assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        }
    }

    /** MockitoBean stubs are reset after every test, so the fake is wired before each one. */
    @BeforeEach
    void wireFake() {
        when(serviceClient.get(eq(AI), anyString())).thenAnswer(inv -> ai.get(inv.getArgument(1)));
        when(serviceClient.post(eq(AI), anyString(), any(), anyString()))
                .thenAnswer(inv -> ai.post(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
        when(serviceClient.patch(eq(AI), anyString(), any(), anyString()))
                .thenAnswer(inv -> ai.patch(inv.getArgument(1), inv.getArgument(2)));
    }

    @Test
    @Order(1)
    void featureDefinitionsAreRegisteredInTheCatalogueOnce() throws Exception {
        JsonNode first = post("/v1/risk/fraud/model-scores/register-features", "", 200);
        assertThat(first.get("created").asInt()).isEqualTo(FeatureCatalog.all().size());
        assertThat(first.get("errors")).isEmpty();
        JsonNode zscore = ai.features.get("amount_zscore");
        assertThat(zscore.get("entity").asString()).isEqualTo("CUSTOMER");
        assertThat(zscore.get("feature_set").asString()).isEqualTo("fraud-realtime-v1");
        assertThat(zscore.get("definition").get("transform").asString()).contains("clamped to [-10, 10]");
        assertThat(ai.features.get("card_txn_count_1h").get("definition").get("window").asString()).isEqualTo("1h");
        JsonNode again = post("/v1/risk/fraud/model-scores/register-features", "", 200);
        assertThat(again.get("created").asInt()).isZero();
        assertThat(again.get("unchanged").asInt()).isEqualTo(FeatureCatalog.all().size());
    }

    @Test
    @Order(2)
    void domainEventsFeedTheFeatureStoreOncePerEventAndPerTenant() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (int i = 0; i < 4; i++) {
            String id = UUID.randomUUID().toString();
            String payload = """
                    {"id":"%s","card_id":"%s","customer_id":"%s","merchant_id":"MER-1","merchant_country":"GB",
                     "billing_amount":"40.00","billing_currency":"KES","created_at":"%s"}"""
                    .formatted(UUID.randomUUID(), card, customer, now.minusMinutes(10 + i));
            publish("meridian.cards.v1", id, "cards.card_authorization.approved", TENANT, payload);
            if (i == 0) {
                publish("meridian.cards.v1", id, "cards.card_authorization.approved", TENANT, payload); // redelivery
            }
        }
        publish("meridian.payments.v1", UUID.randomUUID().toString(), "payments.payment.initiated", TENANT, """
                {"id":"%s","direction":"OUTBOUND","customer_id":"%s","debtor_account_id":"%s","amount":"900.00",
                 "currency":"KES","creditor_account_number":"0011223344","creditor_agent_bic":"BANKKENX",
                 "creditor_country":"KE","created_at":"%s"}""".formatted(UUID.randomUUID(), customer, UUID.randomUUID(),
                now.minusDays(2)));
        publish("meridian.payments.v1", UUID.randomUUID().toString(), "payments.payment.received", TENANT,
                "{\"customer_id\":\"%s\",\"amount\":\"5\",\"currency\":\"KES\"}".formatted(customer)); // not a source
        // the same card in another tenant
        publish("meridian.cards.v1", UUID.randomUUID().toString(), "cards.card_authorization.declined", OTHER_TENANT, """
                {"card_id":"%s","customer_id":"%s","merchant_id":"MER-1","billing_amount":"1.00","billing_currency":"KES",
                 "created_at":"%s"}""".formatted(card, customer, now.minusMinutes(3)));

        JsonNode f = awaitFeatures(TENANT, previewBody("MER-2", "0011223344", "BANKKENX"),
                x -> x.path("customer_txn_count_7d").asDouble() >= 5);
        assertThat(f.get("card_txn_count_1h").asDouble()).isEqualTo(4.0);      // the redelivered event counted once
        assertThat(f.get("card_txn_amount_1h").asDouble()).isEqualTo(160.0);
        assertThat(f.get("customer_txn_count_24h").asDouble()).isEqualTo(4.0);
        assertThat(f.get("customer_txn_count_7d").asDouble()).isEqualTo(5.0);
        assertThat(f.get("customer_txn_amount_7d").asDouble()).isEqualTo(1060.0);
        assertThat(f.get("new_payee").asDouble()).isEqualTo(0.0);               // paid that account two days ago
        assertThat(f.get("new_merchant_for_card").asDouble()).isEqualTo(1.0);   // MER-2 is new for the card
        assertThat(f.get("beneficiary_age_days").asDouble()).isBetween(1.9, 2.1);
        assertThat(f.get("customer_history_count").asDouble()).isEqualTo(5.0);
        assertThat(f.get("merchant_history_count").asDouble()).isZero();
        JsonNode known = awaitFeatures(TENANT, previewBody("MER-1", null, null), x -> true);
        assertThat(known.get("new_merchant_for_card").asDouble()).isZero();
        assertThat(known.get("merchant_history_count").asDouble()).isEqualTo(4.0);
        assertThat(known.get("new_payee").isNull()).isTrue();                  // no beneficiary in the request

        JsonNode other = awaitFeatures(OTHER_TENANT, previewBody("MER-1", null, null), x -> x.path("card_txn_count_1h").asDouble() >= 1);
        assertThat(other.get("card_txn_count_1h").asDouble()).isEqualTo(1.0);  // tenant isolated
        JsonNode runtime = get("/v1/risk/fraud/model-scores/runtime", TENANT);
        assertThat(runtime.get("feature_store").get("fraud_feature_event").asLong()).isEqualTo(5);
    }

    @Test
    @Order(3)
    void championScoresThroughTheEvaluateEndpointAndTheShadowIsOnlyLogged() throws Exception {
        JsonNode r = evaluate(TENANT, "MER-2", "ECOMMERCE", "4800.00");
        assertThat(r.get("degraded").asBoolean()).as(r.toString()).isFalse();
        assertThat(r.get("model_id").asString()).isEqualTo(model);
        assertThat(r.get("ml_model_version_id").asString()).isEqualTo(gbdtVersion);
        assertThat(r.get("ml_deployment_id").asString()).isEqualTo(championDeployment);
        assertThat(r.get("combination").asString()).isEqualTo("ADDITIVE");
        assertThat(r.get("model_version").asString()).isEqualTo("1.0.0-synthetic");
        mlScore = r.get("fraud_score_id").asString();

        // the stored inputs reproduce the champion's score exactly (pure, deterministic inference)
        JsonNode stored = get("/v1/risk/fraud/scores/" + mlScore, TENANT);
        Map<String, Double> inputs = new HashMap<>();
        stored.get("features").get("engineered").properties().forEach(e -> inputs.put(e.getKey(), e.getValue().asDouble()));
        stored.get("features").get("store").properties().forEach(e ->
                inputs.put(e.getKey(), e.getValue().isNull() ? Double.NaN : e.getValue().asDouble()));
        ModelArtifact gbdt = ModelArtifact.parse(SampleModels.load(SampleModels.GBDT), json, null);
        CompiledModel.Prediction p = gbdt.model().predict(gbdt.vector(inputs));
        // stored inputs are rounded to 6 decimals, so allow one point of rounding
        assertThat(r.get("model_score").asInt()).isCloseTo(p.score(), org.assertj.core.data.Offset.offset(1));
        assertThat(r.get("score").asInt()).isEqualTo(Math.min(1000, r.get("model_score").asInt() + r.get("rule_score").asInt()));
        assertThat(stored.get("rules_component_score").asInt()).isNotNegative();

        JsonNode rows = get("/v1/risk/fraud/model-scores?fraud_score_id=" + mlScore, TENANT).get("data");
        assertThat(rows).hasSize(2);
        JsonNode champion = find(rows, "role", "CHAMPION");
        JsonNode shadow = find(rows, "role", "SHADOW");
        assertThat(champion.get("decided").asBoolean()).isTrue();
        assertThat(champion.get("would_decide").asString()).isEqualTo(r.get("decision").asString());
        assertThat(champion.get("feature_vector").get("card_txn_count_1h").asDouble()).isEqualTo(4.0);
        assertThat(shadow.get("decided").asBoolean()).isFalse();
        assertThat(shadow.get("ml_model_version_id").asString()).isEqualTo(lrVersion);
        assertThat(shadow.get("score").isNull()).isFalse();
        assertThat(r.get("shadow_decision").asString()).isEqualTo(shadow.get("would_decide").asString());

        JsonNode x = get("/v1/risk/fraud/scores/" + mlScore + "/explanation", TENANT);
        assertThat(x.get("ml").get("algorithm").asString()).isEqualTo("TREE_ENSEMBLE");
        JsonNode contributions = x.get("ml").get("contributions");
        assertThat(contributions).isNotEmpty();
        assertThat(contributions.get(0).get("reason_code").asString()).startsWith("ML_");
        assertThat(x.get("ml").get("probability").asDouble()).isCloseTo(p.probability(), org.assertj.core.data.Offset.offset(1e-5));
        assertThat(champion.get("top_contributions")).isNotEmpty();
        assertThat(x.get("model").asString()).startsWith("ml:" + model);

        // replay returns the stored ML fields
        assertThat(r.get("replayed").asBoolean()).isFalse();
    }

    @Test
    @Order(4)
    void aChecksumMismatchDegradesToRules() throws Exception {
        // a new version whose artefact does not match its registered checksum (compiled versions are cached by
        // version and checksum, so the tampered bytes must come with a version that was never loaded)
        String tamperedVersion = UUID.randomUUID().toString();
        ai.version(tamperedVersion, "1.0.1-synthetic", SampleModels.load(SampleModels.GBDT));
        ai.tamper(tamperedVersion, true);
        ai.deployment(championDeployment, model, tamperedVersion, "CHAMPION", 100);
        JsonNode reloaded = post("/v1/risk/fraud/model-scores/reload", "", 200);
        JsonNode champion = deployment(reloaded, championDeployment);
        assertThat(champion.get("loaded").asBoolean()).isFalse();
        assertThat(champion.get("error").asString()).isEqualTo("CHECKSUM_MISMATCH");
        JsonNode r = evaluate(TENANT, "MER-1", "POS", "25.00");
        assertThat(r.get("degraded").asBoolean()).isTrue();
        assertThat(r.get("degraded_reason").asString()).isEqualTo("CHECKSUM_MISMATCH");
        assertThat(r.get("ml_model_version_id").isNull()).isTrue();
        assertThat(r.get("model_version").asString()).startsWith("meridian-lr@");     // the built-in heuristic decided
        assertThat(r.get("score").asInt()).isEqualTo(r.get("rules_component_score").asInt());
        // the shadow still scores (logged) while the champion is unusable
        JsonNode rows = get("/v1/risk/fraud/model-scores?fraud_score_id=" + r.get("fraud_score_id").asString(), TENANT).get("data");
        assertThat(find(rows, "role", "CHAMPION").get("error").asString()).isEqualTo("CHECKSUM_MISMATCH");
        assertThat(find(rows, "role", "SHADOW").get("decided").asBoolean()).isFalse();
        ai.deployment(championDeployment, model, gbdtVersion, "CHAMPION", 100);
        assertThat(deployment(post("/v1/risk/fraud/model-scores/reload", "", 200), championDeployment).get("loaded").asBoolean()).isTrue();
    }

    @Test
    @Order(5)
    void anUnreachableModelRegistryDegradesToRules() throws Exception {
        JsonNode r = evaluate(DOWN_TENANT, "MER-1", "POS", "25.00");
        assertThat(r.get("decision").asString()).isNotBlank();
        assertThat(r.get("degraded").asBoolean()).isTrue();
        assertThat(r.get("degraded_reason").asString()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(r.get("score").asInt()).isEqualTo(r.get("rules_component_score").asInt());
    }

    @Test
    @Order(6)
    void aDeploymentEventHotReloadsTheChampion() throws Exception {
        // ai-platform promotes the logistic model: the GBDT champion is retired
        ai.deployment(shadowDeployment, model, lrVersion, "CHAMPION", 100);
        ai.retire(championDeployment);
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        JsonNode champion = null;
        while (System.nanoTime() < deadline) {
            publish("meridian.reporting.v1", UUID.randomUUID().toString(), "reporting.model_deployment.promoted", TENANT,
                    "{\"id\":\"%s\",\"mode\":\"CHAMPION\"}".formatted(shadowDeployment));
            JsonNode rt = get("/v1/risk/fraud/model-scores/runtime", TENANT);
            champion = rt.path("models").path(0).path("deployments").path(0);
            if (shadowDeployment.equals(champion.path("deployment_id").asString(null))) {
                break;
            }
            Thread.sleep(500);
        }
        assertThat(champion.get("role").asString()).isEqualTo("CHAMPION");
        assertThat(champion.get("deployment_id").asString()).isEqualTo(shadowDeployment);
        JsonNode r = evaluate(TENANT, "MER-1", "POS", "60.00");
        assertThat(r.get("ml_model_version_id").asString()).isEqualTo(lrVersion);
        assertThat(get("/v1/risk/fraud/model-scores?fraud_score_id=" + r.get("fraud_score_id").asString(), TENANT).get("data"))
                .singleElement().satisfies(row -> assertThat(row.get("decided").asBoolean()).isTrue());
        // restore the original set-up for the monitoring test
        ai.deployment(championDeployment, model, gbdtVersion, "CHAMPION", 100);
        ai.deployment(shadowDeployment, model, lrVersion, "SHADOW", 0);
        post("/v1/risk/fraud/model-scores/reload", "", 200);
    }

    @Test
    @Order(7)
    void labelsBecomeTrainingDataAndMonitoringIsPublished() throws Exception {
        for (int i = 0; i < 3; i++) {
            evaluate(TENANT, "MER-" + (i % 2 + 1), "POS", (100 * (i + 1)) + ".00");
        }
        post("/v1/risk/fraud/scores/" + mlScore + "/feedback", "{\"label\":\"FRAUD\",\"typology\":\"CARD_NOT_PRESENT\",\"source\":\"CHARGEBACK\"}", 200);
        String from = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).truncatedTo(ChronoUnit.SECONDS).toString();
        String to = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5).truncatedTo(ChronoUnit.SECONDS).toString();
        JsonNode training = get("/v1/risk/fraud/feedback/training-set?from_ts=" + enc(from) + "&to_ts=" + enc(to), TENANT);
        assertThat(training.get("count").asInt()).isEqualTo(1);
        JsonNode row = training.get("rows").get(0);
        assertThat(row.get("label").asString()).isEqualTo("FRAUD");
        assertThat(row.get("features").get("card_txn_count_1h").asDouble()).isEqualTo(4.0);
        assertThat(row.get("features").has("velocity_1h")).isTrue();

        JsonNode published = post("/v1/risk/fraud/model-scores/publish-monitoring",
                "{\"window_start\":\"%s\",\"window_end\":\"%s\"}".formatted(from, to), 200);
        JsonNode champ = find(published.get("deployments"), "deployment_id", championDeployment);
        assertThat(champ.get("drift_features").toString()).contains("score");
        assertThat(champ.get("errors")).isEmpty();
        assertThat(champ.get("metrics").has("LATENCY_P99")).isTrue();
        assertThat(champ.get("metrics").get("RECALL").asDouble()).isBetween(0.0, 1.0);
        assertThat(ai.drift).containsKey(championDeployment);
        JsonNode drift = ai.drift.get(championDeployment);
        JsonNode scoreBins = find(drift.get("features"), "name", "score");
        assertThat(scoreBins.get("expected")).hasSize(10);
        double actual = 0;
        for (JsonNode n : scoreBins.get("actual")) {
            actual += n.asDouble();
        }
        assertThat(actual).isGreaterThanOrEqualTo(4.0);
        assertThat(ai.metrics).anySatisfy(m -> assertThat(m.get("metric_name").asString()).isEqualTo("ERROR_RATE"));
        assertThat(published.get("degraded_evaluations").asLong()).isGreaterThanOrEqualTo(1); // the checksum test
    }

    @Autowired
    io.meridian.fraud.infrastructure.FeatureEventsListener featureEvents;

    @Autowired
    io.meridian.fraud.application.FeatureService featureService;

    /** The SQL windows agree with the in-memory reference ({@code WindowCounter}) for random event times, redelivered. */
    @Test
    @Order(8)
    void sqlWindowsMatchTheReferenceImplementation() throws Exception {
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(42);
        UUID tenant = UUID.fromString(TENANT);
        for (int round = 0; round < 3; round++) {
            String cardId = UUID.randomUUID().toString();
            java.time.Instant now = io.meridian.common.time.Clocks.now().toInstant();
            io.meridian.fraud.domain.features.WindowCounter reference = new io.meridian.fraud.domain.features.WindowCounter();
            for (int i = 0; i < 80; i++) {
                long ago = switch (i % 4) {
                    case 0 -> rnd.nextLong(0, 9 * 86_400L);
                    case 1 -> rnd.nextLong(3_000, 3_900);           // around the 1 h edge
                    case 2 -> rnd.nextLong(82_000, 87_000);         // around the 24 h edge
                    default -> rnd.nextLong(600_000, 606_000);      // around the 7 d edge
                };
                java.time.Instant at = now.minusSeconds(ago);
                java.math.BigDecimal amount = java.math.BigDecimal.valueOf(rnd.nextInt(1, 100_000), 2);
                var e = new io.meridian.fraud.domain.features.FeatureEvent("parity-" + round + "-" + i,
                        io.meridian.fraud.domain.features.FeatureEvent.CARD_APPROVED, at, amount, "KES",
                        Map.of("CARD", cardId), Map.of(), List.of());
                boolean first = TenantContext.callAs(tenant, "test", () -> featureEvents.apply(tenant, e));
                boolean again = TenantContext.callAs(tenant, "test", () -> featureEvents.apply(tenant, e));
                assertThat(again).isFalse();
                if (first) {
                    reference.add(at, amount.doubleValue());
                }
            }
            JsonNode req = json.readTree("{\"card_id\":\"%s\",\"currency\":\"KES\"}".formatted(cardId));
            Map<String, Double> f = TenantContext.callAs(tenant, "test", () -> featureService.compute(req, now));
            for (var w : io.meridian.fraud.domain.features.Buckets.Window.values()) {
                double[] expected = reference.window(w, now);
                assertThat(f.get("card_txn_count_" + w.suffix)).as("count " + w.suffix).isEqualTo(expected[0]);
                assertThat(f.get("card_txn_amount_" + w.suffix)).as("amount " + w.suffix)
                        .isCloseTo(expected[1], org.assertj.core.data.Offset.offset(1e-6));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode evaluate(String tenant, String merchant, String channel, String amount) throws Exception {
        String body = """
                {"subject_type":"CARD_AUTHORIZATION","subject_id":"%s","customer_id":"%s","card_id":"%s","amount":"%s",
                 "currency":"KES","channel":"%s","counterparty_country":"GB","device_fingerprint":"fp-ml-1",
                 "context":{"merchant_id":"%s","merchant_country":"GB"}}"""
                .formatted(UUID.randomUUID(), customer, card, amount, channel, merchant);
        HttpResponse<String> r = send("POST", "/v1/risk/fraud/scores/evaluate", body, UUID.randomUUID().toString(), tenant, "svc");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private String previewBody(String merchant, String beneficiaryAccount, String bic) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("merchant_id", merchant);
        if (beneficiaryAccount != null) {
            ctx.put("beneficiary_account", beneficiaryAccount);
            ctx.put("beneficiary_bic", bic);
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("customer_id", customer);
        b.put("card_id", card);
        b.put("amount", "120.00");
        b.put("currency", "KES");
        b.put("context", ctx);
        return json.writeValueAsString(b);
    }

    private JsonNode awaitFeatures(String tenant, String body, Predicate<JsonNode> done) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        JsonNode f = null;
        while (System.nanoTime() < deadline) {
            HttpResponse<String> r = send("POST", "/v1/risk/fraud/model-scores/preview-features", body,
                    UUID.randomUUID().toString(), tenant, "analyst");
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            f = json.readTree(r.body()).get("features");
            if (done.test(f)) {
                return f;
            }
            Thread.sleep(300);
        }
        return f;
    }

    private void publish(String topic, String id, String type, String tenant, String payload) throws Exception {
        ProducerRecord<String, String> rec = new ProducerRecord<>(topic, id, payload);
        rec.headers().add("ce_id", id.getBytes(StandardCharsets.UTF_8));
        rec.headers().add("ce_type", type.getBytes(StandardCharsets.UTF_8));
        rec.headers().add("ce_time", OffsetDateTime.now(ZoneOffset.UTC).toString().getBytes(StandardCharsets.UTF_8));
        rec.headers().add("ce_tenantid", tenant.getBytes(StandardCharsets.UTF_8));
        kafka.send(rec).get();
    }

    private static JsonNode find(JsonNode arr, String field, String value) {
        for (JsonNode n : arr) {
            if (value.equals(n.path(field).asString(null))) {
                return n;
            }
        }
        throw new AssertionError(field + "=" + value + " not in " + arr);
    }

    private static JsonNode deployment(JsonNode runtime, String id) {
        for (JsonNode m : runtime.get("models")) {
            for (JsonNode d : m.get("deployments")) {
                if (id.equals(d.get("deployment_id").asString())) {
                    return d;
                }
            }
        }
        throw new AssertionError(id + " not loaded: " + runtime);
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant, "analyst");
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), TENANT, "alice");
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String key, String tenant, String user)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("X-Tenant-Id", tenant).header("X-User", user);
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        b.method(method, body == null || body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Stateful stand-in for the parts of ai-platform-service's API fraud-service uses (paths, page shape and fields as
     * in generated/openapi/services/ai-platform-service.openapi.yaml). DOWN_TENANT sees an unreachable service.
     */
    final class FakeAiPlatform {
        final Map<String, Map<String, Object>> models = new ConcurrentHashMap<>();
        final Map<String, Map<String, Object>> versions = new ConcurrentHashMap<>();
        final Map<String, String> artifacts = new ConcurrentHashMap<>();
        final Map<String, Boolean> tampered = new ConcurrentHashMap<>();
        final Map<String, Map<String, Object>> deployments = new ConcurrentHashMap<>();
        final Map<String, JsonNode> features = new ConcurrentHashMap<>();
        final Map<String, JsonNode> drift = new ConcurrentHashMap<>();
        final List<JsonNode> metrics = new CopyOnWriteArrayList<>();

        void model(String id, String code) {
            models.put(id, Map.of("id", id, "code", code, "model_type", "FRAUD", "status", "ACTIVE"));
        }

        void version(String id, String label, String content) {
            JsonNode a = json.readTree(content);
            List<String> fs = new ArrayList<>();
            a.get("features").forEach(f -> fs.add(f.asString()));
            versions.put(id, Map.of("id", id, "ml_model_id", model, "model_version", label, "status", "DEPLOYED",
                    "artifact_sha256", ModelArtifact.sha256(content), "features", fs));
            artifacts.put(id, content);
        }

        void deployment(String id, String modelId, String versionId, String mode, int traffic) {
            deployments.put(id, Map.of("id", id, "ml_model_id", modelId, "ml_model_version_id", versionId, "environment",
                    "PRODUCTION", "mode", mode, "traffic_percent", traffic, "status", "ACTIVE"));
        }

        void retire(String id) {
            Map<String, Object> d = new HashMap<>(deployments.get(id));
            d.put("status", "RETIRED");
            deployments.put(id, d);
        }

        void tamper(String versionId, boolean on) {
            tampered.put(versionId, on);
        }

        JsonNode get(String path) {
            down();
            if (path.startsWith("/v1/ai/models?")) {
                return page(new ArrayList<>(models.values()));
            }
            if (path.startsWith("/v1/ai/deployments?")) {
                return page(deployments.values().stream().filter(d -> "ACTIVE".equals(d.get("status"))).map(d -> (Object) d).toList());
            }
            if (path.startsWith("/v1/ai/features?")) {
                return page(new ArrayList<>(features.values()));
            }
            if (path.startsWith("/v1/ai/model-versions/")) {
                String rest = path.substring("/v1/ai/model-versions/".length());
                if (rest.endsWith("/artifact")) {
                    String id = rest.substring(0, rest.length() - "/artifact".length());
                    String content = artifacts.get(id);
                    if (content == null) {
                        throw ApiException.notFound("artifact of model version", id);
                    }
                    if (Boolean.TRUE.equals(tampered.get(id))) {
                        content = content.replace("\"base_score\"", " \"base_score\""); // altered in transit
                    }
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("ml_model_version_id", id);
                    a.put("format", "meridian-fraud-model/1");
                    a.put("content", content);
                    a.put("sha256", ModelArtifact.sha256(artifacts.get(id)));
                    return json.valueToTree(a);
                }
                Map<String, Object> v = versions.get(rest);
                if (v == null) {
                    throw ApiException.notFound("model version", rest);
                }
                return json.valueToTree(v);
            }
            throw new ApiException(404, "not_found", path);
        }

        JsonNode post(String path, Object body, String key) {
            down();
            JsonNode b = json.valueToTree(body);
            if (path.equals("/v1/ai/features")) {
                tools.jackson.databind.node.ObjectNode row = (tools.jackson.databind.node.ObjectNode) b.deepCopy();
                row.put("id", UUID.randomUUID().toString());
                row.put("row_version", 1);
                features.put(b.get("name").asString(), row);
                return row;
            }
            if (path.startsWith("/v1/ai/deployments/") && path.endsWith("/drift")) {
                String id = path.substring("/v1/ai/deployments/".length(), path.length() - "/drift".length());
                drift.put(id, b);
                return json.readTree("{\"max_psi\":\"0.5\",\"stability\":\"SIGNIFICANT\"}");
            }
            if (path.equals("/v1/ai/model-metrics")) {
                metrics.add(b);
                return b;
            }
            throw new ApiException(404, "not_found", path);
        }

        JsonNode patch(String path, Object body) {
            down();
            String id = path.substring("/v1/ai/features/".length());
            for (Map.Entry<String, JsonNode> e : features.entrySet()) {
                if (id.equals(e.getValue().get("id").asString())) {
                    tools.jackson.databind.node.ObjectNode row = (tools.jackson.databind.node.ObjectNode) e.getValue().deepCopy();
                    row.setAll((tools.jackson.databind.node.ObjectNode) json.valueToTree(body));
                    row.put("row_version", row.get("row_version").asInt() + 1);
                    features.put(e.getKey(), row);
                    return row;
                }
            }
            throw ApiException.notFound("feature", id);
        }

        private void down() {
            if (TenantContext.current().map(t -> t.toString().equals(DOWN_TENANT)).orElse(false)) {
                throw new IllegalStateException("ai-platform-service: connection refused");
            }
        }

        private JsonNode page(List<Object> rows) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("data", rows);
            p.put("next_cursor", null);
            p.put("has_more", false);
            return json.valueToTree(p);
        }
    }
}
