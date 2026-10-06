package io.meridian.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.channel.domain.GatewaySignature;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * channel-service end to end over HTTP against PostgreSQL 18 (RLS enforced). USSD dialogues and SMS commands arrive
 * through the signed public gateway callbacks; wallet-service is a stateful fake (wallets by msisdn, balances, P2P
 * with PIN check) and notification-service records outbound SMS, both behind the mocked {@link ServiceClient}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.channel.gateway-secret=" + ChannelFlowIT.SECRET, "meridian.channel.default-calling-code=254"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChannelFlowIT {
    static final String SECRET = "gw-test-secret";
    static final String TENANT = "0199c000-0000-7000-8000-00000000c001";
    static final String OTHER_TENANT = "0199c000-0000-7000-8000-00000000c002";
    static final String ALICE = "+254700000001";
    static final String ALICE_WALLET = "0199c000-0000-7000-8000-0000000000a1";
    static final String BOB = "+254712345678";
    static final String CODE = "*123#";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();

    /** Stateful wallet-service stand-in. */
    final Map<String, BigDecimal> balances = new LinkedHashMap<>(Map.of(ALICE, new BigDecimal("10000"), BOB, BigDecimal.ZERO));
    final List<JsonNode> p2pCalls = Collections.synchronizedList(new ArrayList<>());
    final Map<String, JsonNode> p2pByKey = new LinkedHashMap<>();
    final List<JsonNode> sms = Collections.synchronizedList(new ArrayList<>());

    String menu;

    @BeforeEach
    void stub() {
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> fakeGet(i.getArgument(0), i.getArgument(1)));
        when(serviceClient.post(anyString(), anyString(), any(), any())).thenAnswer(i ->
                fakePost(i.getArgument(0), i.getArgument(1), json.valueToTree(i.getArgument(2)), i.getArgument(3)));
    }

    private JsonNode wallet(String msisdn) {
        String id = msisdn.equals(ALICE) ? ALICE_WALLET : "0199c000-0000-7000-8000-0000000000b2";
        return json.createObjectNode().put("id", id).put("msisdn", msisdn).put("currency", "KES")
                .put("kyc_tier", 0).put("language", "en").put("status", "ACTIVE")
                .put("display_name", msisdn.equals(ALICE) ? "Alice Wanjiru" : "Bob Otieno");
    }

    private synchronized JsonNode fakeGet(String service, String path) {
        assertThat(service).isEqualTo("wallet-service");
        if (path.startsWith("/v1/wallets/lookup?msisdn=")) {
            String msisdn = java.net.URLDecoder.decode(path.substring(path.indexOf('=') + 1), java.nio.charset.StandardCharsets.UTF_8);
            if (!balances.containsKey(msisdn)) {
                throw new ApiException(404, "not_found", "wallet not found");
            }
            return wallet(msisdn);
        }
        if (path.equals("/v1/wallets/" + ALICE_WALLET + "/balance")) {
            return json.createObjectNode().put("available_balance", balances.get(ALICE).toPlainString()).put("currency", "KES");
        }
        if (path.startsWith("/v1/wallets/" + ALICE_WALLET + "/transactions")) {
            ObjectNode page = json.createObjectNode();
            var data = page.putArray("data");
            for (JsonNode p : p2pByKey.values()) {
                data.addObject().put("txn_type", "P2P").put("amount", p.get("amount").asString())
                        .put("payer_wallet_id", ALICE_WALLET);
            }
            return page.put("has_more", false);
        }
        throw new ApiException(404, "not_found", path);
    }

    private synchronized JsonNode fakePost(String service, String path, JsonNode body, String key) {
        if ("notification-service".equals(service)) {
            sms.add(body);
            return json.createObjectNode().put("id", UUID.randomUUID().toString()).put("status", "QUEUED");
        }
        assertThat(path).isEqualTo("/v1/wallet-transactions/p2p");
        assertThat(key).isNotBlank();
        if (p2pByKey.containsKey(key)) {
            return p2pByKey.get(key);
        }
        p2pCalls.add(body);
        if (!"4826".equals(body.path("pin").asString())) {
            throw new ApiException(422, "invalid_pin", "Incorrect PIN; 2 attempt(s) left");
        }
        BigDecimal amount = new BigDecimal(body.get("amount").asString());
        String payee = body.get("payee_msisdn").asString();
        if (!balances.containsKey(payee)) {
            throw new ApiException(422, "payee_not_found", "no wallet");
        }
        if (balances.get(ALICE).compareTo(amount) < 0) {
            throw new ApiException(422, "insufficient_funds", "Account has insufficient available balance");
        }
        balances.put(ALICE, balances.get(ALICE).subtract(amount));
        balances.put(payee, balances.get(payee).add(amount));
        ObjectNode t = json.createObjectNode().put("id", UUID.randomUUID().toString()).put("reference", "WTREF" + p2pByKey.size())
                .put("status", "COMPLETED").put("amount", amount.toPlainString()).put("fee_amount", "5.00")
                .put("payee_name", wallet(payee).get("display_name").asString());
        p2pByKey.put(key, t);
        return t;
    }

    // ------------------------------------------------------------------ 1. menus

    @Test
    @Order(1)
    void buildValidatePublishAndCloneAMenu() throws Exception {
        JsonNode m = post("/v1/ussd-menus", """
                {"code":"MAIN","name":"Main menu","short_code":"*123#","language":"en","root_node_key":"main",
                 "session_timeout_seconds":120}""", "maker", 201);
        menu = m.get("id").asString();
        assertThat(m.get("version").asInt()).isEqualTo(1);
        assertThat(m.get("status").asString()).isEqualTo("DRAFT");
        node("""
                {"node_key":"main","node_type":"MENU","label":"Welcome {name}"}""", 201);
        node("""
                {"node_key":"send","parent_node_key":"main","option_number":1,"node_type":"INPUT","label":"Send money",
                 "prompt":"Enter recipient number","input_type":"MSISDN","context_key":"payee_msisdn","next_node_key":"amt"}""", 201);
        node("""
                {"node_key":"amt","node_type":"INPUT","label":"Amount","prompt":"Enter amount","input_type":"AMOUNT",
                 "context_key":"amount","next_node_key":"confirm"}""", 201);
        node("""
                {"node_key":"confirm","node_type":"CONFIRM","label":"Send {amount} KES to {payee_msisdn}?","next_node_key":"pay"}""", 201);
        node("""
                {"node_key":"pay","node_type":"ACTION","label":"Pay","prompt":"Enter PIN","action_code":"P2P_SEND",
                 "requires_pin":true}""", 201);
        node("""
                {"node_key":"bal","parent_node_key":"main","option_number":2,"node_type":"ACTION","label":"My balance",
                 "action_code":"BALANCE","requires_pin":true}""", 201);
        node("""
                {"node_key":"loans","parent_node_key":"main","option_number":3,"node_type":"END","label":"Loans",
                 "min_kyc_tier":2}""", 201);
        // validation: duplicate option, bad regex, text over 182 characters
        assertThat(node("""
                {"node_key":"dup","parent_node_key":"main","option_number":2,"node_type":"END","label":"Dup"}""", 409)
                .get("code").asString()).isEqualTo("option_number_taken");
        assertThat(node("""
                {"node_key":"rx","node_type":"INPUT","label":"x","input_validation":"([a-z","next_node_key":"main"}""", 422)
                .get("code").asString()).isEqualTo("invalid_regex");
        assertThat(node("{\"node_key\":\"long\",\"node_type\":\"END\",\"label\":\"" + "x".repeat(182) + "\"}", 201)).isNotNull();
        // an orphan node blocks publication until removed
        String orphan = findNode("long");
        assertThat(post("/v1/ussd-menus/" + menu + "/publish", "{}", "checker", 422).get("code").asString())
                .isEqualTo("invalid_menu_tree");
        assertThat(raw("DELETE", "/v1/ussd-menu-nodes/" + orphan, null, null, "maker", TENANT).statusCode()).isEqualTo(204);

        assertThat(post("/v1/ussd-menus/" + menu + "/publish", "{}", "maker", 422).get("code").asString())
                .isEqualTo("four_eyes_violation");
        JsonNode published = post("/v1/ussd-menus/" + menu + "/publish", "{\"comment\":\"ok\"}", "checker", 200);
        assertThat(published.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(published.get("published_by").asString()).isEqualTo("checker");
        // published versions are immutable
        assertThat(node("""
                {"node_key":"late","node_type":"END","label":"late"}""", 409).get("code").asString()).isEqualTo("menu_not_draft");

        JsonNode clone = post("/v1/ussd-menus/" + menu + "/clone", null, "maker", 200);
        assertThat(clone.get("version").asInt()).isEqualTo(2);
        assertThat(clone.get("status").asString()).isEqualTo("DRAFT");
        assertThat(clone.get("cloned_from_id").asString()).isEqualTo(menu);
        assertThat(get("/v1/ussd-menus/" + clone.get("id").asString() + "/nodes").get("data")).hasSize(7);

        JsonNode sim = post("/v1/ussd-menus/" + menu + "/simulate", """
                {"msisdn":"+254700000001","inputs":["1","0712345678","250","1","4826"]}""", "maker", 200);
        assertThat(sim.get("ended").asBoolean()).isTrue();
        JsonNode screens = sim.get("screens");
        assertThat(screens.get(0).get("text").asString()).startsWith("CON Welcome");
        assertThat(screens.get(3).get("text").asString()).contains("Send 250 KES to 0712345678?");
        assertThat(screens.get(5).get("input").asString()).isEqualTo("****");
        assertThat(screens.get(5).get("text").asString()).isEqualTo("END [simulated P2P_SEND]");
        assertThat(p2pCalls).isEmpty();
    }

    // ------------------------------------------------------------------ 2. USSD dialogue

    @Test
    @Order(2)
    void fullUssdPaymentThroughTheSignedGatewayCallback() throws Exception {
        String sid = "ATUid_" + UUID.randomUUID();
        HttpResponse<String> unsigned = callbackRaw(ussd(sid, ALICE, null), "deadbeef");
        assertThat(unsigned.statusCode()).isEqualTo(401);

        assertThat(ussdCall(sid, ALICE, null)).isEqualTo("CON Welcome Alice Wanjiru\n1. Send money\n2. My balance");
        assertThat(ussdCall(sid, ALICE, "1")).isEqualTo("CON Enter recipient number");
        assertThat(ussdCall(sid, ALICE, "abc")).isEqualTo("CON Invalid input. Enter recipient number");
        assertThat(ussdCall(sid, ALICE, "0712345678")).isEqualTo("CON Enter amount");
        assertThat(ussdCall(sid, ALICE, "500")).isEqualTo("CON Send 500 KES to 0712345678?\n1. Yes\n2. No");
        assertThat(ussdCall(sid, ALICE, "1")).isEqualTo("CON Enter PIN");
        String done = ussdCall(sid, ALICE, "4826");
        assertThat(done).startsWith("END Sent 500.00 KES to Bob Otieno.");
        assertThat(p2pCalls).hasSize(1);
        JsonNode sent = p2pCalls.getFirst();
        assertThat(sent.get("pin").asString()).isEqualTo("4826");
        assertThat(sent.get("payee_msisdn").asString()).isEqualTo(BOB);
        assertThat(sent.get("channel").asString()).isEqualTo("USSD");
        assertThat(balances.get(BOB)).isEqualByComparingTo("500");

        // a replay after the end answers with the last screen and never pays twice
        assertThat(ussdCall(sid, ALICE, "4826")).isEqualTo(done);
        assertThat(p2pCalls).hasSize(1);

        JsonNode session = get("/v1/ussd-sessions?msisdn=%2B254700000001").get("data").get(0);
        assertThat(session.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(session.get("wallet_transaction_id").isNull()).isFalse();
        assertThat(session.get("step_count").asInt()).isEqualTo(7);
        assertThat(session.has("context")).isFalse();
        JsonNode steps = get("/v1/ussd-sessions/" + session.get("id").asString() + "/steps");
        assertThat(steps.get("data")).hasSize(7);
        assertThat(steps.toString()).doesNotContain("4826").contains("****");
        String context = asTenant(() -> jdbc.sql("select context::text from ussd_session where session_id = ?")
                .param(sid).query(String.class).single());
        assertThat(context).doesNotContain("4826").contains("payee_msisdn");

        // wrong PIN: friendly message, session FAILED, nothing internal leaked
        String sid2 = "ATUid_" + UUID.randomUUID();
        ussdCall(sid2, ALICE, null);
        ussdCall(sid2, ALICE, "1");
        ussdCall(sid2, ALICE, BOB);
        ussdCall(sid2, ALICE, "100");
        ussdCall(sid2, ALICE, "1");
        assertThat(ussdCall(sid2, ALICE, "1111")).isEqualTo("END Wrong PIN.");
        assertThat(balances.get(BOB)).isEqualByComparingTo("500");

        // balance action with PIN; confirm "no" cancels
        String sid3 = "ATUid_" + UUID.randomUUID();
        ussdCall(sid3, ALICE, null);
        ussdCall(sid3, ALICE, "2");
        assertThat(ussdCall(sid3, ALICE, "4826")).isEqualTo("END Your balance is 9500.00 KES.");
        String sid4 = "ATUid_" + UUID.randomUUID();
        ussdCall(sid4, ALICE, null);
        ussdCall(sid4, ALICE, "1");
        ussdCall(sid4, ALICE, BOB);
        ussdCall(sid4, ALICE, "10");
        assertThat(ussdCall(sid4, ALICE, "2")).startsWith("END Cancelled");
        assertThat(get("/v1/ussd-sessions?status=ABORTED").get("data")).hasSize(1);

        // unknown subscriber
        assertThat(ussdCall("ATUid_" + UUID.randomUUID(), "+254799000000", null)).startsWith("END You are not registered");
    }

    @Test
    @Order(3)
    void sessionsTimeOutByTheDatabaseClock() throws Exception {
        String sid = "ATUid_" + UUID.randomUUID();
        ussdCall(sid, ALICE, null);
        asTenant(() -> jdbc.sql("update ussd_session set last_activity_at = now() - interval '10 minutes' where session_id = ?")
                .param(sid).update());
        assertThat(ussdCall(sid, ALICE, "1")).startsWith("END Your session timed out");
        String idle = "ATUid_" + UUID.randomUUID();
        ussdCall(idle, ALICE, null);
        asTenant(() -> jdbc.sql("update ussd_session set last_activity_at = now() - interval '10 minutes' where session_id = ?")
                .param(idle).update());
        JsonNode expired = post("/v1/ussd-sessions/expire", null, "scheduler", 200);
        assertThat(expired.get("timed_out").asInt()).isEqualTo(1);
        assertThat(get("/v1/ussd-sessions?status=TIMED_OUT").get("data")).hasSize(2);
    }

    // ------------------------------------------------------------------ 3. SMS

    @Test
    @Order(4)
    void smsCommandsRepliesAndDeliveryReports() throws Exception {
        post("/v1/sms-commands", """
                {"keyword":"send","short_code":"22222","language":"en","action_code":"P2P_SEND",
                 "syntax":"SEND amount msisdn pin","help_text":"SEND amount number PIN"}""", "ops", 201);
        JsonNode bal = post("/v1/sms-commands", """
                {"keyword":"Bal","short_code":"22222","language":"en","action_code":"BALANCE","syntax":"BAL",
                 "requires_pin":false}""", "ops", 201);
        assertThat(bal.get("keyword").asString()).isEqualTo("BAL");
        assertThat(post("/v1/sms-commands", """
                {"keyword":"SEND","short_code":"22222","language":"en","action_code":"P2P_SEND"}""", "ops", 409)
                .get("code").asString()).isEqualTo("keyword_exists");
        post("/v1/sms-commands", """
                {"keyword":"X","short_code":"22222","language":"en","action_code":"FORMAT_DISK"}""", "ops", 422);

        Map<String, Object> in = Map.of("msisdn", ALICE, "short_code", "22222", "body", "send 250 0712345678 4826",
                "provider_message_id", "gw-1", "mno", "SAFARICOM");
        assertThat(callbackRaw("/v1/sms-messages/inbound", in, "bad").statusCode()).isEqualTo(401);
        JsonNode r = json.readTree(signed("/v1/sms-messages/inbound", in).body());
        assertThat(r.get("reply").asString()).startsWith("Sent 250.00 KES to Bob Otieno");
        assertThat(r.get("reply_status").asString()).isEqualTo("SENT");
        assertThat(balances.get(BOB)).isEqualByComparingTo("750");
        assertThat(sms.getLast().get("destination").asString()).isEqualTo(ALICE);
        assertThat(sms.getLast().get("channel").asString()).isEqualTo("SMS");
        JsonNode replay = json.readTree(signed("/v1/sms-messages/inbound", in).body());
        assertThat(replay.get("inbound_id").asString()).isEqualTo(r.get("inbound_id").asString());
        assertThat(p2pCalls).hasSize(3);

        JsonNode inbound = get("/v1/sms-messages/" + r.get("inbound_id").asString());
        assertThat(inbound.get("body").asString()).isEqualTo("send 250 0712345678 ****");
        assertThat(inbound.get("direction").asString()).isEqualTo("INBOUND");
        JsonNode reply = get("/v1/sms-messages/" + r.get("reply_id").asString());
        assertThat(reply.get("reply_to_id").asString()).isEqualTo(r.get("inbound_id").asString());
        assertThat(reply.get("notification_id").isNull()).isFalse();

        JsonNode bal2 = json.readTree(signed("/v1/sms-messages/inbound", Map.of("msisdn", ALICE, "short_code", "22222",
                "body", "BAL", "provider_message_id", "gw-2")).body());
        assertThat(bal2.get("reply").asString()).isEqualTo("Your balance is 9250.00 KES.");
        JsonNode unknown = json.readTree(signed("/v1/sms-messages/inbound", Map.of("msisdn", ALICE, "short_code", "22222",
                "body", "foo bar", "provider_message_id", "gw-3")).body());
        assertThat(unknown.get("reply").asString()).isEqualTo("Unknown command FOO. Commands: BAL, SEND.");

        String providerId = reply.get("provider_message_id").asString();
        JsonNode delivered = json.readTree(signed("/v1/sms-messages/delivery-reports",
                Map.of("provider_message_id", providerId, "status", "DELIVERED")).body());
        assertThat(delivered.get("status").asString()).isEqualTo("DELIVERED");
        JsonNode backwards = json.readTree(signed("/v1/sms-messages/delivery-reports",
                Map.of("provider_message_id", providerId, "status", "SENT")).body());
        assertThat(backwards.get("applied").asBoolean()).isFalse();
        assertThat(get("/v1/sms-messages/" + r.get("reply_id").asString()).get("status").asString()).isEqualTo("DELIVERED");
        assertThat(signed("/v1/sms-messages/delivery-reports", Map.of("provider_message_id", "nope", "status", "DELIVERED"))
                .statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ 4. isolation and events

    @Test
    @Order(5)
    void tenantIsolationAndEvents() throws Exception {
        assertThat(raw("GET", "/v1/ussd-menus/" + menu, null, null, "x", OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(json.readTree(raw("GET", "/v1/ussd-sessions", null, null, "x", OTHER_TENANT).body()).get("data")).isEmpty();
        assertThat(json.readTree(raw("GET", "/v1/sms-messages", null, null, "x", OTHER_TENANT).body()).get("data")).isEmpty();
        List<String> types = asTenant(() -> jdbc.sql("select distinct event_type from outbox").query(String.class).list());
        assertThat(types).contains("wallets.ussd_menu.published", "wallets.ussd_session.completed",
                "wallets.sms_message.received", "wallets.sms_message.delivery_updated");
        String payloads = asTenant(() -> jdbc.sql("select string_agg(payload::text, ' ') from outbox").query(String.class).single());
        assertThat(payloads).doesNotContain("4826 ").doesNotContain("\"context\"");
    }

    // ------------------------------------------------------------------ helpers

    private String findNode(String key) throws Exception {
        for (JsonNode n : get("/v1/ussd-menus/" + menu + "/nodes?limit=200").get("data")) {
            if (key.equals(n.get("node_key").asString())) {
                return n.get("id").asString();
            }
        }
        throw new AssertionError(key);
    }

    private JsonNode node(String body, int expected) throws Exception {
        ObjectNode b = (ObjectNode) json.readTree(body);
        b.put("ussd_menu_id", menu);
        return post("/v1/ussd-menu-nodes", b.toString(), "maker", expected);
    }

    private Map<String, Object> ussd(String sid, String msisdn, String input) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_id", sid);
        m.put("msisdn", msisdn);
        m.put("short_code", CODE);
        m.put("mno", "SAFARICOM");
        if (input != null) {
            m.put("input", input);
        }
        return m;
    }

    private String ussdCall(String sid, String msisdn, String input) throws Exception {
        HttpResponse<String> r = signed("/v1/ussd-sessions/callback", ussd(sid, msisdn, input));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
        return r.body();
    }

    private HttpResponse<String> signed(String path, Map<String, Object> body) throws Exception {
        return callbackRaw(path, body, GatewaySignature.sign(SECRET, body));
    }

    private HttpResponse<String> callbackRaw(Map<String, Object> body, String signature) throws Exception {
        return callbackRaw("/v1/ussd-sessions/callback", body, signature);
    }

    private HttpResponse<String> callbackRaw(String path, Map<String, Object> body, String signature) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path + "?tenant_id=" + TENANT))
                .header("Content-Type", "application/json").header(GatewaySignature.HEADER, signature)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private <T> T asTenant(Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", body);
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
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
