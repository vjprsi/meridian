package io.meridian.interop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.interop.domain.InteropCrypto;
import io.meridian.interop.infrastructure.SimulatedSwitch;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * interop-service end to end over HTTP against PostgreSQL 18 (RLS enforced). wallet-service (holds, confirm, cancel,
 * credits) and the settlement ledger balance are a stateful {@link FakeWalletService} behind the mocked
 * {@link ServiceClient}; the switch is the built-in simulator and its answers are posted to the signed public callbacks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.interop.switch-secrets.MOJA=s3cret-moja"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InteropFlowIT {
    static final String TENANT = "0199c000-0000-7000-8000-00000000c001";
    static final String OTHER_TENANT = "0199c000-0000-7000-8000-00000000c002";
    static final UUID SETTLEMENT = UUID.fromString("0199c000-0000-7000-8000-0000000005e1");
    static final String SECRET = "s3cret-moja";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SimulatedSwitch simulator;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakeWalletService wallets;
    UUID alice;
    UUID bob;
    String sw;

    @BeforeAll
    void setUp() {
        wallets = new FakeWalletService(json);
        alice = wallets.wallet("+254700000011", "Alice Wanjiru", "KES", "50000", "ACTIVE");
        bob = wallets.wallet("+254722000001", "Bob Otieno", "KES", "0", "ACTIVE");
    }

    @BeforeEach
    void stub() {
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> wallets.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        when(serviceClient.get(anyString(), anyString()))
                .thenAnswer(i -> wallets.get(i.getArgument(0), i.getArgument(1)));
    }

    // ------------------------------------------------------------------ 1. switch and directory

    @Test
    @Order(1)
    void switchSetupAndDirectorySync() throws Exception {
        post("/v1/interop-switches", switchBody("BAD", "TELEX"), 422);
        sw = post("/v1/interop-switches", switchBody("MOJA", "MOJALOOP_FSPIOP"), 201).get("id").asString();
        post("/v1/interop-participants", """
                {"interop_switch_id":"%s","fsp_id":"oldfsp","name":"Old FSP","participant_type":"BANK"}""".formatted(sw), 201);
        post("/v1/interop-participants", """
                {"interop_switch_id":"%s","fsp_id":"oldfsp","name":"Old FSP","participant_type":"BANK"}""".formatted(sw), 409);
        JsonNode sync = post("/v1/interop-switches/" + sw + "/sync-participants", null, 200);
        assertThat(sync.get("added").asInt()).isEqualTo(4);
        assertThat(sync.get("deactivated").asInt()).isEqualTo(1);
        JsonNode again = post("/v1/interop-switches/" + sw + "/sync-participants", null, 200);
        assertThat(again.get("added").asInt()).isZero();
        assertThat(again.get("updated").asInt()).isEqualTo(4);
        assertThat(get("/v1/interop-switches/" + sw + "/participants").get("data")).hasSize(5);
        assertThat(get("/v1/interop-switches/" + sw).get("last_directory_sync_at").isNull()).isFalse();
        // code is fixed
        HttpResponse<String> patch = raw("PATCH", "/v1/interop-switches/" + sw, "{\"code\":\"X\"}", null, TENANT, null);
        assertThat(patch.statusCode()).isEqualTo(422);

        JsonNode missing = post("/v1/interop-quotes/lookup-party", """
                {"interop_switch_id":"%s","party_type":"MSISDN","party_id":"+254711000000"}""".formatted(sw), 200);
        assertThat(missing.get("found").asBoolean()).isFalse();
        JsonNode party = post("/v1/interop-quotes/lookup-party", """
                {"interop_switch_id":"%s","party_type":"MSISDN","party_id":"+254711223344"}""".formatted(sw), 200);
        assertThat(party.get("found").asBoolean()).isTrue();
        assertThat(party.get("fsp_id").asString()).isIn("mtnmoney", "airtelmoney", "equitybank", "faulumfi");
    }

    // ------------------------------------------------------------------ 2. outbound commit

    @Test
    @Order(2)
    void outboundReserveThenFulfilCommits() throws Exception {
        JsonNode quote = quote("1000");
        assertThat(quote.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(quote.get("direction").asString()).isEqualTo("OUTBOUND");
        assertThat(quote.get("fee_amount").asString()).startsWith("20.00");
        assertThat(quote.get("expires_at").isNull()).isFalse();
        assertThat(quote.has("fulfilment")).isFalse();

        String key = UUID.randomUUID().toString();
        String body = transferBody(quote, "1000");
        JsonNode t = send("POST", "/v1/interop-transfers", body, key, TENANT, null, 201);
        JsonNode replay = send("POST", "/v1/interop-transfers", body, key, TENANT, null, 201);
        assertThat(replay.get("id").asString()).isEqualTo(t.get("id").asString());
        assertThat(t.get("status").asString()).isEqualTo("RESERVED");
        assertThat(t.get("wallet_transaction_id").isNull()).isFalse();
        assertThat(t.get("hold_id").isNull()).isFalse();
        assertThat(wallets.held(alice)).isEqualByComparingTo("1020");
        JsonNode reserve = wallets.calls("POST", "/v1/wallet-transactions").getLast().body();
        assertThat(reserve.get("txn_type").asString()).isEqualTo("INTEROP_OUT");
        assertThat(reserve.get("two_phase").asBoolean()).isTrue();
        assertThat(reserve.get("counterparty_ledger_account_id").asString()).isEqualTo(SETTLEMENT.toString());
        assertThat(wallets.calls("POST", "/v1/wallet-transactions").getLast().key()).isEqualTo("itr-" + t.get("id").asString() + "-reserve");
        assertThat(simulator.prepared).contains(t.get("transfer_id").asString());
        post("/v1/interop-transfers", transferBody(quote, "1000"), 409);

        String fulfilment = simulator.fulfilmentFor(quote.get("quote_id").asString());
        JsonNode done = callback("/v1/interop-transfers/callbacks", """
                {"type":"TRANSFER_FULFIL","transfer_id":"%s","fulfilment":"%s","transfer_state":"COMMITTED"}"""
                .formatted(t.get("transfer_id").asString(), fulfilment), 200);
        assertThat(done.get("status").asString()).isEqualTo("COMMITTED");
        assertThat(wallets.balance(alice)).isEqualByComparingTo("48980");
        assertThat(wallets.held(alice)).isEqualByComparingTo("0");
        assertThat(wallets.account(SETTLEMENT)).isEqualByComparingTo("1020");
        // replayed fulfilment is a no-op
        JsonNode again = callback("/v1/interop-transfers/callbacks", """
                {"type":"TRANSFER_FULFIL","transfer_id":"%s","fulfilment":"%s","transfer_state":"COMMITTED"}"""
                .formatted(t.get("transfer_id").asString(), fulfilment), 200);
        assertThat(again.get("status").asString()).isEqualTo("COMMITTED");
        assertThat(wallets.calls("POST", "/v1/wallet-transactions/")).filteredOn(c -> c.path().endsWith("/confirm")).hasSize(1);
        JsonNode stored = get("/v1/interop-transfers/" + t.get("id").asString());
        assertThat(stored.get("completed_at").isNull()).isFalse();
        assertThat(stored.has("fulfilment")).isFalse();

        JsonNode pos = get("/v1/interop-switches/" + sw + "/position");
        assertThat(new BigDecimal(pos.get("net_position").asString())).isEqualByComparingTo("1020");
        assertThat(new BigDecimal(pos.get("headroom").asString())).isEqualByComparingTo("8980");
        assertThat(new BigDecimal(pos.get("settlement_balance").asString())).isEqualByComparingTo("1020");
    }

    // ------------------------------------------------------------------ 3. abort, error, bad fulfilment

    @Test
    @Order(3)
    void abortErrorAndInvalidFulfilmentReleaseTheFunds() throws Exception {
        JsonNode t1 = post("/v1/interop-transfers", transferBody(quote("500"), "500"), 201);
        JsonNode aborted = post("/v1/interop-transfers/" + t1.get("id").asString() + "/abort", "{\"reason\":\"Customer cancelled\"}", 200);
        assertThat(aborted.get("status").asString()).isEqualTo("ABORTED");
        assertThat(wallets.held(alice)).isEqualByComparingTo("0");
        assertThat(post("/v1/interop-transfers/" + t1.get("id").asString() + "/abort", "{\"reason\":\"again\"}", 200)
                .get("status").asString()).isEqualTo("ABORTED");

        JsonNode t2 = post("/v1/interop-transfers", transferBody(quote("400"), "400"), 201);
        JsonNode err = callback("/v1/interop-transfers/callbacks", """
                {"type":"TRANSFER_ERROR","transfer_id":"%s","error_code":"5100","error_description":"Payee limit"}"""
                .formatted(t2.get("transfer_id").asString()), 200);
        assertThat(err.get("status").asString()).isEqualTo("ABORTED");
        assertThat(err.get("error_code").asString()).isEqualTo("5100");

        JsonNode t3 = post("/v1/interop-transfers", transferBody(quote("300"), "300"), 201);
        JsonNode bad = callback("/v1/interop-transfers/callbacks", """
                {"type":"TRANSFER_FULFIL","transfer_id":"%s","fulfilment":"%s","transfer_state":"COMMITTED"}"""
                .formatted(t3.get("transfer_id").asString(), InteropCrypto.newFulfilment()), 422);
        assertThat(bad.get("code").asString()).isEqualTo("invalid_fulfilment");
        JsonNode t3after = get("/v1/interop-transfers/" + t3.get("id").asString());
        assertThat(t3after.get("status").asString()).isEqualTo("ABORTED");
        assertThat(t3after.get("error_code").asString()).isEqualTo("INVALID_FULFILMENT");
        assertThat(wallets.held(alice)).isEqualByComparingTo("0");
        assertThat(wallets.balance(alice)).isEqualByComparingTo("48980");
    }

    // ------------------------------------------------------------------ 4. limits, validation, expiry

    @Test
    @Order(4)
    void netDebitCapValidationAndExpiry() throws Exception {
        JsonNode big = quote("9000");
        JsonNode r = post("/v1/interop-transfers", transferBody(big, "9000"), 422);
        assertThat(r.get("code").asString()).isEqualTo("net_debit_cap_exceeded");
        assertThat(post("/v1/interop-transfers", transferBody(big, "8000"), 422).get("code").asString()).isEqualTo("quote_mismatch");
        assertThat(post("/v1/interop-quotes", """
                {"interop_switch_id":"%s","payer_wallet_id":"%s","payee_party_type":"MSISDN","payee_party_id":"+254733445566",
                 "payee_fsp_id":"nosuchfsp","amount":"10","currency":"KES"}""".formatted(sw, alice), 422)
                .get("code").asString()).isEqualTo("participant_not_found");
        assertThat(post("/v1/interop-quotes", """
                {"interop_switch_id":"%s","payer_wallet_id":"%s","payee_party_type":"MSISDN","payee_party_id":"+254733445566",
                 "amount":"10","currency":"USD"}""".formatted(sw, alice), 422).get("code").asString()).isEqualTo("currency_not_supported");

        JsonNode t = post("/v1/interop-transfers", transferBody(quote("200"), "200"), 201);
        JsonNode stale = quote("50");
        asTenant(() -> jdbc.sql("update interop_transfer set expires_at = now() - interval '1 second' where id = ?::uuid")
                .param(t.get("id").asString()).update());
        asTenant(() -> jdbc.sql("update interop_quote set expires_at = now() - interval '1 second' where id = ?::uuid")
                .param(stale.get("id").asString()).update());
        assertThat(post("/v1/interop-transfers", transferBody(stale, "50"), 422).get("code").asString()).isEqualTo("quote_expired");
        JsonNode exp = post("/v1/interop-transfers/expire", null, 200);
        assertThat(exp.get("transfers_aborted").asInt()).isEqualTo(1);
        assertThat(exp.get("quotes_expired").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode after = get("/v1/interop-transfers/" + t.get("id").asString());
        assertThat(after.get("status").asString()).isEqualTo("ABORTED");
        assertThat(after.get("error_code").asString()).isEqualTo("EXPIRED");
        assertThat(get("/v1/interop-quotes/" + stale.get("id").asString()).get("status").asString()).isEqualTo("EXPIRED");
        assertThat(wallets.held(alice)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ 5. signatures

    @Test
    @Order(5)
    void callbacksRequireAValidSignature() throws Exception {
        String body = "{\"switch_code\":\"MOJA\",\"payload\":{\"type\":\"TRANSFER_ERROR\",\"transfer_id\":\"x\"}}";
        assertThat(raw("POST", "/v1/interop-transfers/callbacks?tenant_id=" + TENANT, body, null, null, null).statusCode()).isEqualTo(401);
        assertThat(raw("POST", "/v1/interop-transfers/callbacks?tenant_id=" + TENANT, body, null, null,
                InteropCrypto.sign("wrong", json.readTree(body))).statusCode()).isEqualTo(401);
        String other = "{\"switch_code\":\"NOPE\",\"payload\":{\"type\":\"QUOTE_REQUEST\"}}";
        assertThat(raw("POST", "/v1/interop-quotes/callbacks?tenant_id=" + TENANT, other, null, null,
                InteropCrypto.sign(SECRET, json.readTree(other))).statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ 6. inbound

    @Test
    @Order(6)
    void inboundQuoteAndPrepareCreditThePayee() throws Exception {
        String req = """
                {"type":"QUOTE_REQUEST","quote_id":"q-in-1","payee_party_type":"MSISDN","payee_party_id":"+254722000001",
                 "amount":"300","currency":"KES","payer_fsp_id":"mtnmoney"}""";
        JsonNode q = callback("/v1/interop-quotes/callbacks", req, 200);
        assertThat(q.get("status").asString()).isEqualTo("ACCEPTED");
        String condition = q.get("ilp_condition").asString();
        assertThat(q.get("payee_name").asString()).isEqualTo("Bob Otieno");
        assertThat(callback("/v1/interop-quotes/callbacks", req, 200).get("ilp_condition").asString()).isEqualTo(condition);
        JsonNode unknown = callback("/v1/interop-quotes/callbacks", """
                {"type":"QUOTE_REQUEST","quote_id":"q-in-2","payee_party_type":"MSISDN","payee_party_id":"+254799999999",
                 "amount":"10","currency":"KES","payer_fsp_id":"mtnmoney"}""", 200);
        assertThat(unknown.get("status").asString()).isEqualTo("REJECTED");

        String prepare = """
                {"type":"TRANSFER_PREPARE","transfer_id":"t-in-1","quote_id":"q-in-1","amount":"300","currency":"KES",
                 "payer_fsp_id":"mtnmoney","ilp_condition":"%s"}""".formatted(condition);
        JsonNode done = callback("/v1/interop-transfers/callbacks", prepare, 200);
        assertThat(done.get("status").asString()).isEqualTo("COMMITTED");
        String fulfilment = done.get("fulfilment").asString();
        assertThat(InteropCrypto.verify(fulfilment, condition)).isTrue();
        assertThat(wallets.balance(bob)).isEqualByComparingTo("300");
        assertThat(wallets.account(SETTLEMENT)).isEqualByComparingTo("720");
        JsonNode replay = callback("/v1/interop-transfers/callbacks", prepare, 200);
        assertThat(replay.get("fulfilment").asString()).isEqualTo(fulfilment);
        assertThat(wallets.balance(bob)).isEqualByComparingTo("300");
        JsonNode credit = wallets.calls("POST", "/v1/wallet-transactions").stream()
                .filter(c -> "INTEROP_IN".equals(c.body().path("txn_type").asString())).toList().getLast().body();
        assertThat(credit.get("payee_wallet_id").asString()).isEqualTo(bob.toString());

        // a second prepare on a new quote with a different amount is refused
        JsonNode q3 = callback("/v1/interop-quotes/callbacks", """
                {"type":"QUOTE_REQUEST","quote_id":"q-in-3","payee_party_type":"MSISDN","payee_party_id":"+254722000001",
                 "amount":"100","currency":"KES","payer_fsp_id":"mtnmoney"}""", 200);
        JsonNode refused = callback("/v1/interop-transfers/callbacks", """
                {"type":"TRANSFER_PREPARE","transfer_id":"t-in-3","quote_id":"q-in-3","amount":"150","currency":"KES",
                 "payer_fsp_id":"mtnmoney","ilp_condition":"%s"}""".formatted(q3.get("ilp_condition").asString()), 200);
        assertThat(refused.get("status").asString()).isEqualTo("ABORTED");
        assertThat(refused.has("fulfilment")).isFalse();
        assertThat(wallets.balance(bob)).isEqualByComparingTo("300");

        JsonNode inbound = get("/v1/interop-transfers?direction=INBOUND");
        assertThat(inbound.get("data")).hasSize(2);
    }

    // ------------------------------------------------------------------ 7. isolation and events

    @Test
    @Order(7)
    void tenantIsolationAndEvents() throws Exception {
        assertThat(raw("GET", "/v1/interop-switches/" + sw, null, null, OTHER_TENANT, null).statusCode()).isEqualTo(404);
        assertThat(json.readTree(raw("GET", "/v1/interop-transfers", null, null, OTHER_TENANT, null).body()).get("data")).isEmpty();
        // a callback signed for MOJA but addressed to another tenant finds no switch there
        String body = "{\"switch_code\":\"MOJA\",\"payload\":{\"type\":\"TRANSFER_ERROR\",\"transfer_id\":\"x\"}}";
        assertThat(raw("POST", "/v1/interop-transfers/callbacks?tenant_id=" + OTHER_TENANT, body, null, null,
                InteropCrypto.sign(SECRET, json.readTree(body))).statusCode()).isEqualTo(404);
        List<String> types = asTenant(() -> jdbc.sql("select distinct event_type from outbox").query(String.class).list());
        assertThat(types).containsExactlyInAnyOrder("wallets.interop_quote.received", "wallets.interop_transfer.reserved",
                "wallets.interop_transfer.committed", "wallets.interop_transfer.aborted");
        String payloads = asTenant(() -> jdbc.sql("select string_agg(payload::text, ' ') from outbox").query(String.class).single());
        assertThat(payloads).doesNotContain("\"fulfilment\"");
    }

    // ------------------------------------------------------------------ helpers

    private String switchBody(String code, String protocol) {
        return """
                {"code":"%s","name":"National hub","switch_type":"MOJALOOP_HUB","country":"KE","currency":"KES",
                 "protocol":"%s","participant_id":"meridianfsp","settlement_account_id":"%s","net_debit_cap":"10000",
                 "fee_flat":"10","fee_percentage":"0.01","quote_validity_seconds":60,"transfer_timeout_seconds":60}"""
                .formatted(code, protocol, SETTLEMENT);
    }

    private JsonNode quote(String amount) throws Exception {
        return post("/v1/interop-quotes", """
                {"interop_switch_id":"%s","payer_wallet_id":"%s","payee_party_type":"MSISDN","payee_party_id":"+254733445566",
                 "payee_fsp_id":"airtelmoney","amount":"%s","currency":"KES"}""".formatted(sw, alice, amount), 201);
    }

    private String transferBody(JsonNode quote, String amount) {
        return """
                {"interop_switch_id":"%s","interop_quote_id":"%s","payer_wallet_id":"%s","counterparty_fsp_id":"airtelmoney",
                 "amount":"%s","currency":"KES"}""".formatted(sw, quote.get("id").asString(), alice, amount);
    }

    private JsonNode callback(String path, String payload, int expected) throws Exception {
        String body = "{\"switch_code\":\"MOJA\",\"payload\":" + payload + "}";
        String sig = InteropCrypto.sign(SECRET, json.readTree(body));
        HttpResponse<String> r = raw("POST", path + "?tenant_id=" + TENANT, body, null, null, sig);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private <T> T asTenant(java.util.concurrent.Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", body);
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        return send("POST", path, body, UUID.randomUUID().toString(), TENANT, null, expected);
    }

    private JsonNode get(String path) throws Exception {
        return send("GET", path, null, null, TENANT, null, 200);
    }

    private JsonNode send(String method, String path, String body, String key, String tenant, String sig, int expected)
            throws Exception {
        HttpResponse<String> r = raw(method, path, body, key, tenant, sig);
        assertThat(r.statusCode()).as(method + " " + path + " -> " + r.body()).isEqualTo(expected);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private HttpResponse<String> raw(String method, String path, String body, String key, String tenant, String sig)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-User", "ops").header("Content-Type", "application/json");
        if (tenant != null) {
            b.header("X-Tenant-Id", tenant);
        }
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        if (sig != null) {
            b.header("X-Switch-Signature", sig);
        }
        if ("PATCH".equals(method)) {
            b.header("If-Match", "\"1\"");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
