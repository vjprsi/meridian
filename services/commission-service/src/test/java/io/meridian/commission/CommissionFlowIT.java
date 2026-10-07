package io.meridian.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * commission-service end to end over HTTP against PostgreSQL 18 (RLS enforced) with wallet-service and
 * agent-network-service replaced by the stateful {@link FakeNetwork}: a 3-level agent hierarchy, a commission pool
 * wallet and beneficiary commission wallets. Payouts are asserted as wallet balance movements.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.commission.listener-enabled=false",
        "meridian.commission.payout-source-wallet-id=0199c000-0000-7000-8000-0000000000aa"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommissionFlowIT {
    static final String TENANT = "0199c000-0000-7000-8000-00000000c001";
    static final String OTHER_TENANT = "0199c000-0000-7000-8000-00000000c002";
    static final UUID POOL = UUID.fromString("0199c000-0000-7000-8000-0000000000aa");
    static final UUID SUPER = UUID.randomUUID();
    static final UUID AGENT = UUID.randomUUID();
    static final UUID SUB = UUID.randomUUID();
    static final UUID TERRITORY = UUID.randomUUID();
    static final UUID SUPER_WALLET = UUID.randomUUID();
    static final UUID AGENT_WALLET = UUID.randomUUID();
    static final UUID SUB_WALLET = UUID.randomUUID();

    @Value("${local.server.port}")
    int port;

    @Autowired
    io.meridian.web.time.BusinessDates businessDates;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakeNetwork net;
    String v1;
    String v2;
    String txn2;
    String run;

    @BeforeAll
    void setUp() {
        net = new FakeNetwork(json);
        net.wallet(POOL, "KES", "10000");
        net.wallet(SUPER_WALLET, "KES", "0");
        net.wallet(AGENT_WALLET, "KES", "0");
        net.wallet(SUB_WALLET, "KES", "0");
        net.agent(SUPER, "SUPER_AGENT", null, TERRITORY, SUPER_WALLET);
        net.agent(AGENT, "AGENT", SUPER, TERRITORY, AGENT_WALLET);
        net.agent(SUB, "SUB_AGENT", AGENT, TERRITORY, SUB_WALLET);
    }

    @BeforeEach
    void stub() {
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> net.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> net.get(i.getArgument(0), i.getArgument(1)));
    }

    // ------------------------------------------------------------------ 1. plans and rules

    @Test
    @Order(1)
    void planLifecycleWithRulesFourEyesCloneAndReject() throws Exception {
        JsonNode p = post("/v1/commission-plans", """
                {"code":"AGT-STD","name":"Agent standard","applies_to":"AGENT","currency":"KES","payout_frequency":"DAILY",
                 "valid_from":"2020-01-01"}""", "maker", 201);
        v1 = p.get("id").asString();
        assertThat(p.get("status").asString()).isEqualTo("DRAFT");
        assertThat(p.get("version").asInt()).isEqualTo(1);
        post("/v1/commission-plans", """
                {"code":"BAD","name":"x","applies_to":"EVERYONE","currency":"KES","valid_from":"2020-01-01"}""", "maker", 422);

        rule(v1, "Cash-in agent", "AGENT", "\"basis\":\"FLAT\",\"flat_amount\":\"10\",\"base\":\"AMOUNT\"", 201);
        rule(v1, "Cash-in sub-agent as agent", "AGENT",
                "\"basis\":\"FLAT\",\"flat_amount\":\"6\",\"base\":\"AMOUNT\",\"agent_level\":\"SUB_AGENT\"", 201);
        rule(v1, "Sub-agent override", "SUB_AGENT", "\"basis\":\"FLAT\",\"flat_amount\":\"4\",\"base\":\"AMOUNT\"", 201);
        rule(v1, "Super-agent override", "SUPER_AGENT",
                "\"basis\":\"PERCENTAGE\",\"percentage\":\"0.001\",\"base\":\"AMOUNT\",\"tax_withholding_rate\":\"0.10\"", 201);
        JsonNode bad = rule(v1, "No percentage", "AGENT", "\"basis\":\"PERCENTAGE\",\"base\":\"AMOUNT\"", 422);
        assertThat(bad.get("code").asString()).isEqualTo("invalid_rule");
        rule(v1, "Platform without wallet", "PLATFORM", "\"basis\":\"FLAT\",\"flat_amount\":\"1\"", 422);

        post("/v1/commission-plans/" + v1 + "/submit", null, "maker", 200);
        assertThat(post("/v1/commission-plans/" + v1 + "/approve", "{}", "maker", 422).get("code").asString())
                .isEqualTo("four_eyes_violation");
        JsonNode active = post("/v1/commission-plans/" + v1 + "/approve", "{\"comment\":\"ok\"}", "checker", 200);
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(active.get("approved_by").asString()).isEqualTo("checker");
        assertThat(rule(v1, "late", "AGENT", "\"basis\":\"FLAT\",\"flat_amount\":\"1\"", 409).get("code").asString())
                .isEqualTo("plan_not_editable");
        HttpResponse<String> patch = raw("PATCH", "/v1/commission-plans/" + v1, "{\"name\":\"renamed\"}", null, "maker", TENANT);
        assertThat(patch.statusCode()).isEqualTo(409);

        JsonNode clone = post("/v1/commission-plans/" + v1 + "/clone", null, "maker", 200);
        v2 = clone.get("id").asString();
        assertThat(clone.get("version").asInt()).isEqualTo(2);
        assertThat(clone.get("parent_plan_id").asString()).isEqualTo(v1);
        assertThat(get("/v1/commission-plans/" + v2 + "/rules").get("data")).hasSize(4);
        post("/v1/commission-plans/" + v2 + "/submit", null, "maker", 200);
        post("/v1/commission-plans/" + v2 + "/approve", "{}", "checker", 200);
        assertThat(get("/v1/commission-plans/" + v1).get("status").asString()).isEqualTo("RETIRED");

        String v3 = post("/v1/commission-plans/" + v2 + "/clone", null, "maker", 200).get("id").asString();
        post("/v1/commission-plans/" + v3 + "/approve", "{}", "checker", 409);
        post("/v1/commission-plans/" + v3 + "/submit", null, "maker", 200);
        JsonNode rejected = post("/v1/commission-plans/" + v3 + "/reject", "{\"reason\":\"rates too high\"}", "checker", 200);
        assertThat(rejected.get("status").asString()).isEqualTo("DRAFT");
        assertThat(rejected.get("rejection_reason").asString()).isEqualTo("rates too high");
        assertThat(post("/v1/commission-plans/" + v3 + "/retire", "{}", "checker", 200).get("status").asString()).isEqualTo("RETIRED");
    }

    // ------------------------------------------------------------------ 2. simulate and accrue

    @Test
    @Order(2)
    void simulateAndAccrueAcrossTheHierarchy() throws Exception {
        JsonNode sim = post("/v1/commission-plans/" + v2 + "/simulate", """
                {"txn_type":"CASH_IN","amount":"1000","agent_id":"%s"}""".formatted(SUB), "ops", 200);
        assertThat(sim.get("total_commission").asString()).isEqualTo("11.00");
        assertThat(sim.get("earnings")).hasSize(3);
        assertThat(sim.get("earnings")).anyMatch(e -> e.get("beneficiary").asString().equals("AGENT")
                && e.get("commission_amount").asString().equals("6.00")
                && e.get("beneficiary_wallet_id").asString().equals(SUB_WALLET.toString()));
        assertThat(sim.get("earnings")).anyMatch(e -> e.get("beneficiary").asString().equals("SUPER_AGENT")
                && e.get("net_amount").asString().equals("0.90")
                && e.get("beneficiary_wallet_id").asString().equals(SUPER_WALLET.toString()));

        String txn1 = UUID.randomUUID().toString();
        JsonNode a1 = post("/v1/commission-earnings/accrue", accrue(txn1, "CASH_IN", "COMPLETED", "1000", SUB), "wallet", 200);
        assertThat(a1.get("earnings")).hasSize(3);
        assertThat(a1.get("total_commission").asString()).isEqualTo("11.00");
        JsonNode again = post("/v1/commission-earnings/accrue", accrue(txn1, "CASH_IN", "COMPLETED", "1000", SUB), "wallet", 200);
        assertThat(again.get("earnings")).hasSize(3);
        assertThat(get("/v1/commission-earnings?wallet_transaction_id=" + txn1).get("data")).hasSize(3);
        JsonNode e = a1.get("earnings").get(0);
        assertThat(e.get("commission_plan_id").asString()).isEqualTo(v2);
        assertThat(e.get("payout_due_on").asString()).isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());

        txn2 = UUID.randomUUID().toString();
        JsonNode a2 = post("/v1/commission-earnings/accrue", accrue(txn2, "CASH_IN", "COMPLETED", "5000", AGENT), "wallet", 200);
        assertThat(a2.get("earnings")).hasSize(2); // AGENT 10 + SUPER_AGENT 5 (no sub-agent)
        assertThat(post("/v1/commission-earnings/accrue", accrue(UUID.randomUUID().toString(), "P2P", "COMPLETED", "100", AGENT),
                "wallet", 200).get("earnings")).isEmpty();
        assertThat(post("/v1/commission-earnings/accrue", accrue(UUID.randomUUID().toString(), "CASH_IN", "FAILED", "100", AGENT),
                "wallet", 422).get("code").asString()).isEqualTo("invalid_status");

        // reversal claws the commission back, once
        JsonNode claw = post("/v1/commission-earnings/accrue", accrue(txn2, "CASH_IN", "REVERSED", "5000", AGENT), "wallet", 200);
        assertThat(claw.get("earnings")).hasSize(4);
        assertThat(claw.get("total_commission").asString()).isEqualTo("0.00");
        post("/v1/commission-earnings/accrue", accrue(txn2, "CASH_IN", "REVERSED", "5000", AGENT), "wallet", 200);
        assertThat(get("/v1/commission-earnings?wallet_transaction_id=" + txn2 + "&is_clawback=true").get("data")).hasSize(2);

        JsonNode summary = get("/v1/commission-earnings/summary?beneficiary_wallet_id=" + SUPER_WALLET);
        JsonNode g = summary.get("groups").get(0);
        assertThat(new BigDecimal(g.get("earned").asString())).isEqualByComparingTo("5.40");
        assertThat(new BigDecimal(g.get("clawed_back").asString())).isEqualByComparingTo("4.50");
        assertThat(new BigDecimal(g.get("unpaid").asString())).isEqualByComparingTo("0.90");
    }

    // ------------------------------------------------------------------ 3. payout runs

    @Test
    @Order(3)
    void payoutRunWithFourEyesPartialFailureAndRetry() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThat(post("/v1/commission-payout-runs", runBody(today.minusYears(10), today.minusYears(9)), "maker", 422)
                .get("code").asString()).isEqualTo("nothing_to_pay");
        JsonNode r = post("/v1/commission-payout-runs", runBody(today.minusDays(1), today.plusDays(1)), "maker", 201);
        run = r.get("id").asString();
        assertThat(r.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(r.get("beneficiary_count").asInt()).isEqualTo(2); // sub-agent 10.00, super-agent 0.90; agent nets to 0
        assertThat(new BigDecimal(r.get("total_amount").asString())).isEqualByComparingTo("10.90");
        assertThat(r.get("earning_count").asInt()).isEqualTo(7);
        assertThat(post("/v1/commission-payout-runs", runBody(today, today), "maker", 409).get("code").asString())
                .isEqualTo("run_in_progress");
        assertThat(post("/v1/commission-payout-runs/" + run + "/approve", "{}", "maker", 422).get("code").asString())
                .isEqualTo("four_eyes_violation");

        net.failingPayees.add(SUB_WALLET);
        JsonNode done = post("/v1/commission-payout-runs/" + run + "/approve", "{}", "checker", 200);
        assertThat(done.get("status").asString()).isEqualTo("PARTIALLY_SUCCEEDED");
        assertThat(done.get("paid_count").asInt()).isEqualTo(1);
        assertThat(done.get("failed_count").asInt()).isEqualTo(1);
        assertThat(net.balance(SUPER_WALLET)).isEqualByComparingTo("0.90");
        assertThat(net.balance(SUB_WALLET)).isEqualByComparingTo("0");
        assertThat(net.balance(POOL)).isEqualByComparingTo("9999.10");
        JsonNode payout = net.payouts().stream().filter(c -> c.body().get("payee_wallet_id").asString().equals(SUPER_WALLET.toString()))
                .findFirst().orElseThrow().body();
        assertThat(payout.get("txn_type").asString()).isEqualTo("COMMISSION_PAYOUT");
        assertThat(payout.get("initiator_type").asString()).isEqualTo("SYSTEM");
        assertThat(net.payouts()).anyMatch(c -> c.key().equals("cpr-" + run + "-" + SUB_WALLET + "-1"));

        net.failingPayees.clear();
        JsonNode retried = post("/v1/commission-payout-runs/" + run + "/retry-failed", null, "checker", 200);
        assertThat(retried.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(new BigDecimal(retried.get("paid_amount").asString())).isEqualByComparingTo("10.90");
        assertThat(net.balance(SUB_WALLET)).isEqualByComparingTo("10.00");
        assertThat(net.balance(POOL)).isEqualByComparingTo("9989.10");
        assertThat(net.payouts()).anyMatch(c -> c.key().equals("cpr-" + run + "-" + SUB_WALLET + "-2"));
        JsonNode lines = get("/v1/commission-payout-runs/" + run + "/lines");
        assertThat(lines.get("data")).hasSize(3);
        assertThat(lines.get("data")).anyMatch(l -> l.get("payout_status").asString().equals("FAILED")
                && l.get("failure_reason").asString().startsWith("payee_not_active"));
        post("/v1/commission-payout-runs/" + run + "/retry-failed", null, "checker", 409);

        // paid earnings are no longer unpaid; the agent's netted-out earnings are carried into the next run
        JsonNode next = post("/v1/commission-payout-runs", runBody(today.minusDays(1), today.plusDays(1)), "maker", 201);
        assertThat(next.get("beneficiary_count").asInt()).isEqualTo(0);
        assertThat(next.get("earning_count").asInt()).isEqualTo(2);
        JsonNode cancelled = post("/v1/commission-payout-runs/" + next.get("id").asString() + "/cancel",
                "{\"reason\":\"nothing payable\"}", "maker", 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(new BigDecimal(get("/v1/commission-earnings/summary?beneficiary_wallet_id=" + SUPER_WALLET)
                .get("groups").get(0).get("unpaid").asString())).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ 4. idempotency, isolation, events

    @Test
    @Order(4)
    void idempotencyIsolationAndEvents() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = """
                {"code":"MER-STD","name":"Merchant","applies_to":"MERCHANT","currency":"KES","valid_from":"2020-01-01"}""";
        JsonNode first = send("POST", "/v1/commission-plans", body, key, "maker", TENANT, 201);
        JsonNode replay = send("POST", "/v1/commission-plans", body, key, "maker", TENANT, 201);
        assertThat(replay.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(raw("GET", "/v1/commission-plans/" + v2, null, null, "x", OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(json.readTree(raw("GET", "/v1/commission-earnings", null, null, "x", OTHER_TENANT).body()).get("data")).isEmpty();
        assertThat(raw("GET", "/v1/commission-payout-runs/" + run, null, null, "x", OTHER_TENANT).statusCode()).isEqualTo(404);

        List<String> types = TenantContext.callAs(UUID.fromString(TENANT), "test",
                () -> jdbc.sql("select distinct event_type from outbox").query(String.class).list());
        assertThat(types).contains("wallets.commission_plan.submitted", "wallets.commission_plan.activated",
                "wallets.commission_plan.retired", "wallets.commission_earning.accrued",
                "wallets.commission_payout_run.approved", "wallets.commission_payout_run.completed");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode rule(String plan, String name, String beneficiary, String extra, int expected) throws Exception {
        return post("/v1/commission-rules", "{\"commission_plan_id\":\"" + plan + "\",\"name\":\"" + name
                + "\",\"txn_type\":\"CASH_IN\",\"beneficiary\":\"" + beneficiary + "\"," + extra + "}", "maker", expected);
    }

    @Test
    @Order(5)
    void retirementDatesUseTheBusinessDate() throws Exception {
        when(serviceClient.get(eq("business-day-service"), anyString()))
                .thenReturn(json.readTree("{\"data\":[{\"business_date\":\"2031-02-03\"}]}"));
        businessDates.invalidate();
        try {
            String plan = post("/v1/commission-plans", """
                    {"code":"BD-RETIRE","name":"Retire me","applies_to":"AGENT","currency":"KES","payout_frequency":"DAILY",
                     "valid_from":"2020-01-01"}""", "maker", 201).get("id").asString();
            JsonNode retired = post("/v1/commission-plans/" + plan + "/retire", "{}", "checker", 200);
            assertThat(retired.get("status").asString()).isEqualTo("RETIRED");
            assertThat(retired.get("valid_to").asString()).isEqualTo("2031-02-03");
        } finally {
            businessDates.invalidate();
        }
    }

    private String accrue(String txn, String type, String status, String amount, UUID agent) {
        return "{\"wallet_transaction_id\":\"" + txn + "\",\"txn_type\":\"" + type + "\",\"status\":\"" + status
                + "\",\"amount\":\"" + amount + "\",\"fee_amount\":\"0\",\"currency\":\"KES\",\"channel\":\"AGENT_APP\","
                + "\"kyc_tier\":1,\"agent_id\":\"" + agent + "\",\"completed_at\":\"" + OffsetDateTime.now(ZoneOffset.UTC) + "\"}";
    }

    private String runBody(LocalDate from, LocalDate to) {
        return "{\"payout_frequency\":\"DAILY\",\"period_start\":\"" + from + "\",\"period_end\":\"" + to
                + "\",\"currency\":\"KES\"}";
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        return send("POST", path, body, UUID.randomUUID().toString(), user, TENANT, expected);
    }

    private JsonNode get(String path) throws Exception {
        return send("GET", path, null, null, "ops", TENANT, 200);
    }

    private JsonNode send(String method, String path, String body, String key, String user, String tenant, int expected)
            throws Exception {
        HttpResponse<String> r = raw(method, path, body, key, user, tenant);
        assertThat(r.statusCode()).as(method + " " + path + " -> " + r.body()).isEqualTo(expected);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private HttpResponse<String> raw(String method, String path, String body, String key, String user, String tenant)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        if ("PATCH".equals(method)) {
            b.header("If-Match", "\"1\"");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
