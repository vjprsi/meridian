package io.meridian.cardauthorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * card-authorization-service end to end over HTTP against PostgreSQL 18 (RLS enforced). card-service, ledger,
 * fraud, vault and clearing are stateful fakes behind the mocked {@link ServiceClient}; the program's JIT
 * endpoint is a real local HTTP server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CardAuthorizationFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000ca001";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000ca002";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    @Autowired
    io.meridian.cardauthorization.application.AuthorizationService authorizationService;

    final HttpClient http = HttpClient.newHttpClient();
    CardAuthFakes fakes;
    HttpServer jitServer;
    final AtomicReference<String> jitMode = new AtomicReference<>("APPROVE");

    final UUID programId = UUID.randomUUID();
    final UUID closedProgramId = UUID.randomUUID();
    final UUID jitProgramId = UUID.randomUUID();
    final UUID programAccount = UUID.randomUUID();
    final UUID cardA = UUID.randomUUID();
    final UUID accA = UUID.randomUUID();
    final UUID cardVel = UUID.randomUUID();
    final UUID cardFrozen = UUID.randomUUID();
    final UUID cardExpired = UUID.randomUUID();
    final UUID cardPoor = UUID.randomUUID();
    final UUID accPoor = UUID.randomUUID();
    final UUID cardClosedPolicy = UUID.randomUUID();
    final UUID cardJit = UUID.randomUUID();
    final UUID cardPerf = UUID.randomUUID();
    final UUID accPerf = UUID.randomUUID();

    @BeforeAll
    void setUp() throws Exception {
        fakes = new CardAuthFakes(json);
        jitServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jitServer.createContext("/jit", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String sig = ex.getRequestHeaders().getFirst("X-Signature");
            String mode = jitMode.get();
            String resp;
            if (sig == null || sig.length() != 64) {
                resp = "{\"approved\":false,\"decline_reason\":\"bad signature\"}";
            } else if ("SLOW".equals(mode)) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                resp = "{\"approved\":true}";
            } else if ("DECLINE".equals(mode)) {
                resp = "{\"approved\":false,\"decline_reason\":\"client balance low\"}";
            } else {
                resp = body.contains("\"amount\"") ? "{\"approved\":true}" : "{\"approved\":false}";
            }
            byte[] out = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        jitServer.start();

        ObjectNode program = fakes.program(programId, "DEBIT", programAccount);
        ObjectNode ctxA = fakes.card(cardA, program, accA, new BigDecimal("5000"));
        fakes.control(ctxA, "MCC_DENY", "7995");
        fakes.control(ctxA, "COUNTRY_DENY", "KP");
        ObjectNode max = fakes.control(ctxA, "AMOUNT_MAX");
        max.put("max_amount", "1500");
        max.put("currency", "USD");
        fakes.limit(ctxA, "DAILY", "1000", null);

        ObjectNode ctxVel = fakes.card(cardVel, program, UUID.randomUUID(), new BigDecimal("1000"));
        fakes.limit(ctxVel, "DAILY", null, 2);

        ObjectNode frozen = fakes.card(cardFrozen, program, UUID.randomUUID(), new BigDecimal("100"));
        ((ObjectNode) frozen.get("card")).put("status", "FROZEN");
        ObjectNode expired = fakes.card(cardExpired, program, UUID.randomUUID(), new BigDecimal("100"));
        ((ObjectNode) expired.get("card")).put("expiry_year", 2020);

        fakes.card(cardPoor, program, accPoor, new BigDecimal("50"));

        ObjectNode closedProgram = fakes.program(closedProgramId, "DEBIT", null);
        closedProgram.put("fraud_policy", "FAIL_CLOSED");
        closedProgram.put("fraud_timeout_ms", 100);
        fakes.card(cardClosedPolicy, closedProgram, UUID.randomUUID(), new BigDecimal("500"));

        ObjectNode jitProgram = fakes.program(jitProgramId, "JIT", programAccount);
        jitProgram.put("jit_webhook_url", "http://127.0.0.1:" + jitServer.getAddress().getPort() + "/jit");
        jitProgram.put("jit_timeout_ms", 400);
        fakes.balances.put(programAccount, new BigDecimal("100000"));
        fakes.card(cardJit, jitProgram, UUID.randomUUID(), BigDecimal.ZERO);

        ObjectNode perf = fakes.card(cardPerf, program, accPerf, new BigDecimal("10000000"));
        fakes.limit(perf, "DAILY", "10000000", 100_000);
        fakes.control(perf, "MCC_DENY", "7995", "6051");
    }

    @AfterAll
    void tearDown() {
        jitServer.stop(0);
    }

    /** Mockito beans are reset after every test, so the stubs are re-attached to the same fakes each time. */
    @BeforeEach
    void stub() {
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> fakes.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> fakes.get(i.getArgument(0), i.getArgument(1)));
        fakes.ledgerDown = false;
        fakes.fraudScore = 100;
        fakes.fraudDecision = "ALLOW";
        fakes.fraudDelayMs = 0;
        fakes.cvvResult = "M";
        fakes.pinResult = "VERIFIED";
        jitMode.set("APPROVE");
    }

    // ------------------------------------------------------------------ 1. approve + hold + counters

    @Test
    @Order(1)
    void approvesAndPlacesLedgerHold() throws Exception {
        JsonNode a = simulate(cardA, "25.00", "USD", "5411", null);
        assertThat(a.get("decision").asString()).isEqualTo("APPROVED");
        assertThat(a.get("response_code").asString()).isEqualTo("00");
        assertThat(a.get("auth_code").asString()).matches("[A-Z0-9]{6}");
        assertThat(a.get("status").asString()).isEqualTo("APPROVED");
        assertThat(a.get("funding_source").asString()).isEqualTo("LEDGER_HOLD");
        assertThat(a.get("card_program_id").asString()).isEqualTo(programId.toString());
        assertThat(new BigDecimal(a.get("approved_amount").asString())).isEqualByComparingTo("25.00");
        JsonNode hold = fakes.hold(a.get("hold_id").asString());
        assertThat(hold.get("ledger_account_id").asString()).isEqualTo(accA.toString());
        assertThat(hold.get("amount").asString()).isEqualTo("25.00");
        assertThat(hold.get("hold_type").asString()).isEqualTo("CARD_AUTHORIZATION");
        assertThat(hold.get("source_id").asString()).isEqualTo(a.get("id").asString());
        assertThat(hold.hasNonNull("expires_at")).isTrue();
        assertThat(fakes.calls("ledger-service", "/v1/holds").getLast().key()).isEqualTo("card-auth-hold:" + a.get("id").asString());
        assertThat(a.get("fraud_score").asInt()).isEqualTo(100);
        JsonNode fraudCall = fakes.calls("fraud-service", "/v1/risk/fraud/scores/evaluate").getLast().body();
        assertThat(fraudCall.get("subject_type").asString()).isEqualTo("CARD_AUTHORIZATION");
        assertThat(fraudCall.get("subject_id").asString()).isEqualTo(a.get("id").asString());
        assertThat(events(a.get("id").asString())).contains("cards.card_authorization.approved");
        JsonNode usage = get("/v1/card-authorizations/usage?card_id=" + cardA, TENANT, 200);
        assertThat(new BigDecimal(usage.get("counters").get(0).get("used_amount").asString())).isEqualByComparingTo("25");
        assertThat(usage.get("counters").get(0).get("used_count").asInt()).isEqualTo(1);
        assertThat(a.get("processing_time_ms").asInt()).isLessThan(1000);
    }

    // ------------------------------------------------------------------ 2. network message: idempotency, vault, sanitising

    @Test
    @Order(2)
    void networkMessageIsIdempotentVerifiedAndSanitised() throws Exception {
        String body = """
                {"card_id":"%s","message_type":"AUTHORIZATION","mti":"0100","processing_code":"000000","stan":"000123",
                 "rrn":"612345678901","network_transaction_id":"NTX-1001","channel":"ECOM","pos_entry_mode":"ECOMMERCE",
                 "merchant_id":"AMZ-1","merchant_name":"Online Shop","merchant_country":"US","mcc":"5732",
                 "transaction_amount":"40.00","transaction_currency":"USD","cvv":"123","expiry_month":12,"expiry_year":2030,
                 "raw_message":{"de2":"4111111111111111","de4":"000000004000","de48":{"cvv2":"123","tag":"x"},"de49":"840"}}
                """.formatted(cardA);
        HttpResponse<String> r1 = send("POST", "/v1/card-authorizations", body, "net-1001", TENANT);
        assertThat(r1.statusCode()).as(r1.body()).isEqualTo(201);
        JsonNode a = json.readTree(r1.body());
        assertThat(a.get("decision").asString()).isEqualTo("APPROVED");
        assertThat(a.get("cvv_result").asString()).isEqualTo("M");
        assertThat(a.has("cvv")).isFalse();
        JsonNode vaultCall = fakes.calls("card-vault-service", "/v1/card-vault/verify").getLast().body();
        assertThat(vaultCall.get("cvv").asString()).isEqualTo("123");
        assertThat(vaultCall.get("pan_token").asString()).startsWith("tok_");
        int holdsBefore = fakes.holds.size();
        HttpResponse<String> r2 = send("POST", "/v1/card-authorizations", body, "net-1001", TENANT);
        assertThat(r2.statusCode()).isEqualTo(201);
        assertThat(r2.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(r2.body()).get("id").asString()).isEqualTo(a.get("id").asString());
        assertThat(fakes.holds).hasSize(holdsBefore);
        String stored = asTenant(() -> jdbc.sql("select raw_message::text from card_authorization where id = ?::uuid")
                .param(a.get("id").asString()).query(String.class).single());
        assertThat(stored).contains("de4").doesNotContain("de2").doesNotContain("4111111111111111").doesNotContain("cvv2");
        String everything = asTenant(() -> jdbc.sql("""
                select coalesce(string_agg(row_to_json(t)::text, ''), '') from card_authorization t""").query(String.class).single()
                + jdbc.sql("select coalesce(string_agg(payload::text, ''), '') from outbox").query(String.class).single());
        assertThat(everything).doesNotContain("4111111111111111").doesNotContain("\"cvv\"");
    }

    // ------------------------------------------------------------------ 3. declines

    @Test
    @Order(3)
    void declineClasses() throws Exception {
        assertDecline(simulate(cardFrozen, "10", "USD", "5411", null), "CARD_FROZEN", "57");
        assertDecline(simulate(cardExpired, "10", "USD", "5411", null), "CARD_EXPIRED", "54");
        assertDecline(simulate(cardA, "10", "USD", "7995", null), "CONTROL_MCC", "57");
        assertDecline(simulate(cardA, "10", "USD", "5411", "KP"), "CONTROL_COUNTRY", "57");
        assertDecline(simulate(cardA, "1600", "USD", "5411", null), "CONTROL_AMOUNT", "61");
        assertDecline(simulate(cardA, "990", "USD", "5411", null), "LIMIT_EXCEEDED", "61");
        assertThat(simulate(cardVel, "5", "USD", "5411", null).get("decision").asString()).isEqualTo("APPROVED");
        assertThat(simulate(cardVel, "5", "USD", "5411", null).get("decision").asString()).isEqualTo("APPROVED");
        assertDecline(simulate(cardVel, "5", "USD", "5411", null), "VELOCITY_EXCEEDED", "65");
        fakes.fraudScore = 900;
        fakes.fraudDecision = "REVIEW";
        JsonNode fraud = simulate(cardA, "10", "USD", "5411", null);
        assertDecline(fraud, "FRAUD_SUSPECTED", "59");
        assertThat(fraud.get("fraud_score").asInt()).isEqualTo(900);
        fakes.fraudScore = 100;
        assertDecline(simulate(cardPoor, "80", "USD", "5411", null), "INSUFFICIENT_FUNDS", "51");
        int holds = fakes.holds.size();
        JsonNode declined = simulate(cardA, "10", "USD", "7995", null);
        assertThat(declined.hasNonNull("hold_id")).isFalse();
        assertThat(fakes.holds).hasSize(holds);
        assertThat(events(declined.get("id").asString())).containsExactly("cards.card_authorization.declined");
    }

    @Test
    @Order(4)
    void cvvAndPinFailures() throws Exception {
        fakes.cvvResult = "N";
        String body = """
                {"card_id":"%s","channel":"ECOM","transaction_amount":"12.00","transaction_currency":"USD","mcc":"5411","cvv":"999"}
                """.formatted(cardA);
        JsonNode cvv = json.readTree(send("POST", "/v1/card-authorizations", body, "cvv-fail", TENANT).body());
        assertDecline(cvv, "INVALID_CVV", "N7");
        assertThat(cvv.get("cvv_result").asString()).isEqualTo("N");
        fakes.pinResult = "TRIES_EXCEEDED";
        String atm = """
                {"card_id":"%s","channel":"ATM","processing_code":"010000","transaction_amount":"20.00","transaction_currency":"USD",
                 "mcc":"6011","encrypted_pin_block":"AAAAAAAAAAAAAAAAAAAAAAAAAAAA","pin_key_id":"zpk-default"}
                """.formatted(cardA);
        JsonNode pin = json.readTree(send("POST", "/v1/card-authorizations", atm, "pin-fail", TENANT).body());
        assertDecline(pin, "PIN_TRIES_EXCEEDED", "75");
        assertThat(pin.get("pin_verified").asBoolean()).isFalse();
        String noPin = """
                {"card_id":"%s","channel":"ATM","transaction_amount":"20.00","transaction_currency":"USD","mcc":"6011"}
                """.formatted(cardA);
        assertDecline(json.readTree(send("POST", "/v1/card-authorizations", noPin, "atm-no-pin", TENANT).body()), "INVALID_PIN", "55");
    }

    @Test
    @Order(5)
    void fraudTimeoutFailOpenAndFailClosed() throws Exception {
        fakes.fraudDelayMs = 600;
        long t0 = System.nanoTime();
        JsonNode open = simulate(cardA, "11", "USD", "5411", null);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(open.get("decision").asString()).isEqualTo("APPROVED");
        assertThat(open.get("fraud_decision").asString()).isEqualTo("UNAVAILABLE");
        assertThat(ms).isLessThan(600);
        assertDecline(simulate(cardClosedPolicy, "11", "USD", "5411", null), "FRAUD_SUSPECTED", "59");
    }

    @Test
    @Order(6)
    void partialApprovalAndFx() throws Exception {
        JsonNode partial = post("/v1/card-authorizations/simulate", """
                {"card_id":"%s","amount":"80.00","currency":"USD","mcc":"5411","partial_approval_capable":true}
                """.formatted(cardPoor), "partial-1", TENANT, 200);
        assertThat(partial.get("decision").asString()).isEqualTo("PARTIAL");
        assertThat(partial.get("response_code").asString()).isEqualTo("10");
        assertThat(new BigDecimal(partial.get("approved_amount").asString())).isEqualByComparingTo("50.00");
        assertThat(fakes.available(accPoor)).isEqualByComparingTo("0");

        JsonNode fx = simulate(cardA, "100.00", "EUR", "5411", "FR");
        assertThat(fx.get("decision").asString()).as(fx.toString()).isEqualTo("APPROVED");
        assertThat(new BigDecimal(fx.get("billing_amount").asString())).isEqualByComparingTo("110.67");
        assertThat(new BigDecimal(fx.get("fx_markup").asString())).isEqualByComparingTo("2.17");
        assertThat(fx.get("billing_currency").asString()).isEqualTo("USD");
        assertThat(fx.get("is_international").asBoolean()).isTrue();
        assertThat(fakes.hold(fx.get("hold_id").asString()).get("amount").asString()).isEqualTo("110.67");
        assertDecline(simulate(cardA, "10", "CHF", "5411", null), "SYSTEM_ERROR", "96");
    }

    // ------------------------------------------------------------------ 4. incremental / reversal

    @Test
    @Order(7)
    void incrementalAndReversals() throws Exception {
        JsonNode a = simulate(cardA, "40.00", "USD", "7011", null);
        String id = a.get("id").asString();
        String holdId = a.get("hold_id").asString();
        BigDecimal usedBefore = dailyUsed(cardA);
        JsonNode inc = post("/v1/card-authorizations/" + id + "/simulate-incremental", "{\"amount\":\"10.00\"}", "inc-1", TENANT, 200);
        assertThat(inc.get("message_type").asString()).isEqualTo("INCREMENTAL");
        assertThat(inc.get("decision").asString()).isEqualTo("APPROVED");
        assertThat(fakes.hold(holdId).get("amount").asString()).isEqualTo("50.00");
        JsonNode parent = get("/v1/card-authorizations/" + id, TENANT, 200);
        assertThat(new BigDecimal(parent.get("approved_amount").asString())).isEqualByComparingTo("50");
        assertThat(dailyUsed(cardA)).isEqualByComparingTo(usedBefore.add(new BigDecimal("10")));
        assertThat(events(inc.get("id").asString())).contains("cards.card_authorization.incremented");

        JsonNode rev = post("/v1/card-authorizations/" + id + "/simulate-reversal", "{\"amount\":\"20.00\"}", "rev-1", TENANT, 200);
        assertThat(rev.get("message_type").asString()).isEqualTo("PARTIAL_REVERSAL");
        assertThat(fakes.hold(holdId).get("status").asString()).isEqualTo("RELEASED");
        parent = get("/v1/card-authorizations/" + id, TENANT, 200);
        assertThat(parent.get("status").asString()).isEqualTo("PARTIALLY_REVERSED");
        assertThat(new BigDecimal(parent.get("reversed_amount").asString())).isEqualByComparingTo("20");
        JsonNode rehold = fakes.hold(parent.get("hold_id").asString());
        assertThat(rehold.get("amount").asString()).isEqualTo("30.00");
        assertThat(rehold.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(dailyUsed(cardA)).isEqualByComparingTo(usedBefore.add(new BigDecimal("-10")));

        JsonNode full = post("/v1/card-authorizations/" + id + "/simulate-reversal", "{}", "rev-2", TENANT, 200);
        assertThat(full.get("message_type").asString()).isEqualTo("REVERSAL");
        parent = get("/v1/card-authorizations/" + id, TENANT, 200);
        assertThat(parent.get("status").asString()).isEqualTo("REVERSED");
        assertThat(rehold.get("status").asString()).isEqualTo("RELEASED");
        assertThat(dailyUsed(cardA)).isEqualByComparingTo(usedBefore.subtract(new BigDecimal("40")));
        assertThat(events(id)).contains("cards.card_authorization.reversed");
        JsonNode related = get("/v1/card-authorizations/" + id + "/related", TENANT, 200);
        assertThat(related.get("data")).hasSize(3);
        // reversal of an unknown original
        JsonNode orphan = post("/v1/card-authorizations", """
                {"card_id":"%s","message_type":"REVERSAL","parent_authorization_id":"%s","channel":"POS",
                 "transaction_amount":"5.00","transaction_currency":"USD"}""".formatted(cardA, UUID.randomUUID()),
                "orphan-rev", TENANT, 201);
        assertDecline(orphan, "ORIGINAL_NOT_FOUND", "25");
    }

    // ------------------------------------------------------------------ 5. JIT

    @Test
    @Order(8)
    void jitApproveDeclineAndTimeoutStandIn() throws Exception {
        JsonNode ok = simulate(cardJit, "30.00", "USD", "5411", null);
        assertThat(ok.get("decision").asString()).as(ok.toString()).isEqualTo("APPROVED");
        assertThat(ok.get("funding_source").asString()).isEqualTo("JIT");
        assertThat(ok.get("jit_used").asBoolean()).isTrue();
        assertThat(ok.get("jit_result").asString()).isEqualTo("APPROVED");
        assertThat(fakes.hold(ok.get("hold_id").asString()).get("ledger_account_id").asString()).isEqualTo(programAccount.toString());
        JsonNode jitLog = get("/v1/card-authorizations/" + ok.get("id").asString() + "/jit-requests", TENANT, 200);
        assertThat(jitLog.get("data")).hasSize(1);
        assertThat(jitLog.get("data").get(0).get("result").asString()).isEqualTo("APPROVED");
        assertThat(events(jitLog.get("data").get(0).get("id").asString())).contains("cards.card_jit_funding.requested");

        jitMode.set("DECLINE");
        assertDecline(simulate(cardJit, "30.00", "USD", "5411", null), "JIT_DECLINED", "51");

        jitMode.set("SLOW");
        JsonNode standIn = simulate(cardJit, "30.00", "USD", "5411", null);
        assertThat(standIn.get("decision").asString()).isEqualTo("APPROVED");
        assertThat(standIn.get("stand_in").asBoolean()).isTrue();
        assertThat(standIn.get("jit_result").asString()).isEqualTo("TIMEOUT");
        JsonNode records = get("/v1/card-stand-in-records?card_authorization_id=" + standIn.get("id").asString(), TENANT, 200);
        assertThat(records.get("data").get(0).get("stand_in_reason").asString()).isEqualTo("JIT_TIMEOUT");
        assertDecline(simulate(cardJit, "300.00", "USD", "5411", null), "JIT_TIMEOUT", "91"); // above stand-in max
    }

    // ------------------------------------------------------------------ 6. ledger down -> stand-in -> replay

    @Test
    @Order(9)
    void ledgerOutageStandInAndReplay() throws Exception {
        // clear earlier pending stand-ins first
        post("/v1/card-stand-in-records/replay", null, "replay-0", TENANT, 200);
        fakes.ledgerDown = true;
        JsonNode si = simulate(cardA, "30.00", "USD", "5411", null);
        assertThat(si.get("decision").asString()).isEqualTo("APPROVED");
        assertThat(si.get("funding_source").asString()).isEqualTo("STAND_IN");
        assertThat(si.hasNonNull("hold_id")).isFalse();
        assertThat(events(si.get("id").asString())).contains("cards.card_authorization.approved");
        assertDecline(simulate(cardA, "150.00", "USD", "5411", null), "SYSTEM_ERROR", "91");
        JsonNode failed = post("/v1/card-stand-in-records/replay", null, "replay-1", TENANT, 200);
        assertThat(failed.get("failed").asInt()).isEqualTo(1);
        fakes.ledgerDown = false;
        JsonNode replay = post("/v1/card-stand-in-records/replay", null, "replay-2", TENANT, 200);
        assertThat(replay.get("posted").asInt()).isEqualTo(1);
        JsonNode rec = get("/v1/card-stand-in-records?card_authorization_id=" + si.get("id").asString(), TENANT, 200).get("data").get(0);
        assertThat(rec.get("replayed").asBoolean()).isTrue();
        assertThat(rec.get("replay_result").asString()).isEqualTo("POSTED");
        JsonNode auth = get("/v1/card-authorizations/" + si.get("id").asString(), TENANT, 200);
        assertThat(auth.get("hold_id").asString()).isEqualTo(rec.get("hold_id").asString());
        assertThat(fakes.hold(rec.get("hold_id").asString()).get("amount").asString()).isEqualTo("30.00");
        assertThat(fakes.calls("ledger-service", "/v1/holds").getLast().key()).isEqualTo("stand-in-replay:" + rec.get("id").asString());
        assertThat(post("/v1/card-stand-in-records/replay", null, "replay-3", TENANT, 200).get("replayed").asInt()).isZero();
    }

    // ------------------------------------------------------------------ 7. clear / expire

    @Test
    @Order(10)
    void clearAndExpire() throws Exception {
        JsonNode a = simulate(cardA, "60.00", "USD", "5411", null);
        String id = a.get("id").asString();
        JsonNode cleared = post("/v1/card-authorizations/" + id + "/clear",
                "{\"amount\":\"60.00\",\"final\":true,\"clearing_record_id\":\"" + UUID.randomUUID() + "\"}", "clear-" + id, TENANT, 200);
        assertThat(cleared.get("status").asString()).isEqualTo("CLEARED");
        assertThat(new BigDecimal(cleared.get("cleared_amount").asString())).isEqualByComparingTo("60");
        assertThat(events(id)).contains("cards.card_authorization.cleared");
        assertThat(post("/v1/card-authorizations/" + id + "/clear", "{\"amount\":\"1.00\"}", "clear-again", TENANT, 409)
                .get("code").asString()).isEqualTo("invalid_transition");

        JsonNode b = simulate(cardA, "15.00", "USD", "5411", null);
        BigDecimal used = dailyUsed(cardA);
        JsonNode expired = post("/v1/card-authorizations/" + b.get("id").asString() + "/expire", "{\"reason\":\"stale\"}", "exp-1", TENANT, 200);
        assertThat(expired.get("status").asString()).isEqualTo("EXPIRED");
        assertThat(fakes.hold(b.get("hold_id").asString()).get("status").asString()).isEqualTo("RELEASED");
        assertThat(dailyUsed(cardA)).isEqualByComparingTo(used.subtract(new BigDecimal("15")));
        assertThat(post("/v1/card-authorizations/" + b.get("id").asString() + "/expire", "{}", "exp-2", TENANT, 409)
                .get("code").asString()).isEqualTo("invalid_transition");

        JsonNode c = simulate(cardA, "5.00", "USD", "5411", null);
        String asOf = java.net.URLEncoder.encode(OffsetDateTime.now().plusDays(30).toString(), StandardCharsets.UTF_8);
        JsonNode stale = post("/v1/card-authorizations/expire-stale?as_of=" + asOf, null, "stale-1", TENANT, 200);
        assertThat(stale.get("expired").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(get("/v1/card-authorizations/" + c.get("id").asString(), TENANT, 200).get("status").asString()).isEqualTo("EXPIRED");
        assertThat(events(c.get("id").asString())).contains("cards.card_authorization.expired");
    }

    // ------------------------------------------------------------------ 8. rules + clearing simulation

    @Test
    @Order(11)
    void authorizationRules() throws Exception {
        assertThat(post("/v1/card-authorization-rules", """
                {"name":"bad","condition_expression":"mcc in [","action":"DECLINE"}""", "rule-bad", TENANT, 422)
                .get("code").asString()).isEqualTo("invalid_expression");
        assertThat(post("/v1/card-authorization-rules", """
                {"name":"bad2","condition_expression":"amount > 1","action":"REVIEW","decline_reason":"OTHER"}""", "rule-bad2", TENANT, 422)
                .get("code").asString()).isEqualTo("decline_reason_not_allowed");
        JsonNode rule = post("/v1/card-authorization-rules", """
                {"card_program_id":"%s","name":"No pawn shops over 20","priority":10,
                 "condition_expression":"mcc in ['5933'] && amount > 20","action":"DECLINE"}""".formatted(programId),
                "rule-1", TENANT, 201);
        JsonNode d = simulate(cardA, "25.00", "USD", "5933", null);
        assertDecline(d, "RULE_DECLINED", "05");
        assertThat(d.get("authorization_rule_id").asString()).isEqualTo(rule.get("id").asString());
        assertThat(simulate(cardA, "15.00", "USD", "5933", null).get("decision").asString()).isEqualTo("APPROVED");
        JsonNode test = post("/v1/card-authorization-rules/test", """
                {"sample":{"mcc":"5933","amount":50},"card_program_id":"%s"}""".formatted(programId), "rule-test", TENANT, 200);
        assertThat(test.get("matched").asBoolean()).isTrue();
        assertThat(test.get("rule_id").asString()).isEqualTo(rule.get("id").asString());
        assertThat(test.get("decline_reason").asString()).isEqualTo("RULE_DECLINED");
        JsonNode adhoc = post("/v1/card-authorization-rules/test", """
                {"sample":{"hour":3},"condition_expression":"hour < 6"}""", "rule-test-2", TENANT, 200);
        assertThat(adhoc.get("matched").asBoolean()).isTrue();
        assertThat(send("DELETE", "/v1/card-authorization-rules/" + rule.get("id").asString(), null, null, TENANT).statusCode()).isEqualTo(204);
        assertThat(simulate(cardA, "25.00", "USD", "5933", null).get("decision").asString()).isEqualTo("APPROVED");
    }

    @Test
    @Order(12)
    void simulateClearingGoesThroughClearingService() throws Exception {
        JsonNode a = simulate(cardA, "33.00", "USD", "5411", null);
        JsonNode rec = post("/v1/card-authorizations/" + a.get("id").asString() + "/simulate-clearing", "{\"amount\":\"33.00\"}",
                "sim-clr-1", TENANT, 200);
        assertThat(rec.get("match_status").asString()).isEqualTo("MATCHED");
        JsonNode file = fakes.clearingFiles.getLast();
        assertThat(file.get("scheme").asString()).isEqualTo("VISA");
        JsonNode r = file.get("records").get(0);
        assertThat(r.get("auth_code").asString()).isEqualTo(a.get("auth_code").asString());
        assertThat(r.get("network_transaction_id").asString()).isEqualTo(a.get("network_transaction_id").asString());
        assertThat(r.get("billing_amount").asString()).isEqualTo("33.00");
    }

    // ------------------------------------------------------------------ 9. validation + isolation

    @Test
    @Order(13)
    void validationAndTenantIsolation() throws Exception {
        assertThat(send("POST", "/v1/card-authorizations", "{\"channel\":\"POS\",\"transaction_amount\":\"1\",\"transaction_currency\":\"USD\"}",
                "val-1", TENANT).statusCode()).isEqualTo(400);
        assertThat(post("/v1/card-authorizations/simulate", """
                {"card_id":"%s","amount":"10","currency":"USD"}""".formatted(UUID.randomUUID()), "val-2", TENANT, 404)
                .get("code").asString()).isEqualTo("not_found");
        assertThat(post("/v1/card-authorizations/simulate", """
                {"card_id":"%s","amount":"-10","currency":"USD"}""".formatted(cardA), "val-3", TENANT, 422)
                .get("code").asString()).isEqualTo("invalid_amount");
        assertThat(send("POST", "/v1/card-authorizations/simulate", "{\"card_id\":\"" + cardA + "\",\"amount\":\"1\",\"currency\":\"USD\"}",
                null, TENANT).statusCode()).isEqualTo(400);

        JsonNode a = simulate(cardA, "7.00", "USD", "5411", null);
        assertThat(send("GET", "/v1/card-authorizations/" + a.get("id").asString(), null, null, OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(get("/v1/card-authorizations?card_id=" + cardA, OTHER_TENANT, 200).get("data")).isEmpty();
        assertThat(get("/v1/card-authorizations/usage?card_id=" + cardA, OTHER_TENANT, 200).get("counters")).isEmpty();
        assertThat(get("/v1/card-authorizations?card_id=" + cardA + "&auth_code=" + a.get("auth_code").asString(), TENANT, 200)
                .get("data")).hasSize(1);
    }

    // ------------------------------------------------------------------ 10. latency

    @Test
    @Order(20)
    void thousandSequentialAuthorizationsMeetTheLatencyBudget() throws Exception {
        for (int i = 0; i < 50; i++) { // warm-up (JIT, pools, caches)
            simulate(cardPerf, "1.00", "USD", "5411", null);
        }
        // (a) application service path: the full decision + I/O + persistence, called in-process with the tenant bound
        int n = 1_000;
        long[] lat = new long[n];
        int[] internal = new int[n];
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            ObjectNode req = (ObjectNode) json.readTree(perfBody(i));
            long s = System.nanoTime();
            JsonNode r = asTenant(() -> authorizationService.process(req));
            lat[i] = System.nanoTime() - s;
            internal[i] = r.get("processing_time_ms").asInt();
            assertThat(r.get("decision").asString()).isEqualTo("APPROVED");
        }
        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        String service = stats("application-service path", lat, totalMs);
        Arrays.sort(internal);
        String decision = String.format("  decision before persistence (processing_time_ms): p50=%d ms p99=%d ms max=%d ms%n",
                internal[n / 2], internal[(int) (n * 0.99) - 1], internal[n - 1]);
        // (b) HTTP path incl. idempotency, JSON and Tomcat (reported; latency is dominated by DB round trips)
        int m = 200;
        long[] httpLat = new long[m];
        long h0 = System.nanoTime();
        for (int i = 0; i < m; i++) {
            long s = System.nanoTime();
            HttpResponse<String> r = send("POST", "/v1/card-authorizations", perfBody(n + i), "perf-" + i, TENANT);
            httpLat[i] = System.nanoTime() - s;
            assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        }
        String httpReport = stats("HTTP path", httpLat, (System.nanoTime() - h0) / 1_000_000);
        String report = service + decision + httpReport;
        System.out.print(report);
        Files.writeString(Path.of("build", "auth-latency.txt"), report);
        long approved = asTenant(() -> jdbc.sql("select count(*) from card_authorization where card_id = ? and decision = 'APPROVED'")
                .param(cardPerf).query(Long.class).single());
        assertThat(approved).isEqualTo(n + m + 50L);
        assertThat(totalMs).isLessThan(60_000);
        assertThat(lat[(int) (n * 0.99) - 1] / 1e6).isLessThan(100.0);
    }

    String perfBody(int i) {
        return """
                {"card_id":"%s","message_type":"AUTHORIZATION","channel":"POS","pos_entry_mode":"CHIP","card_present":true,
                 "transaction_amount":"%d.%02d","transaction_currency":"USD","mcc":"5411","merchant_id":"PERF-%d",
                 "merchant_country":"US","stan":"%06d","rrn":"%012d"}""".formatted(cardPerf, 1 + i % 90, i % 100, i % 7, i, i);
    }

    static String stats(String name, long[] lat, long totalMs) {
        long[] l = lat.clone();
        Arrays.sort(l);
        int n = l.length;
        return String.format("card-authorization %s (Testcontainers PostgreSQL, in-memory fakes): %d sequential "
                + "authorisations in %d ms (%.0f/s); p50=%.2f ms p95=%.2f ms p99=%.2f ms max=%.2f ms%n",
                name, n, totalMs, n * 1000.0 / Math.max(1, totalMs), l[n / 2] / 1e6, l[(int) (n * 0.95) - 1] / 1e6,
                l[(int) (n * 0.99) - 1] / 1e6, l[n - 1] / 1e6);
    }

    // ------------------------------------------------------------------ helpers

    void assertDecline(JsonNode a, String reason, String code) {
        assertThat(a.get("decision").asString()).as(a.toString()).isEqualTo("DECLINED");
        assertThat(a.get("decline_reason").asString()).as(a.toString()).isEqualTo(reason);
        assertThat(a.get("response_code").asString()).as(a.toString()).isEqualTo(code);
        assertThat(a.get("status").asString()).isEqualTo("DECLINED");
    }

    BigDecimal dailyUsed(UUID card) throws Exception {
        JsonNode u = get("/v1/card-authorizations/usage?card_id=" + card, TENANT, 200);
        for (JsonNode c : u.get("counters")) {
            if ("DAILY".equals(c.get("period").asString())) {
                return new BigDecimal(c.get("used_amount").asString());
            }
        }
        return BigDecimal.ZERO;
    }

    JsonNode simulate(UUID card, String amount, String currency, String mcc, String country) throws Exception {
        ObjectNode b = json.createObjectNode();
        b.put("card_id", card.toString());
        b.put("amount", amount);
        b.put("currency", currency);
        b.put("mcc", mcc);
        b.put("merchant_country", country == null ? "US" : country);
        return post("/v1/card-authorizations/simulate", b.toString(), "sim-" + UUID.randomUUID(), TENANT, 200);
    }

    List<String> events(String aggregateId) throws Exception {
        return asTenant(() -> jdbc.sql("select event_type from outbox where aggregate_id = ?::uuid order by created_at")
                .param(aggregateId).query(String.class).list());
    }

    <T> T asTenant(Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", body);
    }

    JsonNode post(String path, String body, String key, String tenant, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, key, tenant);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    JsonNode get(String path, String tenant, int expected) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    HttpResponse<String> send(String method, String path, String body, String key, String tenant) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenant)
                .header("X-User", "gateway");
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
