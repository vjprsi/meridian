package io.meridian.agentnetwork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
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
 * agent-network-service end to end over HTTP against PostgreSQL 18 (RLS enforced). wallet-service and
 * commission-service are a stateful {@link FakeWalletService} behind the mocked {@link ServiceClient}: agent wallets
 * are opened and activated there and every float movement is a FLOAT_TRANSFER whose balances are asserted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.agent-network.treasury-wallet-id=0199c000-0000-7000-8000-0000000000f1"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentNetworkFlowIT {
    static final String TENANT = "0199c000-0000-7000-8000-00000000a001";
    static final String OTHER_TENANT = "0199c000-0000-7000-8000-00000000a002";
    static final UUID TREASURY = UUID.fromString("0199c000-0000-7000-8000-0000000000f1");
    static final UUID PLAN = UUID.fromString("0199c000-0000-7000-8000-0000000000c1");
    static final UUID DRAFT_PLAN = UUID.fromString("0199c000-0000-7000-8000-0000000000c2");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakeWalletService wallets;

    String region;
    String superAgent;
    UUID superFloat;
    String agent;
    UUID agentFloat;
    String subAgent;
    UUID subFloat;
    String outletNear;
    String outletFar;
    String till;

    @BeforeAll
    void setUp() {
        wallets = new FakeWalletService(json);
        wallets.wallet(TREASURY, "TREASURY", "KES", "1000000");
        wallets.plans.put(PLAN, "{\"id\":\"" + PLAN + "\",\"status\":\"ACTIVE\",\"currency\":\"KES\",\"applies_to\":\"AGENT\"}");
        wallets.plans.put(DRAFT_PLAN, "{\"id\":\"" + DRAFT_PLAN + "\",\"status\":\"DRAFT\",\"currency\":\"KES\",\"applies_to\":\"AGENT\"}");
    }

    @BeforeEach
    void stub() {
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> wallets.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        when(serviceClient.get(anyString(), anyString()))
                .thenAnswer(i -> wallets.get(i.getArgument(0), i.getArgument(1)));
    }

    // ------------------------------------------------------------------ 1. territories and hierarchy

    @Test
    @Order(1)
    void territoriesAndSuperAgentOnboardingWithFourEyes() throws Exception {
        region = post("/v1/agent-territories", """
                {"code":"NBI","name":"Nairobi","territory_level":"REGION","country":"KE"}""", "ops1", 201).get("id").asString();
        String district = post("/v1/agent-territories", """
                {"code":"NBI-KIB","name":"Kibera","territory_level":"DISTRICT","country":"KE","parent_id":"%s"}"""
                .formatted(region), "ops1", 201).get("id").asString();
        post("/v1/agent-territories", """
                {"code":"NBI","name":"Dup","territory_level":"REGION","country":"KE"}""", "ops1", 409);
        JsonNode cycle = patch("/v1/agent-territories/" + region, "{\"parent_id\":\"" + district + "\"}", 422);
        assertThat(cycle.get("code").asString()).isEqualTo("territory_cycle");

        post("/v1/agents", """
                {"agent_level":"SUB_AGENT","legal_name":"Orphan","contact_msisdn":"+254711000009","currency":"KES"}""",
                "ops1", 422, "invalid_hierarchy");
        JsonNode sa = post("/v1/agents", agentBody("SUPER_AGENT", null, "Mega Distributors Ltd"), "ops1", 201);
        superAgent = sa.get("id").asString();
        assertThat(sa.get("status").asString()).isEqualTo("ONBOARDING");
        assertThat(sa.get("agent_code").asString()).startsWith("SA").hasSize(8);
        assertThat(sa.hasNonNull("super_agent_id")).isFalse();

        post("/v1/agents/" + superAgent + "/activate", "{}", "ops1", 422, "four_eyes_violation");
        post("/v1/agents/" + superAgent + "/activate", "{}", "ops2", 422, "kyc_incomplete");
        String doc = post("/v1/agent-kyc-documents", """
                {"agent_id":"%s","document_type":"BUSINESS_LICENSE","document_id":"%s","expires_at":"2030-01-01"}"""
                .formatted(superAgent, UUID.randomUUID()), "ops1", 201).get("id").asString();
        post("/v1/agent-kyc-documents", """
                {"agent_id":"%s","document_type":"ID","document_id":"%s","expires_at":"2001-01-01"}"""
                .formatted(superAgent, UUID.randomUUID()), "ops1", 422, "document_expired");
        post("/v1/agent-kyc-documents/" + doc + "/verify", "{}", "ops1", 422, "four_eyes_violation");
        assertThat(post("/v1/agent-kyc-documents/" + doc + "/verify", "{}", "ops2", 200).get("verified").asBoolean()).isTrue();
        assertThat(raw("DELETE", "/v1/agent-kyc-documents/" + doc, null, null, "ops1", TENANT).statusCode()).isEqualTo(409);

        JsonNode active = post("/v1/agents/" + superAgent + "/activate", "{\"comment\":\"KYB complete\"}", "ops2", 200);
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(active.get("kyc_status").asString()).isEqualTo("VERIFIED");
        assertThat(active.get("onboarded_at").isNull()).isFalse();
        superFloat = UUID.fromString(active.get("float_wallet_id").asString());
        UUID commission = UUID.fromString(active.get("commission_wallet_id").asString());
        assertThat(wallets.type(superFloat)).isEqualTo("SUPER_AGENT");
        assertThat(wallets.status(superFloat)).isEqualTo("ACTIVE");
        assertThat(wallets.type(commission)).isEqualTo("COMMISSION");
        assertThat(wallets.calls("POST", "/v1/wallets")).filteredOn(c -> c.path().equals("/v1/wallets")).hasSize(2);
        JsonNode floats = get("/v1/agents/" + superAgent + "/float");
        assertThat(floats.get("data")).hasSize(1);
        assertThat(floats.get("data").get(0).get("wallet_id").asString()).isEqualTo(superFloat.toString());
        post("/v1/agents/" + superAgent + "/activate", "{}", "ops2", 409);
        // PATCH cannot move the agent in the hierarchy
        assertThat(patch("/v1/agents/" + superAgent, "{\"agent_level\":\"AGENT\"}", 422).get("code").asString())
                .isEqualTo("field_not_updatable");
    }

    @Test
    @Order(2)
    void agentsAndSubAgentsBuildTheTree() throws Exception {
        post("/v1/agents", agentBody("SUB_AGENT", superAgent, "Wrong parent"), "ops1", 422, "invalid_hierarchy");
        agent = post("/v1/agents", agentBody("AGENT", superAgent, "Kibera Shop"), "ops1", 201).get("id").asString();
        subAgent = post("/v1/agents", agentBody("SUB_AGENT", agent, "Kibera Kiosk"), "ops1", 201).get("id").asString();
        assertThat(get("/v1/agents/" + subAgent).get("super_agent_id").asString()).isEqualTo(superAgent);
        verifiedDoc(agent);
        verifiedDoc(subAgent);
        post("/v1/agents/" + subAgent + "/activate", "{}", "ops2", 422, "parent_not_active");
        agentFloat = UUID.fromString(post("/v1/agents/" + agent + "/activate", "{}", "ops2", 200).get("float_wallet_id").asString());
        subFloat = UUID.fromString(post("/v1/agents/" + subAgent + "/activate", "{}", "ops2", 200).get("float_wallet_id").asString());
        assertThat(wallets.type(agentFloat)).isEqualTo("AGENT");

        JsonNode tree = get("/v1/agents/" + superAgent + "/hierarchy");
        assertThat(tree.get("upline")).isEmpty();
        assertThat(tree.get("downline_count").asInt()).isEqualTo(2);
        assertThat(tree.get("downline").get(0).get("agent").get("id").asString()).isEqualTo(agent);
        assertThat(tree.get("downline").get(0).get("children").get(0).get("agent").get("id").asString()).isEqualTo(subAgent);
        JsonNode up = get("/v1/agents/" + subAgent + "/hierarchy");
        assertThat(up.get("upline")).hasSize(2);
        assertThat(up.get("upline").get(1).get("id").asString()).isEqualTo(superAgent);
        assertThat(get("/v1/agents/" + superAgent + "/sub-agents").get("data")).hasSize(1);

        post("/v1/agents/" + agent + "/assign-commission-plan", """
                {"commission_plan_id":"%s","effective_from":"2026-01-01"}""".formatted(DRAFT_PLAN), "ops1", 422, "plan_not_active");
        JsonNode assigned = post("/v1/agents/" + agent + "/assign-commission-plan", """
                {"commission_plan_id":"%s","effective_from":"2026-01-01"}""".formatted(PLAN), "ops1", 200);
        assertThat(assigned.get("commission_plan_id").asString()).isEqualTo(PLAN.toString());
    }

    // ------------------------------------------------------------------ 2. float management

    @Test
    @Order(3)
    void floatTopUpRebalanceAndFailures() throws Exception {
        post("/v1/agent-float-requests", floatRequest("TOP_UP", superAgent, null, superFloat, "100000", null),
                "ops1", 422, "funding_proof_required");
        String topUp = post("/v1/agent-float-requests", floatRequest("TOP_UP", superAgent, null, superFloat, "100000", "BNK-001"),
                "ops1", 201).get("id").asString();
        post("/v1/agent-float-requests/" + topUp + "/approve", "{}", "ops1", 422, "four_eyes_violation");
        JsonNode done = post("/v1/agent-float-requests/" + topUp + "/approve", "{\"comment\":\"Deposit seen\"}", "ops2", 200);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("approved_by").asString()).isEqualTo("ops2");
        assertThat(done.get("wallet_transaction_id").isNull()).isFalse();
        assertThat(wallets.balance(superFloat)).isEqualByComparingTo("100000");
        assertThat(wallets.balance(TREASURY)).isEqualByComparingTo("900000");
        FakeWalletService.Call transfer = wallets.calls("POST", "/v1/wallet-transactions").getLast();
        assertThat(transfer.body().get("txn_type").asString()).isEqualTo("FLOAT_TRANSFER");
        assertThat(transfer.body().get("initiator_type").asString()).isEqualTo("SYSTEM");
        assertThat(transfer.key()).isEqualTo("afr-" + topUp + "-transfer");
        post("/v1/agent-float-requests/" + topUp + "/cancel", "{}", "ops1", 409);

        String rebalance = post("/v1/agent-float-requests", floatRequest("REBALANCE", agent, superAgent, agentFloat, "30000", null),
                "ops1", 201).get("id").asString();
        assertThat(get("/v1/agent-float-requests/" + rebalance).get("source_wallet_id").asString()).isEqualTo(superFloat.toString());
        post("/v1/agent-float-requests/" + rebalance + "/approve", "{}", "ops2", 200);
        assertThat(wallets.balance(agentFloat)).isEqualByComparingTo("30000");
        assertThat(wallets.balance(superFloat)).isEqualByComparingTo("70000");

        // the sub-agent asks its parent agent for more than the agent holds: execution fails and is recorded
        String tooMuch = post("/v1/agent-float-requests", floatRequest("TRANSFER", subAgent, agent, subFloat, "45000", null),
                "ops1", 201).get("id").asString();
        JsonNode failed = post("/v1/agent-float-requests/" + tooMuch + "/approve", "{}", "ops2", 200);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("failure_reason").asString()).contains("insufficient_funds");
        assertThat(wallets.balance(subFloat)).isEqualByComparingTo("0");

        String rejected = post("/v1/agent-float-requests", floatRequest("TRANSFER", subAgent, agent, subFloat, "500", null),
                "ops1", 201).get("id").asString();
        assertThat(post("/v1/agent-float-requests/" + rejected + "/reject", "{\"reason\":\"No need\"}", "ops2", 200)
                .get("status").asString()).isEqualTo("REJECTED");
        post("/v1/agent-float-requests/" + rejected + "/approve", "{}", "ops2", 409);
        post("/v1/agent-float-requests", floatRequest("TRANSFER", subAgent, agent, superFloat, "500", null),
                "ops1", 422, "invalid_target_wallet");
        post("/v1/agent-float-requests", floatRequest("WITHDRAW", agent, null, agentFloat, "500", null),
                "ops1", 422, "invalid_target_wallet");
        String withdraw = post("/v1/agent-float-requests", floatRequest("WITHDRAW", agent, null, TREASURY, "1000", null),
                "ops1", 201).get("id").asString();
        post("/v1/agent-float-requests/" + withdraw + "/approve", "{}", "ops2", 200);
        assertThat(wallets.balance(agentFloat)).isEqualByComparingTo("29000");
    }

    @Test
    @Order(4)
    void lowFloatAlertAndAutomaticRebalance() throws Exception {
        JsonNode f = get("/v1/agents/" + agent + "/float").get("data").get(0);
        String floatId = f.get("id").asString();
        patch("/v1/agent-floats/" + floatId, """
                {"low_float_alert_threshold":"40000","max_balance":"20000"}""", 422);
        patch("/v1/agent-floats/" + floatId, """
                {"low_float_alert_threshold":"40000","max_balance":"80000","auto_rebalance":true,"rebalance_amount":"20000"}""", 200);
        JsonNode check = post("/v1/agent-floats/" + floatId + "/check", null, "scheduler", 200);
        assertThat(check.get("status").asString()).isEqualTo("LOW");
        assertThat(check.get("alert_raised").asBoolean()).isTrue();
        String req = check.get("rebalance_request_id").asString();
        JsonNode rebalance = get("/v1/agent-float-requests/" + req);
        assertThat(rebalance.get("request_type").asString()).isEqualTo("REBALANCE");
        assertThat(rebalance.get("source_agent_id").asString()).isEqualTo(superAgent);
        assertThat(rebalance.get("amount").asString()).startsWith("20000");
        JsonNode again = post("/v1/agent-floats/" + floatId + "/check", null, "scheduler", 200);
        assertThat(again.get("alert_raised").asBoolean()).isFalse();
        assertThat(again.hasNonNull("rebalance_request_id")).isFalse();
        assertThat(get("/v1/agent-floats/" + floatId).get("last_balance").asString()).startsWith("29000");
    }

    // ------------------------------------------------------------------ 3. outlets, tills, devices, cash

    @Test
    @Order(5)
    void outletsAndNearbySearch() throws Exception {
        post("/v1/agent-outlets", outlet(agent, "Bad", "95", "36.8"), "ops1", 422, "invalid_location");
        outletNear = post("/v1/agent-outlets", outlet(agent, "Kibera Main", "-1.3133", "36.7876"), "ops1", 201).get("id").asString();
        String mid = post("/v1/agent-outlets", outlet(subAgent, "Olympic Estate", "-1.3100", "36.7700"), "ops1", 201).get("id").asString();
        outletFar = post("/v1/agent-outlets", outlet(agent, "Westlands", "-1.2676", "36.8108"), "ops1", 201).get("id").asString();
        assertThat(get("/v1/agent-outlets/" + outletNear).get("outlet_code").asString()).startsWith("OT");
        assertThat(get("/v1/agent-outlets/" + outletNear).get("agent_territory_id").asString()).isEqualTo(region);

        JsonNode near = get("/v1/agent-outlets/nearby?lat=-1.3140&lon=36.7880&radius_m=3000");
        assertThat(near.get("data")).hasSize(2);
        assertThat(near.get("data").get(0).get("id").asString()).isEqualTo(outletNear);
        assertThat(near.get("data").get(0).get("distance_m").asInt()).isLessThan(200);
        assertThat(near.get("data").get(1).get("id").asString()).isEqualTo(mid);
        JsonNode wide = get("/v1/agent-outlets/nearby?lat=-1.3140&lon=36.7880&radius_m=10000&limit=2");
        assertThat(wide.get("data")).hasSize(2);
        assertThat(wide.get("has_more").asBoolean()).isTrue();
        JsonNode page2 = get("/v1/agent-outlets/nearby?lat=-1.3140&lon=36.7880&radius_m=10000&limit=2&cursor="
                + wide.get("next_cursor").asString());
        assertThat(page2.get("data")).hasSize(1);
        assertThat(page2.get("data").get(0).get("id").asString()).isEqualTo(outletFar);
        JsonNode bills = get("/v1/agent-outlets/nearby?lat=-1.3140&lon=36.7880&radius_m=10000&service=BILL_PAY");
        assertThat(bills.get("data")).isEmpty();
        assertThat(raw("GET", "/v1/agent-outlets/nearby?lat=-1.3&lon=36.7&radius_m=900000", null, null, "ops", TENANT)
                .statusCode()).isEqualTo(400);
    }

    @Test
    @Order(6)
    void tillShiftWithCashCountVariance() throws Exception {
        till = post("/v1/agent-tills", """
                {"agent_outlet_id":"%s","till_number":"KIB-01","currency":"KES"}""".formatted(outletNear), "ops1", 201).get("id").asString();
        String operator = UUID.randomUUID().toString();
        post("/v1/agent-tills/" + till + "/open", """
                {"operator_user_id":"%s","opening_cash":"10000","denominations":{"1000":5,"500":4}}""".formatted(operator),
                "teller", 422, "denominations_mismatch");
        JsonNode open = post("/v1/agent-tills/" + till + "/open", """
                {"operator_user_id":"%s","opening_cash":"10000","denominations":{"1000":8,"500":4}}""".formatted(operator), "teller", 200);
        assertThat(open.get("status").asString()).isEqualTo("OPEN");
        post("/v1/agent-tills/" + till + "/open", """
                {"operator_user_id":"%s","opening_cash":"10000"}""".formatted(operator), "teller", 409);
        post("/v1/agent-outlets/" + outletNear + "/close", "{\"reason\":\"moving\"}", "ops1", 422, "open_tills");

        wallets.summaries.put("outlet:" + outletNear, """
                {"groups":[{"txn_type":"CASH_IN","status":"COMPLETED","currency":"KES","count":3,"amount":"5000.00","distinct_payers":1},
                           {"txn_type":"CASH_OUT","status":"COMPLETED","currency":"KES","count":2,"amount":"2000.00","distinct_payers":2}]}""");
        JsonNode closed = post("/v1/agent-tills/" + till + "/close", "{\"declared_cash\":\"12900\"}", "teller", 200);
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("expected_cash").asString()).startsWith("13000");
        assertThat(closed.get("variance").asString()).startsWith("-100");
        assertThat(closed.get("variance_status").asString()).isEqualTo("SHORT");
        assertThat(closed.get("cash_in_total").asString()).startsWith("5000");
        FakeWalletService.Call summary = wallets.calls("GET", "/v1/wallet-transactions/summary").getLast();
        assertThat(summary.path()).contains("agent_outlet_id=" + outletNear).contains("status=COMPLETED");
        post("/v1/agent-tills/" + till + "/close", "{\"declared_cash\":\"1\"}", "teller", 409);
    }

    @Test
    @Order(7)
    void devicesAndCashManagement() throws Exception {
        String device = post("/v1/agent-devices", """
                {"agent_id":"%s","agent_outlet_id":"%s","device_type":"POS","serial_number":"SN-0001"}""".formatted(agent, outletNear),
                "ops1", 201).get("id").asString();
        post("/v1/agent-devices", """
                {"agent_id":"%s","agent_outlet_id":"%s","device_type":"POS","serial_number":"SN-0002"}""".formatted(superAgent, outletNear),
                "ops1", 422, "outlet_mismatch");
        JsonNode hb = post("/v1/agent-devices/" + device + "/heartbeat", "{\"latitude\":\"-1.31\",\"longitude\":\"36.78\",\"app_version\":\"2.1\"}",
                "device", 200);
        assertThat(hb.get("last_seen_at").isNull()).isFalse();
        assertThat(hb.get("app_version").asString()).isEqualTo("2.1");
        assertThat(post("/v1/agent-devices/" + device + "/report-lost", "{\"reason\":\"stolen\"}", "ops1", 200)
                .get("status").asString()).isEqualTo("LOST");
        post("/v1/agent-devices/" + device + "/heartbeat", "{}", "device", 409);
        post("/v1/agent-devices/" + device + "/report-lost", "{}", "ops1", 409);
        assertThat(post("/v1/agent-devices/" + device + "/retire", "{}", "ops1", 200).get("status").asString()).isEqualTo("RETIRED");

        String cmr = post("/v1/cash-management-requests", """
                {"agent_outlet_id":"%s","request_type":"CASH_DELIVERY","amount":"50000","currency":"KES"}""".formatted(outletNear),
                "ops1", 201).get("id").asString();
        post("/v1/cash-management-requests/" + cmr + "/complete", "{}", "ops1", 409);
        post("/v1/cash-management-requests/" + cmr + "/approve", "{}", "ops1", 422, "four_eyes_violation");
        post("/v1/cash-management-requests/" + cmr + "/approve", "{}", "ops2", 200);
        JsonNode done = post("/v1/cash-management-requests/" + cmr + "/complete", "{\"cit_reference\":\"G4S-77\"}", "ops1", 200);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("completed_at").isNull()).isFalse();
        post("/v1/cash-management-requests/" + cmr + "/cancel", "{}", "ops1", 409);
    }

    // ------------------------------------------------------------------ 4. KPIs, suspension, termination

    @Test
    @Order(8)
    void dailyPerformanceAggregationIsIdempotent() throws Exception {
        wallets.summaries.put("agent:" + agent, """
                {"groups":[{"txn_type":"CASH_IN","status":"COMPLETED","currency":"KES","count":4,"amount":"8000.00","distinct_payers":1},
                           {"txn_type":"CASH_OUT","status":"COMPLETED","currency":"KES","count":2,"amount":"2000.00","distinct_payers":2},
                           {"txn_type":"BILL_PAYMENT","status":"COMPLETED","currency":"KES","count":1,"amount":"300.00","distinct_payers":1}]}""");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String key = UUID.randomUUID().toString();
        JsonNode run = send("POST", "/v1/agents/eod/performance?business_date=" + today, null, key, "scheduler", TENANT, 200);
        assertThat(run.get("agents").asInt()).isEqualTo(3);
        assertThat(run.get("rows_written").asInt()).isEqualTo(4); // 3 agents + the outlet with activity
        JsonNode replay = send("POST", "/v1/agents/eod/performance?business_date=" + today, null, key, "scheduler", TENANT, 200);
        assertThat(replay.get("rows_written").asInt()).isEqualTo(4);
        JsonNode rerun = post("/v1/agents/eod/performance?business_date=" + today, null, "scheduler", 200);
        assertThat(rerun.get("rows_written").asInt()).isEqualTo(0);
        assertThat(rerun.get("rows_existing").asInt()).isEqualTo(4);

        JsonNode perf = get("/v1/agents/" + agent + "/performance?from=" + today + "&to=" + today);
        JsonNode agentRow = null;
        for (JsonNode r : perf.get("data")) {
            if (!r.hasNonNull("agent_outlet_id")) {
                agentRow = r;
            }
        }
        assertThat(agentRow).isNotNull();
        assertThat(agentRow.get("cash_in_count").asInt()).isEqualTo(4);
        assertThat(agentRow.get("cash_out_amount").asString()).startsWith("2000");
        assertThat(agentRow.get("bill_pay_count").asInt()).isEqualTo(1);
        assertThat(agentRow.get("commission_earned").asString()).startsWith("108");
        assertThat(get("/v1/agents/" + agent + "/performance?from=" + today.minusDays(5) + "&to=" + today.minusDays(1))
                .get("data")).isEmpty();
    }

    @Test
    @Order(9)
    void suspendReactivateAndTerminate() throws Exception {
        JsonNode s = post("/v1/agents/" + agent + "/suspend", "{\"reason\":\"Audit\"}", "ops1", 200);
        assertThat(s.get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(wallets.status(agentFloat)).isEqualTo("SUSPENDED");
        JsonNode nearby = get("/v1/agent-outlets/nearby?lat=-1.3140&lon=36.7880&radius_m=3000");
        assertThat(nearby.get("data")).noneMatch(o -> o.get("agent_id").asString().equals(agent));
        JsonNode back = post("/v1/agents/" + agent + "/activate", "{\"comment\":\"Audit passed\"}", "ops2", 200);
        assertThat(back.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(wallets.status(agentFloat)).isEqualTo("ACTIVE");

        post("/v1/agents/" + superAgent + "/terminate", "{\"reason\":\"Contract ended\"}", "ops1", 422, "active_sub_agents");
        post("/v1/agents/" + agent + "/terminate", "{\"reason\":\"x\"}", "ops1", 422, "active_sub_agents");
        JsonNode term = post("/v1/agents/" + subAgent + "/terminate", """
                {"reason":"Contract ended","effective_date":"2026-12-31"}""", "ops1", 200);
        assertThat(term.get("status").asString()).isEqualTo("TERMINATED");
        assertThat(term.get("contract_end").asString()).isEqualTo("2026-12-31");
        assertThat(get("/v1/agents/" + subAgent + "/outlets").get("data").get(0).get("status").asString()).isEqualTo("CLOSED");
        post("/v1/agents/" + subAgent + "/activate", "{}", "ops2", 409);
        post("/v1/agents/" + agent + "/terminate", "{\"reason\":\"x\"}", "ops1", 422, "float_not_zero");
    }

    @Test
    @Order(10)
    void tenantIsolationAndEvents() throws Exception {
        assertThat(raw("GET", "/v1/agents/" + agent, null, null, "x", OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(json.readTree(raw("GET", "/v1/agents", null, null, "x", OTHER_TENANT).body()).get("data")).isEmpty();
        assertThat(json.readTree(raw("GET", "/v1/agent-outlets/nearby?lat=-1.3140&lon=36.7880", null, null, "x", OTHER_TENANT)
                .body()).get("data")).isEmpty();
        List<String> types = TenantContext.callAs(UUID.fromString(TENANT), "test",
                () -> jdbc.sql("select distinct event_type from outbox").query(String.class).list());
        assertThat(types).contains("wallets.agent.onboarded", "wallets.agent.status_changed",
                "wallets.agent.commission_plan_assigned", "wallets.agent_outlet.closed", "wallets.agent_float.low_balance",
                "wallets.agent_float_request.approved", "wallets.agent_float_request.rejected", "wallets.agent_till.opened",
                "wallets.agent_till.closed", "wallets.agent_device.lost", "wallets.agent_kyc_document.verified",
                "wallets.cash_management_request.completed");
    }

    // ------------------------------------------------------------------ helpers

    private void verifiedDoc(String agentId) throws Exception {
        String d = post("/v1/agent-kyc-documents", """
                {"agent_id":"%s","document_type":"ID","document_id":"%s"}""".formatted(agentId, UUID.randomUUID()), "ops1", 201)
                .get("id").asString();
        post("/v1/agent-kyc-documents/" + d + "/verify", "{}", "ops2", 200);
    }

    private String agentBody(String level, String parent, String name) {
        return "{\"agent_level\":\"" + level + "\"," + (parent == null ? "" : "\"parent_agent_id\":\"" + parent + "\",")
                + "\"legal_name\":\"" + name + "\",\"contact_msisdn\":\"+254711000001\",\"currency\":\"KES\","
                + "\"agent_territory_id\":\"" + region + "\",\"max_float_amount\":\"500000\"}";
    }

    private String floatRequest(String type, String requester, String source, UUID target, String amount, String bankRef) {
        return "{\"request_type\":\"" + type + "\",\"requesting_agent_id\":\"" + requester + "\","
                + (source == null ? "" : "\"source_agent_id\":\"" + source + "\",")
                + "\"target_wallet_id\":\"" + target + "\",\"amount\":\"" + amount + "\",\"currency\":\"KES\""
                + (bankRef == null ? "" : ",\"bank_reference\":\"" + bankRef + "\"") + "}";
    }

    private String outlet(String agentId, String name, String lat, String lon) {
        return "{\"agent_id\":\"" + agentId + "\",\"name\":\"" + name + "\",\"country\":\"KE\",\"latitude\":\"" + lat
                + "\",\"longitude\":\"" + lon + "\",\"services\":[\"CASH_IN\",\"CASH_OUT\"]}";
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        return send("POST", path, body, UUID.randomUUID().toString(), user, TENANT, expected);
    }

    private JsonNode post(String path, String body, String user, int expected, String code) throws Exception {
        JsonNode r = post(path, body, user, expected);
        if (code != null) {
            assertThat(r.path("code").asString()).as(path).isEqualTo(code);
        }
        return r;
    }

    private JsonNode patch(String path, String body, int expected) throws Exception {
        JsonNode current = get(path);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "ops1").header("Content-Type", "application/json")
                .header("If-Match", "\"" + current.get("row_version").asInt() + "\"")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as("PATCH " + path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
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
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
