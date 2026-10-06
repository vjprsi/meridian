package io.meridian.aiplatform;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end over HTTP against PostgreSQL 18 with row-level security: model risk management (submit, four-eyes
 * approval / rejection, bias and explainability gates), deployments (shadow -> canary -> champion, rollback restoring
 * the previous champion), monitoring metrics with thresholds and PSI drift, the per-tenant monitoring job with
 * auto-rollback, the feature catalogue, LLM gateway policies and copilots (redaction, hashes, citations, proposed
 * actions under human approval, budgets and usage).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiPlatformFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000a1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000a2";
    static final String DEV = "alice";
    static final String RISK = "bob";
    static final String SHA = "a".repeat(64);

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient services;

    final HttpClient http = HttpClient.newHttpClient();
    String model;
    String v1;
    String v4;
    String champion;
    String canary;
    String policy;
    String copilot;
    String proposal;

    @Test
    @Order(1)
    void versionsPassModelRiskGatesUnderFourEyes() throws Exception {
        model = post("/v1/ai/models", """
                {"code":"FRAUD-TX","name":"Transaction fraud","model_type":"FRAUD","risk_tier":"HIGH","owner_team":"fraud-ds",
                 "next_review_date":"2027-01-01"}""", DEV, 201).get("id").asString();
        assertThat(code(post("/v1/ai/model-versions", versionBody("1.0.0", "\"artifact_sha256\":\"xyz\"", true), DEV, 422)))
                .isEqualTo("validation_failed");
        JsonNode created = post("/v1/ai/model-versions", versionBody("1.0.0", "\"artifact_sha256\":\"" + SHA + "\"", true), DEV, 201);
        v1 = created.get("id").asString();
        assertThat(created.get("status").asString()).isEqualTo("TRAINING");
        assertThat(created.get("features").get(0).asString()).isEqualTo("txn_amount");

        // HIGH risk: explainability and an independent validation report are required.
        assertThat(code(post("/v1/ai/model-versions/" + v1 + "/submit-for-approval", "{}", DEV, 422))).isEqualTo("explainability_required");
        HttpResponse<String> patched = send("PATCH", "/v1/ai/model-versions/" + v1, """
                {"explainability_notes":"Top drivers: amount z-score, merchant risk, device age",
                 "validation_document_id":"%s"}""".formatted(UUID.randomUUID()), null, "\"1\"", TENANT, DEV);
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        JsonNode submitted = post("/v1/ai/model-versions/" + v1 + "/submit-for-approval", "{\"comment\":\"ready\"}", DEV, 200);
        assertThat(submitted.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(submitted.get("submitted_by").asString()).isEqualTo(DEV);
        HttpResponse<String> locked = send("PATCH", "/v1/ai/model-versions/" + v1, "{\"framework\":\"ONNX\"}", null,
                "\"" + submitted.get("row_version").asInt() + "\"", TENANT, DEV);
        assertThat(locked.statusCode()).isEqualTo(409);
        assertThat(code(post("/v1/ai/model-versions/" + v1 + "/approve", "{}", DEV, 422))).isEqualTo("four_eyes_violation");
        JsonNode approved = post("/v1/ai/model-versions/" + v1 + "/approve", "{\"comment\":\"MRM committee 2026-10\"}", RISK, 200);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("approved_by").asString()).isEqualTo(RISK);
        assertThat(code(post("/v1/ai/model-versions/" + v1 + "/approve", "{}", RISK, 409))).isEqualTo("invalid_transition");

        String v2 = post("/v1/ai/model-versions", versionBody("2.0.0", "\"artifact_sha256\":\"" + SHA + "\"", false), DEV, 201)
                .get("id").asString();
        assertThat(code(post("/v1/ai/model-versions/" + v2 + "/submit-for-approval", "{}", DEV, 422))).isEqualTo("bias_test_failed");
        String v3 = approvable("3.0.0");
        post("/v1/ai/model-versions/" + v3 + "/submit-for-approval", "{}", DEV, 200);
        JsonNode rejected = post("/v1/ai/model-versions/" + v3 + "/reject", "{\"reason\":\"KS below policy\"}", RISK, 200);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejection_reason").asString()).isEqualTo("KS below policy");
        assertThat(get("/v1/ai/models/" + model + "/versions", TENANT).get("data")).hasSize(3);
        assertThat(outboxCount("reporting.ml_model_version.approved")).isEqualTo(1);
        assertThat(outboxCount("reporting.ml_model_version.rejected")).isEqualTo(1);
    }

    @Test
    @Order(2)
    void deploymentsPromoteAndRollBackRestoringTheChampion() throws Exception {
        JsonNode d1 = post("/v1/ai/model-versions/" + v1 + "/deploy", "{\"environment\":\"PRODUCTION\",\"mode\":\"CHAMPION\",\"traffic_percent\":100}",
                RISK, 201);
        champion = d1.get("id").asString();
        assertThat(d1.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(d1.get("endpoint").asString()).contains("fraud-tx-1.0.0");
        assertThat(get("/v1/ai/models/" + model, TENANT).get("champion_version_id").asString()).isEqualTo(v1);
        assertThat(get("/v1/ai/model-versions/" + v1, TENANT).get("status").asString()).isEqualTo("DEPLOYED");
        assertThat(code(post("/v1/ai/model-versions/" + v1 + "/deploy", "{\"environment\":\"PRODUCTION\",\"mode\":\"SHADOW\"}", RISK, 409)))
                .isEqualTo("already_deployed");

        v4 = approved("4.0.0");
        assertThat(code(post("/v1/ai/model-versions/" + v4 + "/deploy", "{\"environment\":\"PRODUCTION\",\"mode\":\"CHAMPION\"}", RISK, 409)))
                .isEqualTo("champion_exists");
        assertThat(code(post("/v1/ai/model-versions/" + v4 + "/deploy", "{\"environment\":\"PRODUCTION\",\"mode\":\"SHADOW\",\"traffic_percent\":5}",
                RISK, 422))).isEqualTo("validation_failed");
        String shadow = post("/v1/ai/model-versions/" + v4 + "/deploy",
                "{\"environment\":\"PRODUCTION\",\"mode\":\"SHADOW\",\"thresholds\":{\"AUC\":0.7}}", RISK, 201).get("id").asString();
        JsonNode c = post("/v1/ai/deployments/" + shadow + "/promote", "{}", RISK, 200);
        assertThat(c.get("mode").asString()).isEqualTo("CANARY");
        assertThat(c.get("traffic_percent").asInt()).isEqualTo(10);
        assertThat(post("/v1/ai/deployments/" + shadow + "/promote", "{\"traffic_percent\":30}", RISK, 200).get("traffic_percent").asInt())
                .isEqualTo(30);
        assertThat(code(post("/v1/ai/deployments/" + shadow + "/promote", "{\"traffic_percent\":20}", RISK, 422))).isEqualTo("validation_failed");
        JsonNode promoted = post("/v1/ai/deployments/" + shadow + "/promote", "{\"comment\":\"canary healthy for 7 days\"}", RISK, 200);
        assertThat(promoted.get("mode").asString()).isEqualTo("CHAMPION");
        assertThat(promoted.get("traffic_percent").asInt()).isEqualTo(100);
        assertThat(promoted.get("previous_deployment_id").asString()).isEqualTo(champion);
        assertThat(get("/v1/ai/deployments/" + champion, TENANT).get("status").asString()).isEqualTo("RETIRED");
        assertThat(get("/v1/ai/models/" + model, TENANT).get("champion_version_id").asString()).isEqualTo(v4);
        assertThat(code(post("/v1/ai/deployments/" + shadow + "/retire", "{}", RISK, 409))).isEqualTo("champion_in_service");
        assertThat(code(post("/v1/ai/deployments/" + shadow + "/promote", "{}", RISK, 409))).isEqualTo("already_champion");

        JsonNode rolled = post("/v1/ai/deployments/" + shadow + "/rollback", "{\"reason\":\"false positives spiked\"}", RISK, 200);
        assertThat(rolled.get("status").asString()).isEqualTo("ROLLED_BACK");
        JsonNode restored = get("/v1/ai/deployments/" + champion, TENANT);
        assertThat(restored.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(restored.get("mode").asString()).isEqualTo("CHAMPION");
        assertThat(get("/v1/ai/models/" + model, TENANT).get("champion_version_id").asString()).isEqualTo(v1);
        assertThat(get("/v1/ai/models/" + model + "/deployments", TENANT).get("data")).hasSize(2);
        assertThat(outboxCount("reporting.model_deployment.rolled_back")).isEqualTo(1);
        assertThat(outboxCount("reporting.model_deployment.promoted")).isEqualTo(3);
        assertThat(code(post("/v1/ai/deployments/" + shadow + "/promote", "{}", RISK, 409))).isEqualTo("invalid_transition");
    }

    @Test
    @Order(3)
    void metricsAndDriftRaiseBreaches() throws Exception {
        JsonNode auc = post("/v1/ai/model-metrics", metricBody(champion, "AUC", "0.65", "0.70", "2026-10-01T00:00:00Z", "2026-10-01T12:00:00Z"),
                RISK, 201);
        assertThat(auc.get("breached").asBoolean()).isTrue();
        JsonNode psi = post("/v1/ai/model-metrics", metricBody(champion, "PSI", "0.1", null, "2026-10-01T00:00:00Z", "2026-10-01T12:00:00Z"),
                RISK, 201);
        assertThat(psi.get("breached").asBoolean()).isFalse();
        assertThat(new BigDecimal(psi.get("threshold").asString())).isEqualByComparingTo("0.25");
        assertThat(code(post("/v1/ai/model-metrics", metricBody(champion, "AUC", "0.8", null, "2026-10-02T00:00:00Z", "2026-10-01T00:00:00Z"),
                RISK, 422))).isEqualTo("validation_failed");

        JsonNode drift = post("/v1/ai/deployments/" + champion + "/drift", """
                {"window_start":"2026-10-01T00:00:00Z","window_end":"2026-10-01T23:00:00Z",
                 "features":[{"name":"txn_amount","expected":[10,20,30,40],"actual":[40,30,20,10]},
                             {"name":"device_age","expected":[25,25,25,25],"actual":[26,24,25,25]}]}""", RISK, 200);
        assertThat(drift.get("breached_count").asInt()).isEqualTo(1);
        assertThat(drift.get("features").get(0).get("stability").asString()).isEqualTo("SIGNIFICANT");
        assertThat(new BigDecimal(drift.get("features").get(0).get("psi").asString())).isEqualByComparingTo("0.912870");
        assertThat(drift.get("features").get(1).get("stability").asString()).isEqualTo("STABLE");
        assertThat(code(post("/v1/ai/deployments/" + champion + "/drift", """
                {"window_start":"2026-10-01T00:00:00Z","window_end":"2026-10-01T23:00:00Z",
                 "features":[{"name":"x","expected":[1,2],"actual":[1,2,3]}]}""", RISK, 422))).isEqualTo("validation_failed");
        assertThat(get("/v1/ai/deployments/" + champion + "/metrics", TENANT).get("data")).hasSize(4);
        assertThat(get("/v1/ai/model-metrics?breached=true", TENANT).get("data")).hasSize(2);
        assertThat(outboxCount("reporting.model_metric.breached")).isEqualTo(2);
    }

    @Test
    @Order(4)
    void monitoringJobAutoRollsBackBreachingCanariesOncePerDate() throws Exception {
        canary = post("/v1/ai/model-versions/" + v4 + "/deploy",
                "{\"environment\":\"STAGING\",\"mode\":\"CANARY\",\"traffic_percent\":20,\"auto_rollback\":true}", RISK, 201)
                .get("id").asString();
        post("/v1/ai/model-metrics", metricBody(canary, "ERROR_RATE", "0.09", null, "2026-10-01T00:00:00Z", "2026-10-01T06:00:00Z"), RISK, 201);
        HttpResponse<String> review = send("PATCH", "/v1/ai/models/" + model, "{\"next_review_date\":\"2026-09-30\"}", null,
                "\"" + get("/v1/ai/models/" + model, TENANT).get("row_version").asInt() + "\"", TENANT, RISK);
        assertThat(review.statusCode()).as(review.body()).isEqualTo(200);

        JsonNode job = post("/v1/ai/deployments/monitor?business_date=2026-10-01", null, RISK, 200);
        assertThat(job.get("status").asString()).as(job.toString()).isEqualTo("COMPLETED");
        assertThat(job.get("counts").get("auto_rolled_back").asInt()).isEqualTo(1);
        assertThat(job.get("counts").get("breached_deployments").asInt()).isEqualTo(2);
        assertThat(job.get("counts").get("models_due_for_review").asInt()).isEqualTo(1);
        assertThat(get("/v1/ai/deployments/" + canary, TENANT).get("status").asString()).isEqualTo("ROLLED_BACK");
        assertThat(get("/v1/ai/deployments/" + champion, TENANT).get("status").asString()).isEqualTo("ACTIVE"); // champions are never auto-rolled back
        assertThat(get("/v1/ai/models/" + model, TENANT).get("status").asString()).isEqualTo("UNDER_REVIEW");
        JsonNode replay = post("/v1/ai/deployments/monitor?business_date=2026-10-01", null, RISK, 200);
        assertThat(replay.get("replayed").asBoolean()).isTrue();
        assertThat(get("/v1/ai/job-runs?job=MODEL_MONITORING", TENANT).get("data")).hasSize(1);
        // A model under review cannot get new production deployments.
        assertThat(code(post("/v1/ai/model-versions/" + v4 + "/deploy", "{\"environment\":\"PRODUCTION\",\"mode\":\"CHALLENGER\"}", RISK, 422)))
                .isEqualTo("model_not_active");
        assertThat(code(post("/v1/ai/features", "{\"name\":\"txn_count_24h\",\"entity\":\"WALLET\",\"dtype\":\"INT\",\"source_dataset\":\"kafka.txn\"}",
                DEV, 422))).isEqualTo("validation_failed");
        assertThat(post("/v1/ai/features", "{\"name\":\"txn_count_24h\",\"entity\":\"CUSTOMER\",\"dtype\":\"INT\",\"source_dataset\":\"kafka.txn\","
                + "\"freshness_sla_seconds\":60}", DEV, 201).get("online").asBoolean()).isTrue();
    }

    @Test
    @Order(5)
    void copilotsAnswerWithCitationsRedactAndProposeActionsForHumanApproval() throws Exception {
        assertThat(code(post("/v1/ai/llm-policies", policyBody("[{\"name\":\"EVIL\",\"pattern\":\"(a+)+$\"}]"), DEV, 422)))
                .isEqualTo("invalid_redaction_pattern");
        policy = post("/v1/ai/llm-policies", policyBody("[{\"name\":\"CUSTOMER_NO\",\"pattern\":\"CUS-\\\\d{6}\"}]"), DEV, 201)
                .get("id").asString();
        assertThat(code(post("/v1/ai/copilots", copilotBody("AML-1", "internal-llm", "[{\"name\":\"File SAR\",\"mode\":\"WRITE\"}]"), DEV, 422)))
                .isEqualTo("validation_failed");
        copilot = post("/v1/ai/copilots", copilotBody("AML-1", "internal-llm", TOOLS), DEV, 201).get("id").asString();
        String blocked = post("/v1/ai/copilots", copilotBody("AML-2", "public-gpt", TOOLS), DEV, 201).get("id").asString();
        assertThat(code(post("/v1/ai/copilots/" + blocked + "/activate", "{}", RISK, 422))).isEqualTo("model_not_allowed");
        assertThat(code(post("/v1/ai/copilots/" + copilot + "/ask", askBody("hello"), DEV, 409))).isEqualTo("copilot_not_active");
        assertThat(post("/v1/ai/copilots/" + copilot + "/activate", "{\"comment\":\"approved by AI governance\"}", RISK, 200)
                .get("status").asString()).isEqualTo("ACTIVE");

        String prompt = "Customer CUS-123456 (ada.obi@example.com) made many cash deposits below the threshold. "
                + "Is this structuring, and should we file a SAR?";
        JsonNode turn = post("/v1/ai/copilots/" + copilot + "/ask", askBody(prompt), DEV, 201);
        proposal = turn.get("id").asString();
        assertThat(turn.get("outcome").asString()).isEqualTo("ACTION_PROPOSED");
        assertThat(turn.get("prompt_redacted").asString()).contains("[CUSTOMER_NO]").contains("[EMAIL]")
                .doesNotContain("ada.obi").doesNotContain("CUS-123456");
        assertThat(turn.get("prompt_sha256").asString()).isEqualTo(sha256(prompt));
        assertThat(turn.get("response_redacted").asString()).contains("[1]").contains("Proposed action: file_sar");
        assertThat(turn.get("citations").get(0).get("source_id").asString()).isEqualTo("case-77");
        assertThat(turn.get("tool_calls").toString()).contains("\"status\":\"PROPOSED\"").contains("file_sar");
        assertThat(turn.get("redactions").get("OUT_OF_SCOPE_CONTEXT").asInt()).isEqualTo(1);
        assertThat(turn.get("redactions").get("PAN").asInt()).isEqualTo(1);
        assertThat(turn.get("input_tokens").asInt()).isPositive();
        assertThat(turn.get("cost_currency").asString()).isEqualTo("USD");
        assertThat(turn.toString()).doesNotContain("4111 1111 1111 1111");

        assertThat(code(post("/v1/ai/copilot-interactions/" + proposal + "/approve-action", "{}", DEV, 422))).isEqualTo("four_eyes_violation");
        JsonNode decision = post("/v1/ai/copilot-interactions/" + proposal + "/approve-action", "{\"comment\":\"SAR warranted\"}", RISK, 201);
        assertThat(decision.get("outcome").asString()).isEqualTo("ACTION_APPROVED");
        assertThat(decision.get("parent_interaction_id").asString()).isEqualTo(proposal);
        assertThat(decision.get("approved_by").asString()).isEqualTo(RISK);
        assertThat(code(post("/v1/ai/copilot-interactions/" + proposal + "/reject-action", "{\"reason\":\"late\"}", RISK, 409)))
                .isEqualTo("action_already_decided");
        assertThat(get("/v1/ai/copilot-interactions/" + proposal, TENANT).get("outcome").asString()).isEqualTo("ACTION_PROPOSED");
        assertThat(get("/v1/ai/copilot-interactions?parent_interaction_id=" + proposal, TENANT).get("data")).hasSize(1);
        assertThat(code(post("/v1/ai/copilot-interactions/" + decision.get("id").asString() + "/approve-action", "{}", DEV, 409)))
                .isEqualTo("no_proposed_action");

        assertThat(code(post("/v1/ai/copilot-interactions/" + proposal + "/feedback", "{\"rating\":6}", DEV, 422))).isEqualTo("validation_failed");
        assertThat(post("/v1/ai/copilot-interactions/" + proposal + "/feedback", "{\"rating\":4,\"comment\":\"useful\"}", DEV, 201)
                .get("given_by").asString()).isEqualTo(DEV);
        assertThat(code(post("/v1/ai/copilot-interactions/" + proposal + "/feedback", "{\"rating\":5}", DEV, 409))).isEqualTo("feedback_exists");

        JsonNode refused = post("/v1/ai/copilots/" + copilot + "/ask", askBody("What is the weather in Lagos tomorrow?"), DEV, 201);
        assertThat(refused.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(outboxCount("reporting.copilot_interaction.recorded")).isEqualTo(2);
        assertThat(outboxCount("reporting.copilot_action.approved")).isEqualTo(1);

        // Immutable audit: the database rejects updates of interactions.
        Exception blockedUpdate = null;
        try {
            asTenant(() -> jdbc.sql("update copilot_interaction set outcome = 'ANSWERED' where id = ?::uuid").param(proposal).update());
        } catch (Exception e) {
            blockedUpdate = e;
        }
        assertThat(blockedUpdate).isNotNull();
    }

    @Test
    @Order(6)
    void gatewayEnforcesTokenCapsBudgetsAndReportsUsage() throws Exception {
        assertThat(code(post("/v1/ai/copilots/" + copilot + "/ask", askBody("x".repeat(9000)), DEV, 422))).isEqualTo("token_limit_exceeded");
        JsonNode usage = get("/v1/ai/llm-policies/" + policy + "/usage", TENANT);
        long used = usage.get("total_tokens").asLong();
        assertThat(used).isPositive();
        assertThat(usage.get("interactions").asInt()).isEqualTo(3);
        assertThat(usage.get("by_copilot").get(0).get("code").asString()).isEqualTo("AML-1");

        JsonNode p = get("/v1/ai/llm-policies/" + policy, TENANT);
        HttpResponse<String> shrink = send("PATCH", "/v1/ai/llm-policies/" + policy, "{\"monthly_token_budget\":" + (used + 5) + "}", null,
                "\"" + p.get("row_version").asInt() + "\"", TENANT, DEV);
        assertThat(shrink.statusCode()).as(shrink.body()).isEqualTo(200);
        JsonNode over = post("/v1/ai/copilots/" + copilot + "/ask", askBody("Summarise the structuring alert for case 77 please"), DEV, 201);
        assertThat(over.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(over.get("response_redacted").asString()).contains("budget");
        assertThat(over.get("input_tokens").asInt()).isZero();
        assertThat(outboxCount("reporting.llm_budget.exceeded")).isEqualTo(1);
        JsonNode after = get("/v1/ai/llm-policies/" + policy + "/usage", TENANT);
        assertThat(after.get("refused").asInt()).isEqualTo(2);
        assertThat(after.get("budget_exceeded").asBoolean()).isFalse();

        JsonNode preview = post("/v1/ai/llm-policies/" + policy + "/redact", "{\"text\":\"card 4111111111111111 for CUS-654321\"}", DEV, 200);
        assertThat(preview.get("redacted").asString()).isEqualTo("card [PAN] for [CUSTOMER_NO]");
        assertThat(post("/v1/ai/copilots/" + copilot + "/disable", "{\"reason\":\"quarterly review\"}", RISK, 200).get("status").asString())
                .isEqualTo("DISABLED");
        assertThat(code(post("/v1/ai/copilots/" + copilot + "/ask", askBody("hello"), DEV, 409))).isEqualTo("copilot_not_active");
    }

    @Test
    @Order(7)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/ai/models/" + model, null, null, null, OTHER_TENANT, DEV).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/v1/ai/copilot-interactions", null, null, null, OTHER_TENANT, DEV).body()).contains("\"data\":[]");
        HttpResponse<String> r = send("POST", "/v1/ai/copilot-interactions/" + proposal + "/reject-action", "{\"reason\":\"x\"}",
                key(), null, OTHER_TENANT, RISK);
        assertThat(r.statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ fixtures

    static final String TOOLS = """
            [{"name":"lookup_customer","mode":"READ","keywords":["customer profile"]},
             {"name":"file_sar","mode":"WRITE","keywords":["file a sar","suspicious activity report"]}]""";

    private String versionBody(String label, String sha, boolean biasPasses) {
        return """
                {"ml_model_id":"%s","model_version":"%s","framework":"XGBOOST","artifact_uri":"s3://mlflow/fraud/%s",%s,
                 "features":["txn_amount","merchant_risk","device_age"],"metrics":{"AUC":0.91,"KS":0.52},
                 "bias_test_results":[{"attribute":"gender","metric":"DISPARATE_IMPACT","value":0.93,"threshold":0.8,"passed":%s}]}"""
                .formatted(model, label, label, sha, biasPasses);
    }

    private String approvable(String label) throws Exception {
        String v = post("/v1/ai/model-versions", versionBody(label, "\"artifact_sha256\":\"" + SHA + "\"", true)
                .replace("\"features\"", "\"explainability_notes\":\"SHAP summary attached\",\"validation_document_id\":\""
                        + UUID.randomUUID() + "\",\"features\""), DEV, 201).get("id").asString();
        return v;
    }

    private String approved(String label) throws Exception {
        String v = approvable(label);
        post("/v1/ai/model-versions/" + v + "/submit-for-approval", "{}", DEV, 200);
        post("/v1/ai/model-versions/" + v + "/approve", "{}", RISK, 200);
        return v;
    }

    private String metricBody(String deployment, String name, String value, String threshold, String from, String to) {
        return "{\"model_deployment_id\":\"%s\",\"metric_name\":\"%s\",\"metric_value\":\"%s\"%s,\"window_start\":\"%s\",\"window_end\":\"%s\"}"
                .formatted(deployment, name, value, threshold == null ? "" : ",\"threshold\":\"" + threshold + "\"", from, to);
    }

    private String policyBody(String patterns) {
        return """
                {"name":"Default LLM policy","tenant_opt_in":true,"allowed_models":["internal-llm"],"self_hosted_only":true,
                 "pii_redaction":true,"redaction_patterns":%s,"max_tokens_per_request":2000,"monthly_token_budget":1000000,
                 "cost_per_1k_tokens":"0.002","cost_currency":"USD"}""".formatted(patterns);
    }

    private String copilotBody(String code, String alias, String tools) {
        return """
                {"code":"%s","name":"AML investigator","copilot_type":"AML_INVESTIGATION","system_prompt_ref":"prompts/aml/v3",
                 "model_alias":"%s","allowed_tools":%s,"data_scopes":["AML_CASE","POLICY"],"llm_gateway_policy_id":"%s"}"""
                .formatted(code, alias, tools, policy);
    }

    private String askBody(String prompt) {
        return """
                {"prompt":%s,"session_id":"s-1","subject_type":"AML_CASE","subject_id":"%s",
                 "context":[
                   {"id":"case-77","type":"AML_CASE","title":"Alert 77",
                    "text":"Customer made 14 cash deposits just below the reporting threshold within 10 days. Card 4111 1111 1111 1111 was used at the branch. The pattern is consistent with structuring."},
                   {"id":"pol-1","type":"POLICY","title":"CTR policy","text":"Cash deposits above 10000 require a currency transaction report."},
                   {"id":"news-1","type":"NEWS","title":"Press","text":"Structuring cases rose this year according to the press."}]}"""
                .formatted(json.writeValueAsString(prompt), UUID.randomUUID());
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private <T> T asTenant(Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", body);
    }

    private long outboxCount(String type) throws Exception {
        return asTenant(() -> jdbc.sql("select count(*) from outbox where event_type = ? and tenant_id = ?::uuid")
                .params(type, TENANT).query(Long.class).single());
    }

    private static String code(JsonNode problem) {
        return problem.path("code").asString();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, key(), null, TENANT, user);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, null, tenant, DEV);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String ifMatch,
                                      String tenant, String user) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
