package io.meridian.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
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
 * Fraud scoring end to end over HTTP against PostgreSQL 18 with row-level security: policies, maker-checker rule
 * activation, lists, devices, velocity, alerts (assign / contact / escalate / resolve), labels, explanations and
 * backtests. Case management and notifications are stubbed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FraudFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000f1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000f2";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final String customer = UUID.randomUUID().toString();
    String reviewRule;
    String quietScore;
    String reviewScore;
    String alertId;

    @BeforeAll
    void setUp() throws Exception {
        post("/v1/risk/fraud/decision-policies", """
                {"subject_type":"PAYMENT","step_up_threshold":400,"review_threshold":600,"block_threshold":850}""", 201, "alice");
    }

    @Test
    @Order(1)
    void quietPaymentIsAllowedAndReplayIsIdempotent() throws Exception {
        String body = evaluateBody(UUID.randomUUID().toString(), "45.00", "dev-quiet-1", "{}");
        HttpResponse<String> first = send("POST", "/v1/risk/fraud/scores/evaluate", body, "eval-quiet", TENANT, "svc");
        assertThat(first.statusCode()).isEqualTo(200);
        JsonNode r = json.readTree(first.body());
        assertThat(r.get("decision").asString()).isEqualTo("ALLOW");
        assertThat(r.get("score").asInt()).isLessThan(400);
        assertThat(r.get("fraud_alert_id").isNull()).isTrue();
        assertThat(r.get("shadow_decision").asString()).isNotBlank();
        quietScore = r.get("fraud_score_id").asString();

        HttpResponse<String> again = send("POST", "/v1/risk/fraud/scores/evaluate", body, "eval-quiet", TENANT, "svc");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(again.body()).get("fraud_score_id").asString()).isEqualTo(quietScore);

        JsonNode stored = get("/v1/risk/fraud/scores/" + quietScore, TENANT);
        assertThat(stored.get("reason_codes").isArray()).isTrue();
        assertThat(stored.get("features").get("device").get("is_new").asBoolean()).isTrue();
        assertThat(get("/v1/risk/fraud/scores?customer_id=" + customer, TENANT).get("data")).hasSize(1);
    }

    @Test
    @Order(2)
    void ruleNeedsAValidExpressionAndASecondUserToActivate() throws Exception {
        JsonNode bad = post("/v1/risk/fraud/rules", """
                {"code":"BAD","name":"Bad","subject_type":"PAYMENT","expression":"amount >> 5","action":"REVIEW"}""",
                422, "alice");
        assertThat(bad.get("code").asString()).isEqualTo("invalid_expression");

        JsonNode rule = post("/v1/risk/fraud/rules", """
                {"code":"LARGE_FROM_NEW_DEVICE","name":"Large amount from a new device","subject_type":"PAYMENT",
                 "expression":"amount >= 2000 && device.is_new && counterparty_country in ['NG', 'KP']",
                 "action":"REVIEW","score_adjustment":150,"priority":10,"tags":["ato","app-scam"]}""", 201, "alice");
        reviewRule = rule.get("id").asString();
        assertThat(rule.get("status").asString()).isEqualTo("DRAFT");
        assertThat(rule.get("tags")).hasSize(2);

        JsonNode self = post("/v1/risk/fraud/rules/" + reviewRule + "/activate", "{\"mode\":\"ACTIVE\"}", 422, "alice");
        assertThat(self.get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode active = post("/v1/risk/fraud/rules/" + reviewRule + "/activate", "{\"mode\":\"ACTIVE\"}", 200, "bob");
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(active.get("approved_by").asString()).isEqualTo("bob");

        HttpResponse<String> edit = send("PATCH", "/v1/risk/fraud/rules/" + reviewRule, "{\"score_adjustment\":10}",
                null, TENANT, "alice", active.get("row_version").asString());
        assertThat(edit.statusCode()).isEqualTo(409);

        JsonNode test = post("/v1/risk/fraud/rules/test-expression", """
                {"expression":"amount > 10 && on_list('X')","sample_event":{"amount":"25","lists":{"hits":["X"]}}}""",
                200, "alice");
        assertThat(test.get("valid").asBoolean()).isTrue();
        assertThat(test.get("matched").asBoolean()).isTrue();
        JsonNode invalid = post("/v1/risk/fraud/rules/test-expression",
                "{\"expression\":\"amount >\",\"sample_event\":{}}", 200, "alice");
        assertThat(invalid.get("valid").asBoolean()).isFalse();
    }

    @Test
    @Order(3)
    void ruleHitTriggersReviewAlertAndExplanation() throws Exception {
        JsonNode r = post("/v1/risk/fraud/scores/evaluate", evaluateBody(UUID.randomUUID().toString(), "2500.00",
                "dev-new-2", "{\"is_rooted\":true}"), 200, "svc");
        assertThat(r.get("decision").asString()).isIn("REVIEW", "BLOCK");
        assertThat(r.get("reason_codes").toString()).contains("RULE:LARGE_FROM_NEW_DEVICE");
        assertThat(r.get("rule_hits")).anySatisfy(h -> assertThat(h.get("code").asString()).isEqualTo("LARGE_FROM_NEW_DEVICE"));
        assertThat(r.get("rule_score").asInt()).isEqualTo(150);
        reviewScore = r.get("fraud_score_id").asString();
        alertId = r.get("fraud_alert_id").asString();
        assertThat(alertId).isNotBlank();

        JsonNode alert = get("/v1/risk/fraud/alerts/" + alertId, TENANT);
        assertThat(alert.get("status").asString()).isEqualTo("OPEN");
        assertThat(alert.get("sla_due_at").asString()).isNotBlank();
        assertThat(alert.get("reason_codes").toString()).contains("RULE:LARGE_FROM_NEW_DEVICE");

        JsonNode hits = get("/v1/risk/fraud/scores/" + reviewScore + "/rule-hits", TENANT).get("data");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).get("matched_values").get("amount").asString()).startsWith("2500");
        assertThat(get("/v1/risk/fraud/rules/" + reviewRule + "/hits", TENANT).get("data")).hasSize(1);

        JsonNode ex = get("/v1/risk/fraud/scores/" + reviewScore + "/explanation", TENANT);
        assertThat(ex.get("summary").asString()).contains("Decision");
        assertThat(ex.get("feature_contributions").size()).isGreaterThan(0);
        assertThat(ex.get("rule_hits").get(0).get("name").asString()).isEqualTo("Large amount from a new device");
    }

    @Test
    @Order(4)
    void velocityBuildsUpAcrossEvaluations() throws Exception {
        String cust = UUID.randomUUID().toString();
        JsonNode last = null;
        Integer firstModelScore = null;
        for (int i = 0; i < 6; i++) {
            last = post("/v1/risk/fraud/scores/evaluate", """
                    {"subject_type":"PAYMENT","subject_id":"%s","customer_id":"%s","amount":"20.00","currency":"USD",
                     "channel":"MOBILE_APP","device_fingerprint":"dev-velo","ip_address":"10.0.0.5"}"""
                    .formatted(UUID.randomUUID(), cust), 200, "svc");
            if (firstModelScore == null) {
                firstModelScore = last.get("model_score").asInt();
            }
        }
        JsonNode stored = get("/v1/risk/fraud/scores/" + last.get("fraud_score_id").asString(), TENANT);
        assertThat(stored.get("features").get("velocity").get("count_1h").asInt()).isEqualTo(5);
        assertThat(stored.get("features").get("device").get("is_new").asBoolean()).isFalse();
        assertThat(stored.get("model_score").asInt()).isGreaterThan(firstModelScore);
    }

    @Test
    @Order(5)
    void blockListAndBlockedDeviceBlock() throws Exception {
        String list = post("/v1/risk/fraud/lists", """
                {"code":"MULE_ACCOUNTS","name":"Known mule accounts","list_type":"BLOCK","entity_type":"BENEFICIARY_ACCOUNT"}""",
                201, "alice").get("id").asString();
        JsonNode entry = post("/v1/risk/fraud/lists/" + list + "/entries",
                "{\"value\":\"GB29 NWBK 6016 1331 9268 19\",\"reason\":\"confirmed mule\",\"source\":\"CONSORTIUM\"}", 200, "alice");
        assertThat(entry.get("value_masked").asString()).endsWith("6819").doesNotContain("NWBK");
        assertThat(entry.get("value_hash").asString()).hasSize(64);
        JsonNode lookup = post("/v1/risk/fraud/lists/" + list + "/lookup", "{\"value\":\"gb29nwbk60161331926819\"}", 200, "alice");
        assertThat(lookup.get("on_list").asBoolean()).isTrue();
        assertThat(post("/v1/risk/fraud/lists/" + list + "/lookup", "{\"value\":\"GB00\"}", 200, "alice")
                .get("on_list").asBoolean()).isFalse();

        JsonNode blocked = post("/v1/risk/fraud/scores/evaluate", """
                {"subject_type":"PAYMENT","subject_id":"%s","customer_id":"%s","amount":"10.00","currency":"USD",
                 "context":{"beneficiary_account":"GB29NWBK60161331926819"}}""".formatted(UUID.randomUUID(), customer), 200, "svc");
        assertThat(blocked.get("decision").asString()).isEqualTo("BLOCK");
        assertThat(blocked.get("reason_codes").toString()).contains("BLOCK_LIST:MULE_ACCOUNTS");
        assertThat(get("/v1/risk/fraud/alerts/" + blocked.get("fraud_alert_id").asString(), TENANT)
                .get("severity").asString()).isEqualTo("CRITICAL");

        String device = get("/v1/risk/fraud/devices?fingerprint_hash=dev-quiet-1", TENANT).get("data").get(0).get("id").asString();
        post("/v1/risk/fraud/devices/" + device + "/block", "{\"reason\":\"SIM swap confirmed\"}", 200, "alice");
        assertThat(post("/v1/risk/fraud/devices/" + device + "/trust", "{\"reason\":\"x\"}", 409, "alice")
                .get("code").asString()).isEqualTo("device_blocked");
        JsonNode viaDevice = post("/v1/risk/fraud/scores/evaluate",
                evaluateBody(UUID.randomUUID().toString(), "5.00", "dev-quiet-1", "{}"), 200, "svc");
        assertThat(viaDevice.get("decision").asString()).isEqualTo("BLOCK");
        assertThat(viaDevice.get("reason_codes").toString()).contains("DEVICE_BLOCKED");
        post("/v1/risk/fraud/devices/" + device + "/unblock", "{\"reason\":\"customer verified in branch\"}", 200, "alice");
        JsonNode trusted = post("/v1/risk/fraud/devices/" + device + "/trust", "{\"reason\":\"strong auth\"}", 200, "alice");
        assertThat(trusted.get("trusted").asBoolean()).isTrue();
    }

    @Test
    @Order(6)
    void alertWorkflowAssignContactEscalateResolve() throws Exception {
        UUID caseId = UUID.randomUUID();
        when(serviceClient.post(eq("notification-service"), eq("/v1/notifications/send-templated"), any(), anyString()))
                .thenReturn(json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"QUEUED\"}"));
        when(serviceClient.post(eq("case-management-service"), eq("/v1/risk/cases"), any(), anyString()))
                .thenReturn(json.readTree("""
                        {"id":"%s","case_number":"AML-2026-000001","case_type":"FRAUD","status":"OPEN","priority":"HIGH",
                         "title":"Fraud alert","opened_at":"2026-10-06T10:00:00Z","created_at":"2026-10-06T10:00:00Z",
                         "updated_at":"2026-10-06T10:00:00Z","row_version":1}""".formatted(caseId)));
        when(serviceClient.post(eq("case-management-service"), eq("/v1/risk/cases/" + caseId + "/alerts"), any(), anyString()))
                .thenReturn(json.readTree("{\"id\":\"" + UUID.randomUUID() + "\"}"));

        JsonNode assigned = post("/v1/risk/fraud/alerts/" + alertId + "/assign", "{\"assigned_to\":\"analyst-7\"}", 200, "lead");
        assertThat(assigned.get("status").asString()).isEqualTo("IN_REVIEW");
        JsonNode contacted = post("/v1/risk/fraud/alerts/" + alertId + "/contact-customer", "{\"channel\":\"SMS\"}", 200, "analyst-7");
        assertThat(contacted.get("customer_contacted").asBoolean()).isTrue();
        assertThat(contacted.get("notification_id").asString()).isNotBlank();
        assertThat(post("/v1/risk/fraud/alerts/" + alertId + "/contact-customer", "{\"channel\":\"FAX\"}", 422, "analyst-7")
                .get("code").asString()).isEqualTo("invalid_channel");

        JsonNode amlCase = post("/v1/risk/fraud/alerts/" + alertId + "/escalate",
                "{\"case_type\":\"FRAUD\",\"reason\":\"APP scam pattern\"}", 200, "analyst-7");
        assertThat(amlCase.get("id").asString()).isEqualTo(caseId.toString());
        JsonNode escalated = get("/v1/risk/fraud/alerts/" + alertId, TENANT);
        assertThat(escalated.get("status").asString()).isEqualTo("ESCALATED");
        assertThat(escalated.get("aml_case_id").asString()).isEqualTo(caseId.toString());
        assertThat(post("/v1/risk/fraud/alerts/" + alertId + "/assign", "{\"assigned_to\":\"x\"}", 409, "lead")
                .get("code").asString()).isEqualTo("invalid_transition");

        JsonNode needsTypology = post("/v1/risk/fraud/alerts/" + alertId + "/resolve",
                "{\"status\":\"CONFIRMED_FRAUD\",\"resolution\":\"customer confirmed scam\"}", 422, "analyst-7");
        assertThat(needsTypology.get("code").asString()).isEqualTo("typology_required");
        JsonNode resolved = post("/v1/risk/fraud/alerts/" + alertId + "/resolve", """
                {"status":"CONFIRMED_FRAUD","typology":"AUTHORISED_PUSH_PAYMENT_SCAM","resolution":"customer confirmed scam"}""",
                200, "analyst-7");
        assertThat(resolved.get("status").asString()).isEqualTo("CONFIRMED_FRAUD");
        assertThat(resolved.get("resolved_by").asString()).isEqualTo("analyst-7");
        JsonNode labels = get("/v1/risk/fraud/feedback?fraud_score_id=" + reviewScore, TENANT).get("data");
        assertThat(labels).singleElement().satisfies(l -> assertThat(l.get("label").asString()).isEqualTo("FRAUD"));
    }

    @Test
    @Order(7)
    void labelsAndBacktest() throws Exception {
        JsonNode fb = post("/v1/risk/fraud/scores/" + quietScore + "/feedback",
                "{\"label\":\"NOT_FRAUD\",\"source\":\"CHARGEBACK\"}", 200, "ops");
        assertThat(fb.get("label").asString()).isEqualTo("NOT_FRAUD");
        assertThat(post("/v1/risk/fraud/feedback", """
                {"fraud_score_id":"%s","label":"FRAUD","source":"ANALYST","loss_amount":"5","recovered_amount":"9","currency":"USD",
                 "reported_at":"2026-10-01T00:00:00Z"}""".formatted(quietScore), 422, "ops").get("code").asString())
                .isEqualTo("invalid_amount");

        String from = OffsetDateTime.now().minusHours(1).toString();
        String to = OffsetDateTime.now().plusMinutes(5).toString();
        JsonNode bt = post("/v1/risk/fraud/rules/" + reviewRule + "/backtest",
                "{\"from_ts\":\"%s\",\"to_ts\":\"%s\"}".formatted(from, to), 200, "alice");
        assertThat(bt.get("evaluated").asInt()).isGreaterThanOrEqualTo(9);
        assertThat(bt.get("hits").asInt()).isEqualTo(1);
        assertThat(bt.get("true_positives").asInt()).isEqualTo(1);
        assertThat(bt.get("precision").asDouble()).isEqualTo(1.0);

        JsonNode off = post("/v1/risk/fraud/rules/" + reviewRule + "/deactivate", "{\"reason\":\"tuning\"}", 200, "alice");
        assertThat(off.get("status").asString()).isEqualTo("DISABLED");
        JsonNode after = post("/v1/risk/fraud/scores/evaluate", evaluateBody(UUID.randomUUID().toString(), "2500.00",
                "dev-new-9", "{}"), 200, "svc");
        assertThat(after.get("rule_hits")).isEmpty();
    }

    @Test
    @Order(8)
    void validationAndTenantIsolation() throws Exception {
        JsonNode neg = post("/v1/risk/fraud/scores/evaluate", """
                {"subject_type":"PAYMENT","subject_id":"%s","amount":"-1","currency":"USD"}""".formatted(UUID.randomUUID()),
                422, "svc");
        assertThat(neg.get("code").asString()).isEqualTo("invalid_amount");
        JsonNode thresholds = post("/v1/risk/fraud/decision-policies", """
                {"subject_type":"LOGIN","step_up_threshold":700,"review_threshold":600,"block_threshold":900}""", 422, "alice");
        assertThat(thresholds.get("code").asString()).isEqualTo("invalid_thresholds");

        HttpResponse<String> other = send("GET", "/v1/risk/fraud/scores/" + quietScore, null, null, OTHER_TENANT, "x");
        assertThat(other.statusCode()).isEqualTo(404);
        assertThat(get("/v1/risk/fraud/alerts", OTHER_TENANT).get("data")).isEmpty();
        assertThat(get("/v1/risk/fraud/rules", OTHER_TENANT).get("data")).isEmpty();
    }

    @Test
    @Order(9)
    void evaluateLatencyIsWithinBudget() throws Exception {
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            long t = System.nanoTime();
            post("/v1/risk/fraud/scores/evaluate", evaluateBody(UUID.randomUUID().toString(), "12.00", "dev-perf", "{}"), 200, "svc");
            times.add((System.nanoTime() - t) / 1_000_000);
        }
        times.sort(Long::compare);
        System.out.println("fraud evaluate latency ms p50=" + times.get(20) + " p95=" + times.get(37) + " max=" + times.get(39));
        // generous bound for a shared CI host; the service records its own latency_ms per evaluation
        assertThat(times.get((int) (times.size() * 0.95) - 1)).isLessThan(250L);
    }

    // ------------------------------------------------------------------ helpers

    private String evaluateBody(String subject, String amount, String device, String deviceAttrs) {
        return """
                {"subject_type":"PAYMENT","subject_id":"%s","customer_id":"%s","amount":"%s","currency":"USD",
                 "channel":"MOBILE_APP","counterparty_country":"NG","ip_country":"GB","device_fingerprint":"%s",
                 "device":%s,"ip_address":"81.2.69.160","session_id":"s-1"}""".formatted(subject, customer, amount, device, deviceAttrs);
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), TENANT, user);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant, "reader");
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String key, String tenant, String user)
            throws Exception {
        return send(method, path, body, key, tenant, user, null);
    }

    private HttpResponse<String> send(String method, String path, String body, String key, String tenant, String user,
                                      String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenant)
                .header("X-User", user)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
