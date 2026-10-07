package io.meridian.limits;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.test.MeridianTestContainers;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Limits end to end: maker-checker rules, check-and-reserve with commit / release, specificity, per-transaction,
 * count and regulatory limits, overrides with four-eyes, concurrency, expiry, resets and risk appetite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.business-date.source=clock"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LimitsFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000c1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000c2";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    final HttpClient http = HttpClient.newHttpClient();
    final String customer = UUID.randomUUID().toString();
    final String vip = UUID.randomUUID().toString();
    String globalDaily;
    String regulatory;
    String firstCheck;
    String secondCheck;

    @BeforeAll
    void setUp() throws Exception {
        globalDaily = rule("""
                {"code":"GLOBAL_DAILY","name":"Daily outflow","scope":"GLOBAL","period":"DAILY","measure":"AMOUNT",
                 "currency":"USD","max_amount":"1000","breach_action":"REJECT"}""");
        rule("""
                {"code":"VIP_DAILY","name":"VIP daily","scope":"CUSTOMER","scope_ref":"%s","period":"DAILY","measure":"AMOUNT",
                 "currency":"USD","max_amount":"5000","breach_action":"REJECT"}""".formatted(vip));
        rule("""
                {"code":"PER_TXN","name":"Single payment","scope":"GLOBAL","period":"PER_TRANSACTION","measure":"AMOUNT",
                 "currency":"USD","max_amount":"600","breach_action":"REVIEW"}""");
        rule("""
                {"code":"P2P_COUNT","name":"P2P count","scope":"GLOBAL","transaction_type":"P2P","period":"DAILY",
                 "measure":"COUNT","max_count":3,"breach_action":"REJECT"}""");
        regulatory = rule("""
                {"code":"AML_ROLLING","name":"Regulatory rolling cap","scope":"GLOBAL","period":"ROLLING_24H","measure":"AMOUNT",
                 "currency":"USD","max_amount":"20000","breach_action":"REJECT","is_regulatory":true}""");
    }

    private String rule(String body) throws Exception {
        String id = post("/v1/risk/limits/rules", body, 201, "alice").get("id").asString();
        JsonNode self = post("/v1/risk/limits/rules/" + id + "/activate", "{}", 422, "alice");
        assertThat(self.get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode active = post("/v1/risk/limits/rules/" + id + "/activate", "{\"comment\":\"ok\"}", 200, "bob");
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        return id;
    }

    @Test
    @Order(1)
    void reserveUntilTheDailyLimitThenReject() throws Exception {
        JsonNode c1 = check(customer, "400", "BILL_PAY", "k-1", 201);
        assertThat(c1.get("allowed").asBoolean()).isTrue();
        assertThat(c1.get("status").asString()).isEqualTo("RESERVED");
        assertThat(remaining(c1, globalDaily)).isEqualByComparingTo("600");
        firstCheck = c1.get("id").asString();
        assertThat(c1.has("reservations")).isFalse();

        JsonNode c2 = check(customer, "400", "BILL_PAY", "k-2", 201);
        assertThat(remaining(c2, globalDaily)).isEqualByComparingTo("200");
        secondCheck = c2.get("id").asString();

        JsonNode c3 = check(customer, "400", "BILL_PAY", "k-3", 201);
        assertThat(c3.get("allowed").asBoolean()).isFalse();
        assertThat(c3.get("status").asString()).isEqualTo("REJECTED");
        assertThat(c3.get("action").asString()).isEqualTo("REJECT");
        assertThat(c3.get("breached_rule_ids").toString()).contains(globalDaily);

        HttpResponse<String> replay = send("POST", "/v1/risk/limits/checks", checkBody(customer, "400", "BILL_PAY"), "k-1", TENANT, "svc");
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(firstCheck);
        JsonNode reused = json.readTree(send("POST", "/v1/risk/limits/checks", checkBody(customer, "1", "BILL_PAY"),
                "k-1", TENANT, "svc").body());
        assertThat(reused.get("code").asString()).isEqualTo("idempotency_key_reused");
    }

    @Test
    @Order(2)
    void releaseFreesHeadroomAndCommitCountsActualAmount() throws Exception {
        JsonNode released = post("/v1/risk/limits/checks/" + secondCheck + "/release", "{\"reason\":\"payment failed\"}", 200, "svc");
        assertThat(released.get("status").asString()).isEqualTo("RELEASED");
        assertThat(post("/v1/risk/limits/checks/" + secondCheck + "/commit", "{}", 409, "svc").get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(post("/v1/risk/limits/checks/" + firstCheck + "/commit", "{\"amount\":\"450\"}", 422, "svc")
                .get("code").asString()).isEqualTo("invalid_commit_amount");
        JsonNode committed = post("/v1/risk/limits/checks/" + firstCheck + "/commit", "{\"amount\":\"350\"}", 200, "svc");
        assertThat(committed.get("status").asString()).isEqualTo("COMMITTED");
        assertThat(new BigDecimal(committed.get("committed_amount").asString())).isEqualByComparingTo("350");

        JsonNode usage = get("/v1/risk/limits/usages?limit_rule_id=" + globalDaily + "&subject_id=" + customer, TENANT)
                .get("data").get(0);
        assertThat(new BigDecimal(usage.get("used_amount").asString())).isEqualByComparingTo("350");
        assertThat(new BigDecimal(usage.get("reserved_amount").asString())).isEqualByComparingTo("0");
        assertThat(usage.get("used_count").asInt()).isEqualTo(1);

        JsonNode dry = post("/v1/risk/limits/checks/evaluate", checkBody(customer, "600", "BILL_PAY"), 200, "svc");
        assertThat(dry.get("allowed").asBoolean()).isTrue();
        assertThat(dry.get("dry_run").asBoolean()).isTrue();
        assertThat(remainingIn(dry, globalDaily)).isEqualByComparingTo("50");
        JsonNode after = get("/v1/risk/limits/usages?limit_rule_id=" + globalDaily + "&subject_id=" + customer, TENANT)
                .get("data").get(0);
        assertThat(new BigDecimal(after.get("reserved_amount").asString())).isEqualByComparingTo("0");
    }

    @Test
    @Order(3)
    void specificRuleSupersedesGlobalAndPerTransactionReviews() throws Exception {
        JsonNode vipCheck = check(vip, "3000", "BILL_PAY", "vip-1", 201);
        assertThat(vipCheck.get("action").asString()).isEqualTo("REVIEW"); // 3000 > per-transaction 600
        assertThat(vipCheck.get("allowed").asBoolean()).isFalse();
        assertThat(vipCheck.get("status").asString()).isEqualTo("RESERVED");
        assertThat(vipCheck.get("evaluated_rule_ids").toString()).doesNotContain(globalDaily);

        JsonNode sim = post("/v1/risk/limits/rules/simulate", checkBody(vip, "100", "BILL_PAY"), 200, "ops");
        assertThat(sim.get("superseded_rules")).anySatisfy(r -> {
            assertThat(r.get("code").asString()).isEqualTo("GLOBAL_DAILY");
            assertThat(r.get("superseded_by").asString()).isEqualTo("VIP_DAILY");
        });
        assertThat(sim.get("applicable_rules").toString()).contains("AML_ROLLING", "VIP_DAILY", "PER_TXN");
    }

    @Test
    @Order(4)
    void countLimitPerTransactionType() throws Exception {
        String c = UUID.randomUUID().toString();
        for (int i = 0; i < 3; i++) {
            assertThat(check(c, "5", "P2P", "p2p-" + i, 201).get("allowed").asBoolean()).isTrue();
        }
        JsonNode fourth = check(c, "5", "P2P", "p2p-4", 201);
        assertThat(fourth.get("allowed").asBoolean()).isFalse();
        assertThat(fourth.get("remaining").toString()).contains("P2P_COUNT");
        assertThat(check(c, "5", "BILL_PAY", "bill-x", 201).get("allowed").asBoolean()).isTrue();
    }

    @Test
    @Order(5)
    void overrideNeedsASecondApproverAndRaisesTheLimit() throws Exception {
        String c = UUID.randomUUID().toString();
        String from = OffsetDateTime.now().minusMinutes(1).toString();
        String to = OffsetDateTime.now().plusDays(2).toString();
        JsonNode reg = post("/v1/risk/limits/overrides", """
                {"limit_rule_id":"%s","subject_type":"CUSTOMER","subject_id":"%s","max_amount":"90000","reason":"x",
                 "valid_from":"%s","valid_to":"%s"}""".formatted(regulatory, c, from, to), 422, "alice");
        assertThat(reg.get("code").asString()).isEqualTo("regulatory_limit");
        JsonNode tooLong = post("/v1/risk/limits/overrides", """
                {"limit_rule_id":"%s","subject_type":"CUSTOMER","subject_id":"%s","max_amount":"3000","reason":"x",
                 "valid_from":"%s","valid_to":"%s"}""".formatted(globalDaily, c, from, OffsetDateTime.now().plusDays(200)), 422, "alice");
        assertThat(tooLong.get("code").asString()).isEqualTo("override_too_long");

        JsonNode ov = post("/v1/risk/limits/overrides", """
                {"limit_rule_id":"%s","subject_type":"CUSTOMER","subject_id":"%s","max_amount":"3000",
                 "reason":"House purchase deposit","valid_from":"%s","valid_to":"%s"}""".formatted(globalDaily, c, from, to), 201, "alice");
        assertThat(ov.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(ov.get("requested_by").asString()).isEqualTo("alice");
        assertThat(check(c, "550", "BILL_PAY", "ov-0", 201).get("allowed").asBoolean()).isTrue();
        assertThat(check(c, "550", "BILL_PAY", "ov-1", 201).get("allowed").asBoolean()).isFalse();
        String ovId = ov.get("id").asString();
        assertThat(post("/v1/risk/limits/overrides/" + ovId + "/approve", "{}", 422, "alice").get("code").asString())
                .isEqualTo("four_eyes_violation");
        JsonNode approved = post("/v1/risk/limits/overrides/" + ovId + "/approve", "{\"comment\":\"docs seen\"}", 200, "bob");
        assertThat(approved.get("status").asString()).isEqualTo("ACTIVE");
        JsonNode allowedNow = check(c, "550", "BILL_PAY", "ov-2", 201);
        assertThat(allowedNow.get("allowed").asBoolean()).isTrue();
        assertThat(allowedNow.get("remaining").toString()).contains(ovId);
        JsonNode revoked = post("/v1/risk/limits/overrides/" + ovId + "/revoke", "{\"reason\":\"done\"}", 200, "bob");
        assertThat(revoked.get("status").asString()).isEqualTo("REVOKED");
    }

    @Test
    @Order(6)
    void concurrentChecksNeverOverReserve() throws Exception {
        String c = UUID.randomUUID().toString();
        int n = 20;
        List<Future<JsonNode>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                String key = "conc-" + i;
                results.add(pool.submit(() -> json.readTree(send("POST", "/v1/risk/limits/checks",
                        checkBody(c, "100", "BILL_PAY"), key, TENANT, "svc").body())));
            }
            int allowed = 0;
            for (Future<JsonNode> f : results) {
                JsonNode r = f.get();
                assertThat(r.has("id")).as(r.toString()).isTrue();
                if (r.get("allowed").asBoolean()) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(10);
        }
        JsonNode usage = get("/v1/risk/limits/usages?limit_rule_id=" + globalDaily + "&subject_id=" + c, TENANT).get("data").get(0);
        assertThat(new BigDecimal(usage.get("reserved_amount").asString())).isEqualByComparingTo("1000");
        assertThat(usage.get("reserved_count").asInt()).isEqualTo(10);
    }

    @Test
    @Order(7)
    void abandonedReservationsExpireAndUsageCanBeReset() throws Exception {
        String c = UUID.randomUUID().toString();
        JsonNode bad = post("/v1/risk/limits/checks", """
                {"subject_type":"ACCOUNT","subject_id":"%s","customer_id":"%s","transaction_type":"BILL_PAY","amount":"10",
                 "currency":"USD","expires_at":"2020-01-01T00:00:00Z"}""".formatted(UUID.randomUUID(), c), 422, "svc");
        assertThat(bad.get("code").asString()).isEqualTo("invalid_expiry");
        String exp = OffsetDateTime.now().plusSeconds(1).toString();
        JsonNode chk = post("/v1/risk/limits/checks", """
                {"subject_type":"ACCOUNT","subject_id":"%s","customer_id":"%s","transaction_type":"BILL_PAY","amount":"300",
                 "currency":"USD","expires_at":"%s"}""".formatted(UUID.randomUUID(), c, exp), 201, "svc");
        Thread.sleep(1500);
        JsonNode expired = post("/v1/risk/limits/checks/expire-reservations", "{\"max_checks\":100}", 200, "scheduler");
        assertThat(expired.get("check_ids").toString()).contains(chk.get("id").asString());
        assertThat(get("/v1/risk/limits/checks/" + chk.get("id").asString(), TENANT).get("status").asString()).isEqualTo("EXPIRED");
        JsonNode usage = get("/v1/risk/limits/usages?limit_rule_id=" + globalDaily + "&subject_id=" + c, TENANT).get("data").get(0);
        assertThat(new BigDecimal(usage.get("reserved_amount").asString())).isEqualByComparingTo("0");

        JsonNode custUsage = get("/v1/risk/limits/usages?limit_rule_id=" + globalDaily + "&subject_id=" + customer, TENANT).get("data").get(0);
        JsonNode reset = post("/v1/risk/limits/usages/" + custUsage.get("id").asString() + "/reset",
                "{\"reason\":\"duplicate posting reversed\"}", 200, "ops");
        assertThat(new BigDecimal(reset.get("used_amount").asString())).isEqualByComparingTo("0");
        assertThat(reset.get("last_reset_reason").asString()).isEqualTo("duplicate posting reversed");
    }

    @Test
    @Order(8)
    void ruleValidationAndEditingRules() throws Exception {
        assertThat(post("/v1/risk/limits/rules", """
                {"code":"BAD1","name":"x","scope":"CUSTOMER","period":"DAILY","measure":"AMOUNT","currency":"USD","max_amount":"1"}""",
                422, "alice").get("code").asString()).isEqualTo("scope_ref_required");
        assertThat(post("/v1/risk/limits/rules", """
                {"code":"BAD2","name":"x","scope":"GLOBAL","period":"DAILY","measure":"COUNT"}""",
                422, "alice").get("code").asString()).isEqualTo("max_count_required");
        JsonNode active = get("/v1/risk/limits/rules/" + globalDaily, TENANT);
        HttpResponse<String> patch = send("PATCH", "/v1/risk/limits/rules/" + globalDaily, "{\"max_amount\":\"2000\"}",
                null, TENANT, "alice", active.get("row_version").asString());
        assertThat(patch.statusCode()).isEqualTo(409);
        JsonNode off = post("/v1/risk/limits/rules/" + globalDaily + "/deactivate", "{\"reason\":\"re-tune\"}", 200, "bob");
        assertThat(off.get("status").asString()).isEqualTo("INACTIVE");
        HttpResponse<String> edited = send("PATCH", "/v1/risk/limits/rules/" + globalDaily, "{\"max_amount\":\"2000\"}",
                null, TENANT, "carol", off.get("row_version").asString());
        assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
        assertThat(post("/v1/risk/limits/rules/" + globalDaily + "/activate", "{}", 422, "carol").get("code").asString())
                .isEqualTo("four_eyes_violation");
        post("/v1/risk/limits/rules/" + globalDaily + "/activate", "{}", 200, "alice");
    }

    @Test
    @Order(9)
    void riskAppetiteMeasurements() throws Exception {
        String metric = post("/v1/risk/appetite-metrics", """
                {"legal_entity_id":"%s","metric_code":"FRAUD_LOSS_BPS","name":"Fraud losses","unit":"BPS",
                 "threshold_amber":"5","threshold_red":"10"}""".formatted(UUID.randomUUID()), 201, "cro").get("id").asString();
        JsonNode amber = post("/v1/risk/appetite-metrics/" + metric + "/measurements",
                "{\"current_value\":\"7.5\",\"measured_at\":\"%s\",\"source\":\"fraud-mart\"}".formatted(OffsetDateTime.now().minusHours(1)),
                200, "cro");
        assertThat(amber.get("status").asString()).isEqualTo("AMBER");
        JsonNode older = post("/v1/risk/appetite-metrics/" + metric + "/measurements",
                "{\"current_value\":\"1\",\"measured_at\":\"%s\"}".formatted(OffsetDateTime.now().minusDays(3)), 200, "cro");
        assertThat(older.get("status").asString()).isEqualTo("AMBER"); // history only; the latest value stays current
        assertThat(get("/v1/risk/appetite-metrics/" + metric + "/measurements", TENANT).get("data")).hasSize(2);
    }

    @Test
    @Order(10)
    void tenantIsolationAndLatency() throws Exception {
        assertThat(send("GET", "/v1/risk/limits/checks/" + firstCheck, null, null, OTHER_TENANT, "x").statusCode()).isEqualTo(404);
        assertThat(get("/v1/risk/limits/rules", OTHER_TENANT).get("data")).isEmpty();
        JsonNode other = json.readTree(send("POST", "/v1/risk/limits/checks/evaluate", checkBody(customer, "999999", "BILL_PAY"),
                UUID.randomUUID().toString(), OTHER_TENANT, "svc").body());
        assertThat(other.get("allowed").asBoolean()).isTrue();
        assertThat(other.get("evaluated_rule_ids")).isEmpty();

        String c = UUID.randomUUID().toString();
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            long t = System.nanoTime();
            check(c, "1", "BILL_PAY", "lat-" + i, 201);
            times.add((System.nanoTime() - t) / 1_000_000);
        }
        times.sort(Long::compare);
        System.out.println("limit check latency ms p50=" + times.get(20) + " p95=" + times.get(37) + " max=" + times.get(39));
        assertThat(times.get(37)).isLessThan(250L);
    }

    // ------------------------------------------------------------------ helpers

    private static BigDecimal remaining(JsonNode check, String ruleId) {
        return remainingIn(check, ruleId);
    }

    private static BigDecimal remainingIn(JsonNode n, String ruleId) {
        for (JsonNode r : n.get("remaining")) {
            if (r.get("limit_rule_id").asString().equals(ruleId)) {
                return new BigDecimal(r.get("remaining_amount").asString());
            }
        }
        throw new AssertionError("rule " + ruleId + " not evaluated: " + n);
    }

    private String checkBody(String cust, String amount, String txType) {
        return """
                {"subject_type":"ACCOUNT","subject_id":"%s","customer_id":"%s","transaction_type":"%s","channel":"MOBILE_APP",
                 "amount":"%s","currency":"USD","kyc_tier":"T1"}""".formatted(UUID.nameUUIDFromBytes(cust.getBytes()), cust, txType, amount);
    }

    private JsonNode check(String cust, String amount, String txType, String key, int status) throws Exception {
        HttpResponse<String> r = send("POST", "/v1/risk/limits/checks", checkBody(cust, amount, txType), key, TENANT, "svc");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return json.readTree(r.body());
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
