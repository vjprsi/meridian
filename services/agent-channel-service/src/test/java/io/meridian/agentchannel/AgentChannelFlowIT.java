package io.meridian.agentchannel;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.agentchannel.application.DeviceKeys;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * agent-channel-service over HTTP (dev security mode: X-Tenant-Id, X-Agent-Id, X-User, X-Auth-Time) against
 * PostgreSQL 18, with the domain services replaced by a stateful {@link FakeNetwork} behind the mocked
 * {@link ServiceClient}. Two agents of one tenant, A and B (siblings under super-agent P), each own one object of
 * every family; S is A's sub-agent.
 *
 * <p>Proves: the happy paths; A can neither read nor act on B's outlets, tills, devices, float and cash requests,
 * transactions, receipts, bill payments, top-ups, applications, documents and offline batches (404, nothing written
 * downstream); bodies are rebuilt server-side; device binding (registration, ES256 and Ed25519 batch signatures, wrong
 * key, tampered payload, revoked device); step-up (recent login or device proof); idempotency and reconciliation of
 * float requests, offline batches and cash-ins; masked, rate-limited customer lookup; non-agents refused; the audit
 * trail and denial events.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.agent-channel.step-up-max-age=PT5M", "meridian.agent-channel.lookups-per-hour=6"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentChannelFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000a701";
    static final String P = "0199a000-0000-7000-8000-0000000a0000";
    static final String A = "0199a000-0000-7000-8000-0000000a00a1";
    static final String B = "0199a000-0000-7000-8000-0000000a00b1";
    static final String S = "0199a000-0000-7000-8000-0000000a00c1";

    static final String NET = "agent-network-service";
    static final String WAL = "wallet-service";
    static final String BIL = "biller-service";
    static final String ONB = "onboarding-service";
    static final String DOC = "document-service";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    FakeNetwork net;
    final HttpClient http = HttpClient.newHttpClient();

    /** Objects of each agent: [A, B]. */
    String[] floatWallet = new String[2];
    String[] commissionWallet = new String[2];
    String[] outlet = new String[2];
    String[] till = new String[2];
    String[] floatRequest = new String[2];
    String[] cashRequest = new String[2];
    String[] cashOut = new String[2];
    String[] billPayment = new String[2];
    String[] topup = new String[2];
    String[] earning = new String[2];
    String[] application = new String[2];
    String[] document = new String[2];
    String[] batch = new String[2];
    String deviceB;
    String customerWallet;
    String merchantWallet;
    String biller;
    String agentJourney;
    String webJourney;

    // device binding state
    KeyPair keyA;
    String deviceA;

    @BeforeAll
    void seed() throws Exception {
        net = new FakeNetwork(json);
        net.add(NET, "/v1/agents", agent(P, null, "SUPER_AGENT", "PFLOAT"));
        String[] who = {A, B};
        for (int i = 0; i < 2; i++) {
            String ag = who[i];
            floatWallet[i] = UUID.randomUUID().toString();
            commissionWallet[i] = UUID.randomUUID().toString();
            net.add(NET, "/v1/agents", agent(ag, P, "AGENT", floatWallet[i])
                    .replace("\"commission_wallet_id\":null", "\"commission_wallet_id\":\"" + commissionWallet[i] + "\""));
            net.add(WAL, "/v1/wallets", """
                    {"id":"%s","wallet_number":"F%d","wallet_type":"FLOAT","agent_id":"%s","display_name":"Float %d",
                     "currency":"KES","status":"ACTIVE","kyc_tier":3,"is_default":true,"has_pin":true}"""
                    .formatted(floatWallet[i], i, ag, i));
            net.add(WAL, "/v1/wallets", """
                    {"id":"%s","wallet_number":"C%d","wallet_type":"COMMISSION","agent_id":"%s","display_name":"Comm %d",
                     "currency":"KES","status":"ACTIVE","kyc_tier":3,"is_default":true,"has_pin":false}"""
                    .formatted(commissionWallet[i], i, ag, i));
            outlet[i] = id(net.add(NET, "/v1/agent-outlets", """
                    {"agent_id":"%s","outlet_code":"O%d","name":"Outlet %d","country":"KE","status":"ACTIVE"}"""
                    .formatted(ag, i, i)));
            net.add(NET, "/v1/agent-floats", """
                    {"agent_id":"%s","wallet_id":"%s","currency":"KES","auto_rebalance":false,"low_float_alert_threshold":"100"}"""
                    .formatted(ag, floatWallet[i]));
            net.add(NET, "/v1/agent-performance", """
                    {"agent_id":"%s","business_date":"2026-10-07","currency":"KES","cash_in_count":%d,"cash_in_amount":"10",
                     "cash_out_count":0,"cash_out_amount":"0","onboarding_count":0,"bill_pay_count":0,
                     "commission_earned":"0","active_customers":0}""".formatted(ag, i + 1));
            till[i] = id(net.add(NET, "/v1/agent-tills", """
                    {"agent_outlet_id":"%s","till_number":"T%d","status":"CLOSED","currency":"KES"}""".formatted(outlet[i], i)));
            floatRequest[i] = id(net.add(NET, "/v1/agent-float-requests", """
                    {"request_type":"TOP_UP","requesting_agent_id":"%s","target_wallet_id":"%s","amount":"100.00",
                     "currency":"KES","status":"REQUESTED"}""".formatted(ag, floatWallet[i])));
            cashRequest[i] = id(net.add(NET, "/v1/cash-management-requests", """
                    {"agent_outlet_id":"%s","request_type":"CASH_DELIVERY","amount":"500.00","currency":"KES",
                     "status":"REQUESTED"}""".formatted(outlet[i])));
            cashOut[i] = id(net.add(WAL, "/v1/wallet-transactions", """
                    {"reference":"R%d","txn_type":"CASH_OUT","status":"PENDING_CONFIRMATION","channel":"AGENT_APP",
                     "agent_id":"%s","payee_wallet_id":"%s","amount":"20.00","currency":"KES","initiator_type":"AGENT",
                     "idempotency_key":"seed-%d"}""".formatted(i, ag, floatWallet[i], i)));
            billPayment[i] = id(net.add(BIL, "/v1/bill-payments", """
                    {"biller_id":"%s","payer_wallet_id":"%s","channel":"AGENT_APP","account_reference":"12345",
                     "amount":"30.00","currency":"KES","status":"COMPLETED","idempotency_key":"seed-bp-%d"}"""
                    .formatted(UUID.randomUUID(), floatWallet[i], i)));
            topup[i] = id(net.add(BIL, "/v1/airtime-topups", """
                    {"biller_id":"%s","payer_wallet_id":"%s","channel":"AGENT_APP","target_msisdn":"+254711000000",
                     "amount":"10.00","currency":"KES","status":"COMPLETED","idempotency_key":"seed-tu-%d"}"""
                    .formatted(UUID.randomUUID(), floatWallet[i], i)));
            earning[i] = id(net.add("commission-service", "/v1/commission-earnings", """
                    {"beneficiary_agent_id":"%s","beneficiary_wallet_id":"%s","txn_type":"CASH_IN","beneficiary":"AGENT",
                     "base_amount":"100","commission_amount":"1","net_amount":"1","currency":"KES"}"""
                    .formatted(ag, commissionWallet[i])));
            application[i] = id(net.add(ONB, "/v1/onboarding/applications", """
                    {"application_number":"APP-%d","onboarding_journey_id":"%s","status":"STARTED","channel":"AGENT",
                     "agent_id":"%s","phone":"+25470000010%d","progress_percent":0}"""
                    .formatted(i, UUID.randomUUID(), ag, i)));
            document[i] = id(net.add(DOC, "/v1/documents", """
                    {"owner_type":"ONBOARDING_APPLICATION","owner_id":"%s","document_type":"ID_CARD","status":"UPLOADING"}"""
                    .formatted(application[i])));
            batch[i] = id(net.add(ONB, "/v1/onboarding/offline-batches", """
                    {"agent_id":"%s","device_fingerprint":"x","captured_from":"2026-10-07T08:00:00Z",
                     "captured_to":"2026-10-07T08:00:00Z","application_count":1,"payload_uri":"data:x",
                     "payload_signature":"sha256:00","status":"APPLIED"}""".formatted(ag)));
        }
        net.add(NET, "/v1/agents", agent(S, A, "SUB_AGENT", "SFLOAT"));
        net.add(NET, "/v1/agent-performance", """
                {"agent_id":"%s","business_date":"2026-10-07","currency":"KES","cash_in_count":7,"cash_in_amount":"70",
                 "cash_out_count":0,"cash_out_amount":"0","onboarding_count":0,"bill_pay_count":0,
                 "commission_earned":"0","active_customers":0}""".formatted(S));
        deviceB = id(net.add(NET, "/v1/agent-devices", """
                {"agent_id":"%s","agent_outlet_id":"%s","device_type":"SMARTPHONE","serial_number":"SER-B","status":"ACTIVE"}"""
                .formatted(B, outlet[1])));
        customerWallet = id(net.add(WAL, "/v1/wallets", """
                {"wallet_number":"W100","wallet_type":"CUSTOMER","customer_id":"%s","msisdn":"+254700000001",
                 "display_name":"Jane Wanjiru","currency":"KES","status":"ACTIVE","kyc_tier":1,"is_default":true,
                 "has_pin":true}""".formatted(UUID.randomUUID())));
        merchantWallet = id(net.add(WAL, "/v1/wallets", """
                {"wallet_number":"M100","wallet_type":"MERCHANT","msisdn":"+254700000009","display_name":"Shop",
                 "currency":"KES","status":"ACTIVE","kyc_tier":2,"is_default":true,"has_pin":true}"""));
        biller = id(net.add(BIL, "/v1/billers", """
                {"biller_code":"KPLC","name":"Power","category":"ELECTRICITY","country":"KE","currency":"KES",
                 "integration_type":"API","status":"ACTIVE","supports_inquiry":true,"supports_partial_payment":false,
                 "supports_overpayment":false,"account_reference_checksum":"NONE","inquiry_validity_minutes":30,
                 "settlement_frequency":"DAILY","fee_bearer":"CUSTOMER"}"""));
        net.add(BIL, "/v1/biller-products", """
                {"biller_id":"%s","product_code":"P1","name":"Token","product_type":"TOKEN","currency":"KES","is_active":true}"""
                .formatted(biller));
        agentJourney = id(net.add(ONB, "/v1/onboarding/journeys", """
                {"code":"AGT","version":1,"name":"Agent wallet","status":"PUBLISHED","customer_type":"INDIVIDUAL",
                 "channels":["AGENT","MOBILE_APP"],"target_tier":"TIER_1","definition":{},"expiry_days":30}"""));
        webJourney = id(net.add(ONB, "/v1/onboarding/journeys", """
                {"code":"WEB","version":1,"name":"Web only","status":"PUBLISHED","customer_type":"INDIVIDUAL",
                 "channels":["WEB"],"target_tier":"TIER_1","definition":{},"expiry_days":30}"""));
        net.rows(ONB, "/v1/onboarding/steps");
        keyA = es256();
    }

    private static String agent(String id, String parent, String level, String floatWallet) {
        return """
                {"id":"%s","agent_code":"AG-%s","agent_level":"%s","parent_agent_id":%s,"super_agent_id":%s,
                 "legal_name":"Agent %s","contact_msisdn":"+254722000000","currency":"KES","status":"ACTIVE",
                 "float_wallet_id":"%s","commission_wallet_id":null}"""
                .formatted(id, id.substring(id.length() - 3), level, parent == null ? "null" : "\"" + parent + "\"",
                        parent == null ? "null" : "\"" + P + "\"", id.substring(id.length() - 3),
                        floatWallet.length() == 36 ? floatWallet : UUID.nameUUIDFromBytes(floatWallet.getBytes()).toString());
    }

    @BeforeEach
    void wire() {
        net.wire(serviceClient);
    }

    // ------------------------------------------------------------------ happy paths

    @Test
    @Order(1)
    void readsTheSignedInAgentsOwnData() {
        assertThat(get(A, "/v1/agent/me").path("id").asString()).isEqualTo(A);
        assertThat(ids(get(A, "/v1/agent/me/outlets"))).containsExactly(outlet[0]);
        assertThat(get(A, "/v1/agent/me/floats").path("data").get(0).path("wallet_id").asString()).isEqualTo(floatWallet[0]);
        assertThat(get(A, "/v1/agent/me/float-wallet").path("id").asString()).isEqualTo(floatWallet[0]);
        assertThat(get(A, "/v1/agent/me/float-balance").path("available_balance").asString()).isEqualTo("900.00");
        assertThat(get(A, "/v1/agent/me/commission-balance").path("currency").asString()).isEqualTo("KES");
        assertThat(get(A, "/v1/agent/me/performance?from=2026-10-07&to=2026-10-07").path("data").get(0)
                .path("cash_in_count").asInt()).isEqualTo(1);
        assertThat(get(A, "/v1/agent/me/performance?sub_agent_id=" + S).path("data").get(0).path("agent_id").asString())
                .isEqualTo(S);                                                    // hierarchy: read-only sub-agent KPIs
        assertThat(ids(get(A, "/v1/agent/me/sub-agents"))).containsExactly(S);
        assertThat(ids(get(B, "/v1/agent/me/sub-agents"))).isEmpty();

        assertThat(ids(get(A, "/v1/agent/tills"))).containsExactly(till[0]);
        assertThat(ids(get(A, "/v1/agent/tills?agent_outlet_id=" + outlet[0]))).containsExactly(till[0]);
        assertThat(get(A, "/v1/agent/tills/" + till[0]).path("till_number").asString()).isEqualTo("T0");
        assertThat(ids(get(A, "/v1/agent/cash-requests"))).containsExactly(cashRequest[0]);
        assertThat(ids(get(A, "/v1/agent/float-requests"))).containsExactly(floatRequest[0]);
        assertThat(get(A, "/v1/agent/float-requests/" + floatRequest[0]).path("id").asString()).isEqualTo(floatRequest[0]);

        assertThat(ids(get(A, "/v1/agent/transactions"))).contains(cashOut[0]).doesNotContain(cashOut[1]);
        assertThat(get(A, "/v1/agent/transactions/" + cashOut[0]).path("txn_type").asString()).isEqualTo("CASH_OUT");
        HttpResponse<String> receipt = call(A, now(), "GET", "/v1/agent/transactions/" + cashOut[0] + "/receipt", null);
        assertThat(receipt.statusCode()).isEqualTo(200);
        assertThat(receipt.body()).startsWith("RECEIPT " + cashOut[0]);
        assertThat(ids(get(A, "/v1/agent/bill-payments"))).containsExactly(billPayment[0]);
        assertThat(call(A, now(), "GET", "/v1/agent/bill-payments/" + billPayment[0] + "/receipt", null).statusCode())
                .isEqualTo(200);
        assertThat(ids(get(A, "/v1/agent/airtime-topups"))).containsExactly(topup[0]);
        assertThat(ids(get(A, "/v1/agent/commissions"))).containsExactly(earning[0]);
        assertThat(get(A, "/v1/agent/commissions/summary").path("groups").get(0).path("beneficiary_agent_id").asString())
                .isEqualTo(A);
        assertThat(ids(get(A, "/v1/agent/billers"))).containsExactly(biller);
        assertThat(get(A, "/v1/agent/billers/" + biller + "/products").path("data")).hasSize(1);
        assertThat(ids(get(A, "/v1/agent/onboarding/journeys"))).containsExactly(agentJourney);
        assertThat(ids(get(A, "/v1/agent/onboarding/applications"))).containsExactly(application[0]);
        assertThat(get(A, "/v1/agent/onboarding/applications/" + application[0] + "/next-step").path("step_code")
                .asString()).isEqualTo("PERSONAL");
        assertThat(ids(get(A, "/v1/agent/offline-batches"))).containsExactly(batch[0]);
        assertThat(ids(get(A, "/v1/agent/devices"))).isEmpty();
        assertThat(net.writes()).isEmpty();
    }

    @Test
    @Order(2)
    void writesOnTheAgentsOwnObjectsWithBodiesRebuiltServerSide() {
        // cash-in: payer float wallet, currency, channel and outlet are the agent's, whatever the client sends
        JsonNode cashIn = post(A, "/v1/agent/cash-in", """
                {"payee_msisdn":"+254700000001","amount":"50","agent_outlet_id":"%s","pin":"1234",
                 "agent_wallet_id":"%s","currency":"USD","channel":"USSD","agent_id":"%s"}"""
                .formatted(outlet[0], floatWallet[1], B));
        assertThat(cashIn.path("txn_type").asString()).isEqualTo("CASH_IN");
        JsonNode sent = net.lastWrite(WAL, "/v1/wallet-transactions/cash-in").body();
        assertThat(sent.path("agent_wallet_id").asString()).isEqualTo(floatWallet[0]);
        assertThat(sent.path("currency").asString()).isEqualTo("KES");
        assertThat(sent.path("channel").asString()).isEqualTo("AGENT_APP");
        assertThat(sent.path("pin").asString()).isEqualTo("1234");
        assertThat(sent.has("agent_id")).isFalse();

        // cash-out from a customer wallet, then the customer confirms
        JsonNode co = post(A, "/v1/agent/cash-out", "{\"payer_wallet_id\":\"%s\",\"amount\":\"40\",\"currency\":\"USD\"}"
                .formatted(customerWallet));
        assertThat(net.lastWrite(WAL, "/v1/wallet-transactions/cash-out").body().path("agent_wallet_id").asString())
                .isEqualTo(floatWallet[0]);
        assertThat(post(A, "/v1/agent/cash-out/" + co.path("id").asString() + "/confirm", "{\"pin\":\"9999\"}")
                .path("status").asString()).isEqualTo("COMPLETED");
        assertThat(post(A, "/v1/agent/cash-out/" + cashOut[0] + "/cancel", "{\"reason\":\"customer left\"}")
                .path("status").asString()).isEqualTo("CANCELLED");
        // a merchant wallet is not a customer wallet: same 404 as a missing one, nothing sent
        assertThat(call(A, now(), "POST", "/v1/agent/cash-out", "{\"payer_wallet_id\":\"%s\",\"amount\":\"5\"}"
                .formatted(merchantWallet)).statusCode()).isEqualTo(404);

        // quote: the payer is the agent's float wallet
        JsonNode quote = post(A, "/v1/agent/transactions/quote", "{\"txn_type\":\"CASH_IN\",\"amount\":\"10\","
                + "\"payee_msisdn\":\"+254700000001\"}");
        assertThat(quote.path("echo").path("payer_wallet_id").asString()).isEqualTo(floatWallet[0]);

        // float request: requesting agent, target wallet, currency and source are server-side
        JsonNode fr = post(A, "/v1/agent/float-requests", """
                {"request_type":"REBALANCE","amount":"200","requesting_agent_id":"%s","target_wallet_id":"%s",
                 "currency":"USD","source_agent_id":"%s"}""".formatted(B, floatWallet[1], B));
        JsonNode frBody = net.lastWrite(NET, "/v1/agent-float-requests").body();
        assertThat(frBody.path("requesting_agent_id").asString()).isEqualTo(A);
        assertThat(frBody.path("target_wallet_id").asString()).isEqualTo(floatWallet[0]);
        assertThat(frBody.path("source_agent_id").asString()).isEqualTo(P);
        assertThat(frBody.path("currency").asString()).isEqualTo("KES");
        assertThat(call(A, now(), "POST", "/v1/agent/float-requests", "{\"request_type\":\"WITHDRAW\",\"amount\":\"1\"}")
                .statusCode()).isEqualTo(422);
        assertThat(post(A, "/v1/agent/float-requests/" + fr.path("id").asString() + "/cancel", "{}").path("status")
                .asString()).isEqualTo("CANCELLED");

        // cash request and till at the agent's outlet; the till operator is the signed-in user
        post(A, "/v1/agent/cash-requests", "{\"agent_outlet_id\":\"%s\",\"request_type\":\"CASH_PICKUP\",\"amount\":\"300\","
                .formatted(outlet[0]) + "\"currency\":\"USD\"}");
        assertThat(net.lastWrite(NET, "/v1/cash-management-requests").body().path("currency").asString()).isEqualTo("KES");
        JsonNode newTill = post(A, "/v1/agent/tills", "{\"agent_outlet_id\":\"%s\",\"till_number\":\"T9\"}".formatted(outlet[0]));
        assertThat(newTill.path("currency").asString()).isEqualTo("KES");
        JsonNode opened = post(A, "/v1/agent/tills/" + till[0] + "/open", "{\"opening_cash\":\"1000\","
                + "\"operator_user_id\":\"" + UUID.randomUUID() + "\"}");
        assertThat(opened.path("status").asString()).isEqualTo("OPEN");
        assertThat(opened.path("operator_user_id").asString())
                .isEqualTo(UUID.nameUUIDFromBytes("agent-user:till.anna".getBytes(StandardCharsets.UTF_8)).toString());
        assertThat(post(A, "/v1/agent/tills/" + till[0] + "/close", "{\"declared_cash\":\"1000\"}").path("status")
                .asString()).isEqualTo("CLOSED");

        // bills and airtime from the agent's float wallet
        post(A, "/v1/agent/bill-payments", """
                {"biller_id":"%s","account_reference":"998877","amount":"75","payer_wallet_id":"%s","channel":"USSD"}"""
                .formatted(biller, floatWallet[1]));
        JsonNode bp = net.lastWrite(BIL, "/v1/bill-payments").body();
        assertThat(bp.path("payer_wallet_id").asString()).isEqualTo(floatWallet[0]);
        assertThat(bp.path("channel").asString()).isEqualTo("AGENT_APP");
        post(A, "/v1/agent/airtime-topups", "{\"biller_id\":\"%s\",\"target_msisdn\":\"+254711111111\",\"amount\":\"20\"}"
                .formatted(biller));
        assertThat(net.lastWrite(BIL, "/v1/airtime-topups").body().path("payer_wallet_id").asString())
                .isEqualTo(floatWallet[0]);
        assertThat(post(A, "/v1/agent/billers/" + biller + "/validate-reference", "{\"account_reference\":\"1\"}")
                .path("response_code").asString()).isEqualTo("00");

        // assisted onboarding: channel AGENT and the agent are server-side; web-only journeys are refused
        JsonNode app = post(A, "/v1/agent/onboarding/applications", """
                {"onboarding_journey_id":"%s","phone":"+254700000777","channel":"WEB","agent_id":"%s",
                 "collected_data":{"first_name":"Amina"}}""".formatted(agentJourney, B));
        JsonNode appBody = net.lastWrite(ONB, "/v1/onboarding/applications").body();
        assertThat(appBody.path("channel").asString()).isEqualTo("AGENT");
        assertThat(appBody.path("agent_id").asString()).isEqualTo(A);
        String appId = app.path("id").asString();
        assertThat(call(A, now(), "POST", "/v1/agent/onboarding/applications",
                "{\"onboarding_journey_id\":\"%s\",\"phone\":\"+254700000778\"}".formatted(webJourney)).statusCode())
                .isEqualTo(422);
        post(A, "/v1/agent/onboarding/applications/" + appId + "/steps/PERSONAL", "{\"input\":{\"first_name\":\"Amina\"}}");
        JsonNode doc = post(A, "/v1/agent/onboarding/applications/" + appId + "/documents",
                "{\"document_type\":\"ID_CARD\",\"file_name\":\"id.jpg\",\"content_type\":\"image/jpeg\","
                        + "\"owner_type\":\"CUSTOMER\",\"owner_id\":\"" + B + "\"}");
        JsonNode docBody = net.lastWrite(DOC, "/v1/documents").body();
        assertThat(docBody.path("owner_type").asString()).isEqualTo("ONBOARDING_APPLICATION");
        assertThat(docBody.path("owner_id").asString()).isEqualTo(appId);
        String docId = doc.path("id").asString();
        assertThat(post(A, "/v1/agent/onboarding/applications/" + appId + "/documents/" + docId + "/upload-url", null)
                .path("method").asString()).isEqualTo("PUT");
        post(A, "/v1/agent/onboarding/applications/" + appId + "/documents/" + docId + "/complete-upload",
                "{\"size_bytes\":10,\"checksum\":\"" + "a".repeat(64) + "\"}");
        assertThat(post(A, "/v1/agent/onboarding/applications/" + appId + "/submit", null).path("status").asString())
                .isEqualTo("SUBMITTED");
    }

    // ------------------------------------------------------------------ object-level authorization

    @Test
    @Order(3)
    void agentACannotTouchAgentBsObjects() {
        int writes = net.writes().size();
        String[][] refused = {
                {"GET", "/v1/agent/tills/" + till[1], null},
                {"POST", "/v1/agent/tills/" + till[1] + "/open", "{\"opening_cash\":\"1\"}"},
                {"POST", "/v1/agent/tills/" + till[1] + "/close", "{\"declared_cash\":\"1\"}"},
                {"GET", "/v1/agent/tills?agent_outlet_id=" + outlet[1], null},
                {"POST", "/v1/agent/tills", "{\"agent_outlet_id\":\"%s\",\"till_number\":\"X1\"}".formatted(outlet[1])},
                {"GET", "/v1/agent/cash-requests?agent_outlet_id=" + outlet[1], null},
                {"POST", "/v1/agent/cash-requests", "{\"agent_outlet_id\":\"%s\",\"request_type\":\"CASH_PICKUP\",\"amount\":\"1\"}"
                        .formatted(outlet[1])},
                {"GET", "/v1/agent/cash-requests/" + cashRequest[1], null},
                {"POST", "/v1/agent/cash-requests/" + cashRequest[1] + "/cancel", "{}"},
                {"GET", "/v1/agent/float-requests/" + floatRequest[1], null},
                {"POST", "/v1/agent/float-requests/" + floatRequest[1] + "/cancel", "{}"},
                {"GET", "/v1/agent/transactions/" + cashOut[1], null},
                {"GET", "/v1/agent/transactions/" + cashOut[1] + "/receipt", null},
                {"POST", "/v1/agent/cash-out/" + cashOut[1] + "/confirm", "{\"pin\":\"1234\"}"},
                {"POST", "/v1/agent/cash-out/" + cashOut[1] + "/cancel", "{}"},
                {"POST", "/v1/agent/cash-in", "{\"payee_msisdn\":\"+254700000001\",\"amount\":\"1\",\"agent_outlet_id\":\"%s\"}"
                        .formatted(outlet[1])},
                {"GET", "/v1/agent/bill-payments/" + billPayment[1], null},
                {"GET", "/v1/agent/bill-payments/" + billPayment[1] + "/receipt", null},
                {"GET", "/v1/agent/airtime-topups/" + topup[1], null},
                {"GET", "/v1/agent/onboarding/applications/" + application[1], null},
                {"GET", "/v1/agent/onboarding/applications/" + application[1] + "/next-step", null},
                {"GET", "/v1/agent/onboarding/applications/" + application[1] + "/steps", null},
                {"POST", "/v1/agent/onboarding/applications/" + application[1] + "/steps/PERSONAL", "{\"input\":{}}"},
                {"POST", "/v1/agent/onboarding/applications/" + application[1] + "/submit", null},
                {"POST", "/v1/agent/onboarding/applications/" + application[1] + "/documents",
                        "{\"document_type\":\"ID_CARD\",\"file_name\":\"a\",\"content_type\":\"image/jpeg\"}"},
                {"POST", "/v1/agent/onboarding/applications/" + application[0] + "/documents/" + document[1] + "/upload-url", null},
                {"GET", "/v1/agent/offline-batches/" + batch[1], null},
                {"GET", "/v1/agent/devices/" + deviceB, null},
                {"GET", "/v1/agent/devices/" + deviceB + "/keys", null},
                {"POST", "/v1/agent/devices/" + deviceB + "/heartbeat", "{}"},
                {"POST", "/v1/agent/devices/" + deviceB + "/revoke", "{}"},
                {"GET", "/v1/agent/me/performance?sub_agent_id=" + B, null},
                {"GET", "/v1/agent/transactions/" + UUID.randomUUID(), null}};
        for (String[] op : refused) {
            HttpResponse<String> r = call(A, now(), op[0], op[1], op[2]);
            assertThat(r.statusCode()).as(op[0] + " " + op[1] + " -> " + r.body()).isEqualTo(404);
            assertThat(json.readTree(r.body()).path("code").asString()).isEqualTo("not_found");
        }
        assertThat(net.writes()).as("nothing written downstream").hasSize(writes);
        assertThat(net.row(NET, "/v1/agent-tills", till[1]).path("status").asString()).isEqualTo("CLOSED");
        assertThat(net.row(NET, "/v1/agent-float-requests", floatRequest[1]).path("status").asString()).isEqualTo("REQUESTED");
        assertThat(net.row(WAL, "/v1/wallet-transactions", cashOut[1]).path("status").asString())
                .isEqualTo("PENDING_CONFIRMATION");
        assertThat(net.row(NET, "/v1/agent-devices", deviceB).path("status").asString()).isEqualTo("ACTIVE");
        // lists never show B's rows
        assertThat(ids(get(A, "/v1/agent/float-requests"))).doesNotContain(floatRequest[1]);
        assertThat(ids(get(A, "/v1/agent/transactions"))).doesNotContain(cashOut[1]);
        assertThat(ids(get(A, "/v1/agent/offline-batches"))).doesNotContain(batch[1]);
    }

    // ------------------------------------------------------------------ device binding

    @Test
    @Order(4)
    void deviceRegistrationBindsAPublicKey() throws Exception {
        String register = """
                {"serial_number":"SER-A","device_type":"SMARTPHONE","agent_outlet_id":"%s","app_version":"0.1.0",
                 "key_algorithm":"ES256","public_key":"%s","agent_id":"%s"}"""
                .formatted(outlet[0], b64(keyA.getPublic().getEncoded()), B);
        // registering a key is a security change: a recent login only
        assertThat(call(A, stale(), "POST", "/v1/agent/devices", register).statusCode()).isEqualTo(401);
        assertThat(call(A, now(), "POST", "/v1/agent/devices", register.replace(b64(keyA.getPublic().getEncoded()),
                "bm90LWEta2V5")).statusCode()).isEqualTo(422);
        // B's serial number cannot be taken over
        assertThat(call(A, now(), "POST", "/v1/agent/devices", register.replace("SER-A", "SER-B")).statusCode())
                .isEqualTo(422);

        JsonNode key = post(A, "/v1/agent/devices", register);
        assertThat(key.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(key.path("key_algorithm").asString()).isEqualTo("ES256");
        assertThat(key.path("agent_id").asString()).isEqualTo(A);
        deviceA = key.path("agent_device_id").asString();
        JsonNode created = net.lastWrite(NET, "/v1/agent-devices").body();
        assertThat(created.path("agent_id").asString()).isEqualTo(A);
        assertThat(created.path("agent_outlet_id").asString()).isEqualTo(outlet[0]);
        // the same registration again is a replay
        assertThat(post(A, "/v1/agent/devices", register).path("id").asString()).isEqualTo(key.path("id").asString());
        assertThat(ids(get(A, "/v1/agent/devices"))).containsExactly(deviceA);
        assertThat(get(A, "/v1/agent/devices/" + deviceA + "/keys").path("data")).hasSize(1);
        assertThat(post(A, "/v1/agent/devices/" + deviceA + "/heartbeat", "{\"app_version\":\"0.1.1\"}")
                .path("last_seen_at").isMissingNode()).isFalse();
    }

    @Test
    @Order(5)
    void offlineBatchesMustBeSignedWithTheDeviceKey() throws Exception {
        String key = "batch-" + UUID.randomUUID();
        Instant from = Instant.parse("2026-10-07T09:00:00Z");
        String payloadUri = "data:application/gzip;base64,H4sIAAAAAAAAA6uuBQBDv6ajAgAAAA==";
        String good = batchBody(deviceA, from, 1, payloadUri, sign(keyA.getPrivate(), "SHA256withECDSAinP1363Format",
                DeviceKeys.batchPayload(UUID.fromString(TENANT), UUID.fromString(A), UUID.fromString(deviceA), key, from,
                        from, 1, payloadUri)));

        // wrong key, tampered count, tampered payload, signature made for another Idempotency-Key
        KeyPair other = es256();
        String wrongKey = batchBody(deviceA, from, 1, payloadUri, sign(other.getPrivate(), "SHA256withECDSAinP1363Format",
                DeviceKeys.batchPayload(UUID.fromString(TENANT), UUID.fromString(A), UUID.fromString(deviceA), key, from,
                        from, 1, payloadUri)));
        String sig = json.readTree(good).path("signature").asString();
        for (String bad : new String[] {wrongKey, batchBody(deviceA, from, 2, payloadUri, sig),
                batchBody(deviceA, from, 1, payloadUri.replace("AAAA==", "AAAB=="), sig),
                batchBody(deviceA, from, 1, payloadUri, "not-base64!")}) {
            HttpResponse<String> r = send(A, now(), "POST", "/v1/agent/offline-batches", bad, key, Map.of());
            assertThat(r.statusCode()).as(r.body()).isEqualTo(422);
            assertThat(json.readTree(r.body()).path("code").asString()).isEqualTo("device_signature_invalid");
        }
        HttpResponse<String> otherKey = send(A, now(), "POST", "/v1/agent/offline-batches", good, "other-" + key, Map.of());
        assertThat(otherKey.statusCode()).isEqualTo(422);
        // another agent's device: 404
        assertThat(send(A, now(), "POST", "/v1/agent/offline-batches", batchBody(deviceB, from, 1, payloadUri, sig), key,
                Map.of()).statusCode()).isEqualTo(404);
        assertThat(net.writesTo(ONB, "/v1/onboarding/offline-batches")).isEmpty();

        // valid: forwarded with the agent and device set server-side and the verified signature
        JsonNode ok = ok(send(A, now(), "POST", "/v1/agent/offline-batches", good, key, Map.of()), "batch");
        JsonNode forwarded = net.lastWrite(ONB, "/v1/onboarding/offline-batches").body();
        assertThat(forwarded.path("agent_id").asString()).isEqualTo(A);
        assertThat(forwarded.path("device_fingerprint").asString()).isEqualTo(deviceA);
        assertThat(forwarded.path("payload_signature").asString()).isEqualTo("ES256:" + deviceA + ":" + sig);
        // idempotent replay: same batch, one downstream row; reconcilable by the client key
        JsonNode again = ok(send(A, now(), "POST", "/v1/agent/offline-batches", good, key, Map.of()), "replay");
        assertThat(again.path("id").asString()).isEqualTo(ok.path("id").asString());
        assertThat(net.rows(ONB, "/v1/onboarding/offline-batches").values().stream()
                .filter(r -> r.path("agent_id").asString().equals(A))).hasSize(2);   // seeded + this one
        assertThat(ids(get(A, "/v1/agent/offline-batches?idempotency_key=" + key))).containsExactly(ok.path("id").asString());
        assertThat(ids(get(B, "/v1/agent/offline-batches?idempotency_key=" + key))).isEmpty();

        // Ed25519 device
        KeyPair ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        JsonNode edKey = post(A, "/v1/agent/devices", """
                {"serial_number":"SER-ED","device_type":"TABLET","agent_outlet_id":"%s","key_algorithm":"ED25519",
                 "public_key":"%s"}""".formatted(outlet[0], b64(ed.getPublic().getEncoded())));
        String edDevice = edKey.path("agent_device_id").asString();
        String edKeyId = "ed-" + UUID.randomUUID();
        String edBatch = batchBody(edDevice, from, 1, payloadUri, sign(ed.getPrivate(), "Ed25519",
                DeviceKeys.batchPayload(UUID.fromString(TENANT), UUID.fromString(A), UUID.fromString(edDevice), edKeyId,
                        from, from, 1, payloadUri)));
        assertThat(send(A, now(), "POST", "/v1/agent/offline-batches", edBatch, edKeyId, Map.of()).statusCode())
                .isEqualTo(200);
        assertThat(net.lastWrite(ONB, "/v1/onboarding/offline-batches").body().path("payload_signature").asString())
                .startsWith("ED25519:" + edDevice + ":");
    }

    @Test
    @Order(6)
    void reRegistrationAndRevocationInvalidateOldKeys() throws Exception {
        Instant from = Instant.parse("2026-10-07T10:00:00Z");
        String payloadUri = "data:application/gzip;base64,AAAA";
        KeyPair old = keyA;
        keyA = es256();
        JsonNode replaced = post(A, "/v1/agent/devices", """
                {"serial_number":"SER-A","device_type":"SMARTPHONE","agent_outlet_id":"%s","key_algorithm":"ES256",
                 "public_key":"%s"}""".formatted(outlet[0], b64(keyA.getPublic().getEncoded())));
        assertThat(replaced.path("agent_device_id").asString()).isEqualTo(deviceA);
        List<String> statuses = new ArrayList<>();
        get(A, "/v1/agent/devices/" + deviceA + "/keys").path("data").forEach(k -> statuses.add(k.path("status").asString()));
        assertThat(statuses).containsExactlyInAnyOrder("ACTIVE", "REVOKED");
        String k1 = "k1-" + UUID.randomUUID();
        String signedByOld = batchBody(deviceA, from, 1, payloadUri, sign(old.getPrivate(), "SHA256withECDSAinP1363Format",
                DeviceKeys.batchPayload(UUID.fromString(TENANT), UUID.fromString(A), UUID.fromString(deviceA), k1, from,
                        from, 1, payloadUri)));
        assertThat(send(A, now(), "POST", "/v1/agent/offline-batches", signedByOld, k1, Map.of()).statusCode()).isEqualTo(422);

        // a revoked device: its current key no longer verifies anything
        String edSerialDevice = ids(get(A, "/v1/agent/devices")).stream().filter(d -> !d.equals(deviceA)).findFirst()
                .orElseThrow();
        assertThat(post(A, "/v1/agent/devices/" + edSerialDevice + "/revoke", "{\"reason\":\"stolen\"}").path("status")
                .asString()).isEqualTo("LOST");
        String k2 = "k2-" + UUID.randomUUID();
        HttpResponse<String> revoked = send(A, now(), "POST", "/v1/agent/offline-batches",
                batchBody(edSerialDevice, from, 1, payloadUri, "AAAA"), k2, Map.of());
        assertThat(revoked.statusCode()).isEqualTo(422);
        assertThat(json.readTree(revoked.body()).path("code").asString()).isEqualTo("device_key_revoked");
    }

    // ------------------------------------------------------------------ step-up

    @Test
    @Order(7)
    void moneyMovementNeedsARecentLoginOrADeviceProof() throws Exception {
        int writes = net.writes().size();
        String cashIn = "{\"payee_msisdn\":\"+254700000001\",\"amount\":\"5\"}";
        String[][] money = {
                {"/v1/agent/cash-in", cashIn},
                {"/v1/agent/cash-out", "{\"payer_wallet_id\":\"%s\",\"amount\":\"5\"}".formatted(customerWallet)},
                {"/v1/agent/bill-payments", "{\"biller_id\":\"%s\",\"account_reference\":\"1\",\"amount\":\"5\"}".formatted(biller)},
                {"/v1/agent/airtime-topups", "{\"biller_id\":\"%s\",\"target_msisdn\":\"+254711111111\",\"amount\":\"5\"}"
                        .formatted(biller)},
                {"/v1/agent/float-requests", "{\"request_type\":\"TOP_UP\",\"amount\":\"5\",\"bank_reference\":\"BR1\"}"}};
        for (Long authTime : new Long[] {null, stale()}) {
            for (String[] op : money) {
                HttpResponse<String> r = call(A, authTime, "POST", op[0], op[1]);
                assertThat(r.statusCode()).as(op[0] + " " + authTime).isEqualTo(401);
                assertThat(json.readTree(r.body()).path("code").asString()).isEqualTo("step_up_required");
                assertThat(r.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                        h -> assertThat(h).contains("insufficient_user_authentication").contains("max_age=300"));
            }
        }
        assertThat(net.writes()).hasSize(writes);

        // a device proof from the agent's registered device stands in for a recent login
        String key = "proof-" + UUID.randomUUID();
        long ts = Instant.now().getEpochSecond();
        String proof = proof(keyA.getPrivate(), deviceA, "POST", "/v1/agent/cash-in", key, ts);
        HttpResponse<String> proven = send(A, stale(), "POST", "/v1/agent/cash-in", cashIn, key,
                Map.of("X-Agent-Device-Proof", proof));
        assertThat(proven.statusCode()).as(proven.body()).isEqualTo(200);

        // invalid proofs: other key, other path, other Idempotency-Key, too old, B's device
        KeyPair stranger = es256();
        String k2 = "proof2-" + UUID.randomUUID();
        for (String bad : new String[] {
                proof(stranger.getPrivate(), deviceA, "POST", "/v1/agent/cash-in", k2, ts),
                proof(keyA.getPrivate(), deviceA, "POST", "/v1/agent/bill-payments", k2, ts),
                proof(keyA.getPrivate(), deviceA, "POST", "/v1/agent/cash-in", key, ts),
                proof(keyA.getPrivate(), deviceA, "POST", "/v1/agent/cash-in", k2, ts - 3600),
                proof(keyA.getPrivate(), deviceB, "POST", "/v1/agent/cash-in", k2, ts),
                "garbage"}) {
            HttpResponse<String> r = send(A, stale(), "POST", "/v1/agent/cash-in", cashIn, k2,
                    Map.of("X-Agent-Device-Proof", bad));
            assertThat(r.statusCode()).as(bad).isEqualTo(401);
            assertThat(json.readTree(r.body()).path("code").asString()).isEqualTo("device_proof_invalid");
        }
        // a device proof does not register devices
        assertThat(send(A, stale(), "POST", "/v1/agent/devices", "{\"serial_number\":\"SER-X\",\"device_type\":\"POS\","
                + "\"agent_outlet_id\":\"" + outlet[0] + "\",\"key_algorithm\":\"ES256\",\"public_key\":\""
                + b64(es256().getPublic().getEncoded()) + "\"}", "reg-" + UUID.randomUUID(),
                Map.of("X-Agent-Device-Proof", proof)).statusCode()).isEqualTo(401);
        String method = TenantContext.callAs(UUID.fromString(TENANT), "test", () -> jdbc.sql(
                "select auth_method from agent_action where action = 'agentMeCashInCreate' and outcome = 'SUCCEEDED' "
                        + "and agent_device_id = ? order by created_at desc limit 1")
                .param(UUID.fromString(deviceA)).query(String.class).single());
        assertThat(method).isEqualTo("DEVICE_PROOF");
    }

    // ------------------------------------------------------------------ idempotency and reconciliation

    @Test
    @Order(8)
    void floatRequestsAndCashInsAreIdempotentAndReconcilable() {
        String key = "fr-" + UUID.randomUUID();
        String body = "{\"request_type\":\"TOP_UP\",\"amount\":\"150\",\"bank_reference\":\"BANK-9\"}";
        JsonNode first = ok(send(A, now(), "POST", "/v1/agent/float-requests", body, key, Map.of()), "first");
        JsonNode second = ok(send(A, now(), "POST", "/v1/agent/float-requests", body, key, Map.of()), "second");
        assertThat(second.path("id").asString()).isEqualTo(first.path("id").asString());
        assertThat(net.rows(NET, "/v1/agent-float-requests").values().stream()
                .filter(r -> "BANK-9".equals(r.path("bank_reference").asString(null)))).hasSize(1);
        assertThat(ids(get(A, "/v1/agent/float-requests?idempotency_key=" + key)))
                .containsExactly(first.path("id").asString());
        // B using the same client key gets its own request; A's is never B's
        JsonNode b = ok(send(B, now(), "POST", "/v1/agent/float-requests", body, key, Map.of()), "B");
        assertThat(b.path("id").asString()).isNotEqualTo(first.path("id").asString());
        assertThat(b.path("requesting_agent_id").asString()).isEqualTo(B);
        assertThat(ids(get(B, "/v1/agent/float-requests?idempotency_key=" + key))).containsExactly(b.path("id").asString());

        String ck = "ci-" + UUID.randomUUID();
        JsonNode ci = ok(send(A, now(), "POST", "/v1/agent/cash-in", "{\"payee_msisdn\":\"+254700000001\",\"amount\":\"7\"}",
                ck, Map.of()), "cash-in");
        assertThat(ids(get(A, "/v1/agent/transactions?idempotency_key=" + ck))).containsExactly(ci.path("id").asString());
        assertThat(ids(get(B, "/v1/agent/transactions?idempotency_key=" + ck))).isEmpty();
        String bk = "bp-" + UUID.randomUUID();
        JsonNode bp = ok(send(A, now(), "POST", "/v1/agent/bill-payments",
                "{\"biller_id\":\"%s\",\"account_reference\":\"5\",\"amount\":\"9\"}".formatted(biller), bk, Map.of()), "bp");
        assertThat(ids(get(A, "/v1/agent/bill-payments?idempotency_key=" + bk))).containsExactly(bp.path("id").asString());
    }

    // ------------------------------------------------------------------ customer lookup

    @Test
    @Order(9)
    void customerLookupIsMaskedLoggedAndRateLimited() {
        JsonNode found = get(A, "/v1/agent/customer-wallets/lookup?msisdn=%2B254700000001");
        assertThat(found.path("wallet_id").asString()).isEqualTo(customerWallet);
        assertThat(found.path("display_name_masked").asString()).isEqualTo("J*** W***");
        assertThat(found.path("msisdn_masked").asString()).isEqualTo("+254******001");
        assertThat(found.toString()).doesNotContain("Jane").doesNotContain("Wanjiru").doesNotContain("+254700000001");
        assertThat(call(A, now(), "GET", "/v1/agent/customer-wallets/lookup?msisdn=%2B254700000009", null).statusCode())
                .isEqualTo(404);                                                   // a merchant wallet is not a customer
        assertThat(call(A, now(), "GET", "/v1/agent/customer-wallets/lookup", null).statusCode()).isEqualTo(400);
        int status = 200;
        for (int i = 0; i < 6 && status != 429; i++) {
            status = call(A, now(), "GET", "/v1/agent/customer-wallets/lookup?wallet_number=W100", null).statusCode();
        }
        assertThat(status).isEqualTo(429);
        assertThat(call(B, now(), "GET", "/v1/agent/customer-wallets/lookup?wallet_number=W100", null).statusCode())
                .isEqualTo(200);                                                   // the limit is per agent
    }

    // ------------------------------------------------------------------ callers and audit

    @Test
    @Order(10)
    void onlySignedInAgentsOfATenantGetIn() throws Exception {
        HttpResponse<String> staff = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/agent/me"))
                .header("X-Tenant-Id", TENANT).header("X-User", "ops.anna").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(staff.statusCode()).isEqualTo(403);
        assertThat(json.readTree(staff.body()).path("code").asString()).isEqualTo("agent_identity_required");
        HttpResponse<String> customer = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/agent/me"))
                .header("X-Tenant-Id", TENANT).header("X-Customer-Id", A).header("X-Agent-Id", A).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(customer.statusCode()).isEqualTo(403);
        HttpResponse<String> noTenant = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/agent/me"))
                .header("X-Agent-Id", A).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noTenant.statusCode()).isEqualTo(401);
        HttpResponse<String> unknown = call(UUID.randomUUID().toString(), now(), "GET", "/v1/agent/me", null);
        assertThat(unknown.statusCode()).isEqualTo(403);
        assertThat(json.readTree(unknown.body()).path("code").asString()).isEqualTo("agent_not_found");
    }

    @Test
    @Order(11)
    void activityIsTheAgentsOwnAndDenialsAreEvents() throws Exception {
        JsonNode mine = get(A, "/v1/agent/me/activity?limit=200");
        List<String> outcomes = new ArrayList<>();
        mine.path("data").forEach(r -> {
            assertThat(r.path("agent_id").asString()).isEqualTo(A);
            outcomes.add(r.path("outcome").asString());
        });
        assertThat(outcomes).contains("SUCCEEDED", "DENIED", "STEP_UP_REQUIRED", "SIGNATURE_INVALID", "RATE_LIMITED");
        boolean tillDenial = false;
        for (JsonNode r : mine.path("data")) {
            if ("DENIED".equals(r.path("outcome").asString()) && till[1].equals(r.path("object_id").asString())) {
                tillDenial = "TILL".equals(r.path("object_type").asString())
                        && "not_owned".equals(r.path("error_code").asString());
            }
        }
        assertThat(tillDenial).isTrue();
        for (JsonNode r : get(B, "/v1/agent/me/activity?limit=200").path("data")) {
            assertThat(r.path("agent_id").asString()).isEqualTo(B);
        }
        long events = TenantContext.callAs(UUID.fromString(TENANT), "test", () -> jdbc.sql(
                "select count(*) from outbox where event_type = 'channels.agent_action.denied'").query(Long.class).single());
        assertThat(events).isGreaterThanOrEqualTo(34);
        long keyEvents = TenantContext.callAs(UUID.fromString(TENANT), "test", () -> jdbc.sql(
                "select count(*) from outbox where event_type like 'channels.agent_device_key.%'").query(Long.class).single());
        assertThat(keyEvents).isGreaterThanOrEqualTo(4);
    }

    // ------------------------------------------------------------------ helpers

    private static KeyPair es256() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String sign(PrivateKey key, String algorithm, String payload) throws Exception {
        Signature s = Signature.getInstance(algorithm);
        s.initSign(key);
        s.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
    }

    private String proof(PrivateKey key, String device, String method, String path, String idemKey, long ts)
            throws Exception {
        return "v1." + device + "." + ts + "." + sign(key, key.getAlgorithm().equals("EC")
                ? "SHA256withECDSAinP1363Format" : "Ed25519", DeviceKeys.proofPayload(UUID.fromString(TENANT),
                UUID.fromString(A), UUID.fromString(device), method, path, idemKey, ts));
    }

    private String batchBody(String device, Instant at, int count, String payloadUri, String signature) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agent_device_id", device);
        m.put("captured_from", at.toString());
        m.put("captured_to", at.toString());
        m.put("application_count", count);
        m.put("payload_uri", payloadUri);
        m.put("signature", signature);
        m.put("agent_id", B);                          // ignored: the agent comes from the token
        return json.writeValueAsString(m);
    }

    private static String id(JsonNode n) {
        return n.path("id").asString();
    }

    private static List<String> ids(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.path("data").forEach(r -> out.add(r.path("id").asString()));
        return out;
    }

    private static Long now() {
        return Instant.now().getEpochSecond();
    }

    private static Long stale() {
        return Instant.now().minusSeconds(3600).getEpochSecond();
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private JsonNode get(String agent, String path) {
        return ok(call(agent, now(), "GET", path, null), "GET " + path);
    }

    private JsonNode post(String agent, String path, String body) {
        return ok(call(agent, now(), "POST", path, body), "POST " + path);
    }

    private JsonNode ok(HttpResponse<String> r, String what) {
        assertThat(r.statusCode()).as(what + " -> " + r.body()).isBetween(200, 299);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    HttpResponse<String> call(String agent, Long authTime, String method, String path, String body) {
        return send(agent, authTime, method, path, body, UUID.randomUUID().toString(), Map.of());
    }

    private HttpResponse<String> send(String agent, Long authTime, String method, String path, String body, String key,
                                      Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
                .header("X-Tenant-Id", TENANT).header("X-Agent-Id", agent).header("X-User", "till.anna")
                .header("Content-Type", "application/json");
        if (authTime != null) {
            b.header("X-Auth-Time", String.valueOf(authTime));
        }
        if (!method.equals("GET")) {
            b.header("Idempotency-Key", key);
        }
        headers.forEach(b::header);
        b.method(method, body == null ? HttpRequest.BodyPublishers.ofString(method.equals("GET") ? "" : "{}")
                : HttpRequest.BodyPublishers.ofString(body));
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
