package io.meridian.transactionmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
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
 * Transaction monitoring end to end: maker-checker scenario versions, streaming detection on ingest (structuring,
 * fan-in with mule flagging), de-duplication, alert triage and escalation, batch runs and backtests, the risk graph
 * and customer risk ratings with four-eyes overrides.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransactionMonitoringFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000a1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000a2";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final String structurer = UUID.randomUUID().toString();
    final String mule = UUID.randomUUID().toString();
    final String sleeper = UUID.randomUUID().toString();
    final String muleAccount = UUID.randomUUID().toString();
    String structuring;
    String structuringAlert;
    String fanInAlert;

    @BeforeAll
    void scenarios() throws Exception {
        structuring = scenario("STRUCT_10K", "STRUCTURING", "STREAMING", """
                {"parameters":{"threshold":10000,"margin_pct":10,"cash_only":true},"lookback_days":7,"min_count":3,
                 "risk_weight":"1.0","tuning_rationale":"Below-the-line testing on 2025 data: 3 deposits in 7 days"}""");
        scenario("FAN_IN_5", "FAN_IN", "STREAMING", """
                {"parameters":{"min_counterparties":5},"lookback_days":3,"risk_weight":"1.2",
                 "tuning_rationale":"Mule recruitment pattern"}""");
        scenario("DORMANT_90", "DORMANT_REACTIVATION", "BATCH", """
                {"parameters":{"dormant_days":90},"lookback_days":7,"min_amount":"5000","tuning_rationale":"EBA guideline"}""");
    }

    private String scenario(String code, String type, String mode, String version) throws Exception {
        String id = post("/v1/risk/monitoring/scenarios", """
                {"code":"%s","name":"%s","scenario_type":"%s","run_mode":"%s","owner":"mlro-team"}""".formatted(code, code, type, mode),
                201, "alice").get("id").asString();
        JsonNode v = post("/v1/risk/monitoring/scenarios/" + id + "/versions", version, 200, "alice");
        assertThat(v.get("scenario_version").asInt()).isEqualTo(1);
        assertThat(v.get("status").asString()).isEqualTo("DRAFT");
        assertThat(post("/v1/risk/monitoring/scenarios/" + id + "/activate", "{\"scenario_version\":1}", 422, "alice")
                .get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode active = post("/v1/risk/monitoring/scenarios/" + id + "/activate", "{\"scenario_version\":1}", 200, "bob");
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(active.get("active_version").asInt()).isEqualTo(1);
        return id;
    }

    @Test
    @Order(1)
    void scenarioValidation() throws Exception {
        String custom = post("/v1/risk/monitoring/scenarios", """
                {"code":"CUST","name":"Custom","scenario_type":"CUSTOM"}""", 201, "alice").get("id").asString();
        assertThat(post("/v1/risk/monitoring/scenarios/" + custom + "/versions",
                "{\"parameters\":{},\"lookback_days\":7,\"tuning_rationale\":\"x\"}", 422, "alice").get("code").asString())
                .isEqualTo("invalid_parameters");
        String hrj = post("/v1/risk/monitoring/scenarios", """
                {"code":"HRJ","name":"High risk countries","scenario_type":"HIGH_RISK_JURISDICTION"}""", 201, "alice").get("id").asString();
        assertThat(post("/v1/risk/monitoring/scenarios/" + hrj + "/versions",
                "{\"parameters\":{},\"lookback_days\":30,\"tuning_rationale\":\"x\"}", 422, "alice").get("code").asString())
                .isEqualTo("invalid_parameters");
        assertThat(get("/v1/risk/monitoring/scenarios/" + structuring + "/versions", TENANT).get("data")).hasSize(1);
    }

    @Test
    @Order(2)
    void structuringIsDetectedOnIngestOnce() throws Exception {
        String account = UUID.randomUUID().toString();
        String batch = "{\"transactions\":[" + String.join(",",
                leg(structurer, account, "CREDIT", "9300", true, null, Duration.ofDays(3)),
                leg(structurer, account, "CREDIT", "9450", true, null, Duration.ofDays(2)),
                leg(structurer, account, "DEBIT", "120", false, "Grocer", Duration.ofDays(2)),
                leg(structurer, account, "CREDIT", "9800", true, null, Duration.ofDays(1))) + "]}";
        JsonNode r = post("/v1/risk/monitoring/transactions/ingest", batch, 200, "ledger-feed");
        assertThat(r.get("inserted").asInt()).isEqualTo(4);
        assertThat(r.get("alerts_raised")).hasSize(1);
        assertThat(r.get("alerts_raised").get(0).get("scenario_code").asString()).isEqualTo("STRUCT_10K");
        structuringAlert = r.get("alerts_raised").get(0).get("tm_alert_id").asString();

        JsonNode again = post("/v1/risk/monitoring/transactions/ingest", batch, 200, "ledger-feed");
        assertThat(again.get("inserted").asInt()).isZero();
        assertThat(again.get("duplicates").asInt()).isEqualTo(4);
        JsonNode later = post("/v1/risk/monitoring/transactions",
                leg(structurer, account, "CREDIT", "9700", true, null, Duration.ofHours(2)), 201, "ledger-feed");
        assertThat(later.get("is_cash").asBoolean()).isTrue();
        assertThat(get("/v1/risk/monitoring/alerts?customer_id=" + structurer, TENANT).get("data")).hasSize(1);

        JsonNode alert = get("/v1/risk/monitoring/alerts/" + structuringAlert, TENANT);
        assertThat(alert.get("status").asString()).isEqualTo("OPEN");
        assertThat(alert.get("transaction_count").asInt()).isEqualTo(3);
        assertThat(new BigDecimal(alert.get("total_amount").asString())).isEqualByComparingTo("28550");
        assertThat(alert.get("account_id").asString()).isEqualTo(account);
        assertThat(alert.get("reason").get("deposits_in_band").asInt()).isEqualTo(3);
        JsonNode txns = get("/v1/risk/monitoring/alerts/" + structuringAlert + "/transactions", TENANT).get("data");
        assertThat(txns).hasSize(3);
        BigDecimal contributions = BigDecimal.ZERO;
        for (JsonNode t : txns) {
            contributions = contributions.add(new BigDecimal(t.get("contribution").asString()));
        }
        assertThat(contributions).isBetween(new BigDecimal("0.999"), new BigDecimal("1.001"));
    }

    @Test
    @Order(3)
    void fanInFlagsAMuleInTheGraph() throws Exception {
        List<String> legs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            legs.add(leg(mule, muleAccount, "CREDIT", "450", false, "GB00PAYER" + i, Duration.ofHours(20 - i)));
        }
        legs.add(leg(mule, muleAccount, "DEBIT", "2100", false, "CRYPTO-EXCHANGE", Duration.ofHours(2)));
        JsonNode r = post("/v1/risk/monitoring/transactions/ingest", "{\"transactions\":[" + String.join(",", legs) + "]}", 200, "feed");
        assertThat(r.get("alerts_raised")).anySatisfy(a -> assertThat(a.get("scenario_code").asString()).isEqualTo("FAN_IN_5"));
        fanInAlert = r.get("alerts_raised").get(0).get("tm_alert_id").asString();

        JsonNode node = get("/v1/risk/graph/entities?entity_type=CUSTOMER&entity_ref=" + mule, TENANT).get("data").get(0);
        assertThat(node.get("is_mule_suspect").asBoolean()).isTrue();
        assertThat(node.get("risk_score").asInt()).isGreaterThan(500);
        JsonNode acct = get("/v1/risk/graph/entities?entity_type=ACCOUNT&entity_ref=" + muleAccount, TENANT).get("data").get(0);
        JsonNode links = get("/v1/risk/graph/entities/" + acct.get("id").asString() + "/links", TENANT).get("data");
        assertThat(links).hasSize(7); // owned by the customer, 5 payers in, 1 transfer out
        JsonNode net = get("/v1/risk/graph/entities/" + node.get("id").asString() + "/network?depth=2", TENANT);
        assertThat(net.get("node_count").asInt()).isEqualTo(8);
        assertThat(net.get("mule_suspects").asInt()).isEqualTo(1);
        assertThat(net.get("edges")).hasSize(7);
        JsonNode one = get("/v1/risk/graph/entities/" + node.get("id").asString() + "/network?depth=1", TENANT);
        assertThat(one.get("node_count").asInt()).isEqualTo(2);
        assertThat(raw("GET", "/v1/risk/graph/entities/" + node.get("id").asString() + "/network?depth=4", null, TENANT)
                .statusCode()).isEqualTo(422);

        JsonNode shared = post("/v1/risk/graph/links/observe", """
                {"from_type":"CUSTOMER","from_ref":"%s","from_customer_id":"%s","to_type":"DEVICE","to_ref":"dev-hash-77",
                 "link_type":"shared_device"}""".formatted(mule, mule), 200, "fraud-service");
        assertThat(shared.get("link_type").asString()).isEqualTo("SHARED_DEVICE");
        post("/v1/risk/graph/links/observe", """
                {"from_type":"CUSTOMER","from_ref":"%s","from_customer_id":"%s","to_type":"DEVICE","to_ref":"dev-hash-77",
                 "link_type":"SHARED_DEVICE"}""".formatted(structurer, structurer), 200, "fraud-service");
        JsonNode viaDevice = get("/v1/risk/graph/entities/" + node.get("id").asString() + "/network?depth=2", TENANT);
        assertThat(viaDevice.toString()).contains(structurer);
    }

    @Test
    @Order(4)
    void alertTriageAndEscalation() throws Exception {
        UUID caseId = UUID.randomUUID();
        when(serviceClient.post(eq("case-management-service"), eq("/v1/risk/cases"), any(), anyString()))
                .thenReturn(json.readTree("""
                        {"id":"%s","case_number":"AML-2026-000009","case_type":"AML","status":"OPEN","priority":"MEDIUM",
                         "title":"TM alert","opened_at":"2026-10-06T10:00:00Z"}""".formatted(caseId)));
        when(serviceClient.post(eq("case-management-service"), eq("/v1/risk/cases/" + caseId + "/alerts"), any(), anyString()))
                .thenReturn(json.readTree("{\"id\":\"" + UUID.randomUUID() + "\"}"));

        JsonNode triaged = post("/v1/risk/monitoring/alerts/" + structuringAlert + "/assign", "{\"assigned_to\":\"l1-analyst\"}", 200, "lead");
        assertThat(triaged.get("status").asString()).isEqualTo("TRIAGED");
        JsonNode amlCase = post("/v1/risk/monitoring/alerts/" + structuringAlert + "/escalate",
                "{\"case_type\":\"AML\",\"reason\":\"Structuring confirmed by L1\"}", 200, "l1-analyst");
        assertThat(amlCase.get("id").asString()).isEqualTo(caseId.toString());
        JsonNode escalated = get("/v1/risk/monitoring/alerts/" + structuringAlert, TENANT);
        assertThat(escalated.get("status").asString()).isEqualTo("CLOSED_CASE_OPENED");
        assertThat(escalated.get("aml_case_id").asString()).isEqualTo(caseId.toString());
        assertThat(post("/v1/risk/monitoring/alerts/" + structuringAlert + "/close",
                "{\"closed_reason\":\"x\",\"triage_note\":\"y\"}", 409, "l1-analyst").get("code").asString()).isEqualTo("invalid_transition");

        JsonNode closed = post("/v1/risk/monitoring/alerts/" + fanInAlert + "/close",
                "{\"closed_reason\":\"Payroll aggregator\",\"triage_note\":\"Known employer payouts\"}", 200, "l1-analyst");
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED_NO_ACTION");
        assertThat(closed.get("closed_at").asString()).isNotBlank();
    }

    @Test
    @Order(5)
    void batchRunAndBacktest() throws Exception {
        String acct = UUID.randomUUID().toString();
        post("/v1/risk/monitoring/transactions/ingest", "{\"transactions\":[" + String.join(",",
                leg(sleeper, acct, "CREDIT", "80", false, "Old payer", Duration.ofDays(200)),
                leg(sleeper, acct, "CREDIT", "7600", false, "New payer", Duration.ofDays(2))) + "]}", 200, "feed");
        assertThat(get("/v1/risk/monitoring/alerts?customer_id=" + sleeper, TENANT).get("data")).isEmpty(); // batch scenario

        String from = OffsetDateTime.now().minusDays(30).toString();
        String to = OffsetDateTime.now().plusMinutes(1).toString();
        JsonNode run = post("/v1/risk/monitoring/runs", "{\"mode\":\"BATCH\",\"from_ts\":\"%s\",\"to_ts\":\"%s\"}".formatted(from, to), 201, "scheduler");
        JsonNode done = awaitRun(run.get("id").asString());
        assertThat(done.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.get("alerts_generated").asInt()).isEqualTo(1); // dormant; structuring and fan-in already alerted
        assertThat(done.get("results").get("alerts").get("DORMANT_90").asInt()).isEqualTo(1);
        JsonNode dormantAlert = get("/v1/risk/monitoring/alerts?customer_id=" + sleeper, TENANT).get("data").get(0);
        assertThat(dormantAlert.get("tm_run_id").asString()).isEqualTo(run.get("id").asString());

        JsonNode bt = post("/v1/risk/monitoring/scenarios/" + structuring + "/backtest",
                "{\"scenario_version\":1,\"from_ts\":\"%s\",\"to_ts\":\"%s\"}".formatted(from, to), 200, "mlro");
        assertThat(bt.get("mode").asString()).isEqualTo("BACKTEST");
        JsonNode btDone = awaitRun(bt.get("id").asString());
        assertThat(btDone.get("results").get("would_alert").get("STRUCT_10K@v1").asInt()).isEqualTo(1);
        assertThat(get("/v1/risk/monitoring/alerts?tm_scenario_id=" + structuring, TENANT).get("data")).hasSize(1);
        assertThat(post("/v1/risk/monitoring/runs/" + bt.get("id").asString() + "/cancel", "{\"reason\":\"x\"}", 409, "mlro")
                .get("code").asString()).isEqualTo("invalid_transition");
        assertThat(post("/v1/risk/monitoring/runs", "{\"mode\":\"BATCH\",\"from_ts\":\"%s\",\"to_ts\":\"%s\"}".formatted(to, from),
                422, "scheduler").get("code").asString()).isEqualTo("invalid_period");
    }

    @Test
    @Order(6)
    void customerRiskRatingWithFourEyesOverride() throws Exception {
        JsonNode crr = post("/v1/risk/monitoring/customer-risk-ratings", """
                {"customer_id":"%s","factors":{"country_risk":"MEDIUM","product_risk":"LOW","channel_risk":"MEDIUM",
                 "occupation_risk":"LOW","pep":false}}""".formatted(structurer), 201, "kyc-service");
        assertThat(crr.get("rating").asString()).isIn("LOW", "MEDIUM");
        assertThat(crr.get("factors").get("behaviour").get("escalated_12m").asInt()).isEqualTo(1);
        assertThat(crr.get("effective_rating").asString()).isEqualTo(crr.get("rating").asString());
        String id = crr.get("id").asString();
        assertThat(post("/v1/risk/monitoring/customer-risk-ratings", "{\"customer_id\":\"%s\",\"factors\":{}}".formatted(structurer),
                409, "kyc-service").get("code").asString()).isEqualTo("rating_exists");

        JsonNode requested = post("/v1/risk/monitoring/customer-risk-ratings/" + id + "/override",
                "{\"override_rating\":\"HIGH\",\"override_reason\":\"Structuring case opened; EDD required\"}", 200, "alice");
        assertThat(requested.get("override_status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(requested.get("effective_rating").asString()).isEqualTo(crr.get("rating").asString());
        assertThat(post("/v1/risk/monitoring/customer-risk-ratings/" + id + "/approve-override", "{}", 422, "alice")
                .get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode approved = post("/v1/risk/monitoring/customer-risk-ratings/" + id + "/approve-override", "{\"comment\":\"ok\"}", 200, "bob");
        assertThat(approved.get("effective_rating").asString()).isEqualTo("HIGH");
        assertThat(approved.get("next_review_date").asString()).isEqualTo(java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusYears(1).toString());
        JsonNode recalculated = post("/v1/risk/monitoring/customer-risk-ratings/" + id + "/recalculate", "", 200, "scheduler");
        assertThat(recalculated.get("effective_rating").asString()).isEqualTo("HIGH");

        JsonNode pep = post("/v1/risk/monitoring/customer-risk-ratings", """
                {"customer_id":"%s","factors":{"country_risk":"LOW","pep":true}}""".formatted(UUID.randomUUID()), 201, "kyc-service");
        assertThat(pep.get("rating").asString()).isEqualTo("HIGH");
    }

    @Test
    @Order(7)
    void validationAndTenantIsolation() throws Exception {
        assertThat(post("/v1/risk/monitoring/transactions/ingest",
                "{\"transactions\":[{\"transaction_id\":\"x\"}]}", 422, "feed").get("code").asString()).isEqualTo("invalid_transaction");
        assertThat(raw("GET", "/v1/risk/monitoring/alerts/" + structuringAlert, null, OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(get("/v1/risk/monitoring/transactions", OTHER_TENANT).get("data")).isEmpty();
        assertThat(get("/v1/risk/graph/entities", OTHER_TENANT).get("data")).isEmpty();
        JsonNode otherIngest = json.readTree(raw("POST", "/v1/risk/monitoring/transactions/ingest", "{\"transactions\":["
                + leg(structurer, UUID.randomUUID().toString(), "CREDIT", "9500", true, null, Duration.ofHours(1)) + "]}", OTHER_TENANT).body());
        assertThat(otherIngest.get("scenarios_evaluated").asInt()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode awaitRun(String id) throws Exception {
        JsonNode r = null;
        for (int i = 0; i < 100; i++) {
            r = get("/v1/risk/monitoring/runs/" + id, TENANT);
            String st = r.get("status").asString();
            if (!"QUEUED".equals(st) && !"RUNNING".equals(st)) {
                return r;
            }
            Thread.sleep(100);
        }
        return r;
    }

    private String leg(String customer, String account, String direction, String amount, boolean cash, String counterparty,
                       Duration ago) {
        return """
                {"transaction_id":"%s","transaction_type":"%s","customer_id":"%s","account_id":"%s","direction":"%s",
                 "amount":"%s","currency":"USD","is_cash":%s,%s"occurred_at":"%s"}""".formatted(UUID.randomUUID(),
                cash ? "CASH" : "TRANSFER", customer, account, direction, amount, cash,
                counterparty == null ? "" : "\"counterparty_name\":\"" + counterparty + "\",\"counterparty_account\":\"" + counterparty + "\",",
                Instant.now().minus(ago));
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = send("POST", path, body, TENANT, user);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = raw("GET", path, null, tenant);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> raw(String method, String path, String body, String tenant) throws Exception {
        return send(method, path, body, tenant, "reader");
    }

    private HttpResponse<String> send(String method, String path, String body, String tenant, String user) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenant)
                .header("X-User", user)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (!"GET".equals(method)) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
