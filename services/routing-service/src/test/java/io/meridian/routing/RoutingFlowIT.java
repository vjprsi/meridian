package io.meridian.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.routing.domain.BankCodes;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * routing-service end to end over HTTP against PostgreSQL 18 (RLS enforced). ledger-service, payment-service and
 * reference-data-service are stateful fakes behind a mocked {@link ServiceClient}. Business time is a fixed clock:
 * Tuesday 2026-10-06 10:00Z.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.routing.ledger-id=0199a000-0000-7000-8000-0000000000aa",
        "meridian.routing.callback-secret=test-callback-secret",
        "meridian.routing.gl.treasury-funding=0199a000-0000-7000-8000-0000000000f1",
        "meridian.routing.test-timeout-ms=1500"})
@Import({MeridianTestContainers.class, RoutingFlowIT.ClockConfig.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RoutingFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000e001";
    static final String OTHER = "0199a000-0000-7000-8000-00000000e002";
    static final String CLEARING = "0199a000-0000-7000-8000-0000000000c1";
    static final String NOSTRO_LEDGER = "0199a000-0000-7000-8000-0000000000c2";
    static final String TREASURY = "0199a000-0000-7000-8000-0000000000f1";
    static final String CALENDAR = "0199a000-0000-7000-8000-0000000000ca";
    static final String SECRET = "test-callback-secret";
    static final String SETTLE_DATE = "2030-01-02";

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    record Res(int status, JsonNode body, HttpResponse<String> raw) {}

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakeDownstream fake;
    String fps;
    String bacs;
    String outboundE2e = "E2E-OUT-0001";
    UUID outboundPaymentId = UUID.randomUUID();
    String outboundMessageId;
    String batchId;

    @BeforeAll
    void init() {
        fake = new FakeDownstream(json);
        fake.holidays.add(LocalDate.parse("2026-10-12"));
    }

    @BeforeEach
    void stub() {
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> fake.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        when(serviceClient.get(anyString(), anyString()))
                .thenAnswer(i -> fake.get(i.getArgument(0), i.getArgument(1)));
    }

    // ------------------------------------------------------------------ 1. rails, validation, idempotency

    @Test
    @Order(1)
    void railsAreValidatedAndCreatesAreIdempotent() throws Exception {
        Res bad = post("/v1/payment-rails", """
                {"code":"BAD","name":"Bad","rail_type":"BATCH","currencies":["gbp"],"message_standard":"ISO20022",
                 "settlement_model":"DNS"}""", key(), TENANT);
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.body().get("code").asString()).isEqualTo("invalid_currency");

        String fpsBody = """
                {"code":"FPS","name":"Faster Payments","rail_type":"INSTANT","country":"GB","currencies":["GBP"],
                 "message_standard":"ISO20022","settlement_model":"DNS","is_instant":true,"max_amount":"1000000",
                 "cost_per_payment":"0.10","settlement_minutes":1,"settlement_account_id":"%s"}""".formatted(CLEARING);
        String k = key();
        Res created = post("/v1/payment-rails", fpsBody, k, TENANT);
        assertThat(created.status()).isEqualTo(201);
        fps = created.body().get("id").asString();
        assertThat(created.body().get("status").asString()).isEqualTo("ACTIVE");
        Res replay = post("/v1/payment-rails", fpsBody, k, TENANT);
        assertThat(replay.body().get("id").asString()).isEqualTo(fps);
        assertThat(replay.raw().headers().firstValue("Idempotent-Replayed")).contains("true");

        bacs = post("/v1/payment-rails", """
                {"code":"BACS","name":"Bacs","rail_type":"BATCH","country":"GB","currencies":["GBP"],
                 "message_standard":"BACS","settlement_model":"DNS","cost_per_payment":"0.02","settlement_minutes":4320,
                 "settlement_account_id":"%s","holiday_calendar_id":"%s","participant_bic":"MERIGB2L"}"""
                .formatted(CLEARING, CALENDAR), key(), TENANT).body().get("id").asString();
        Res badCutoff = post("/v1/rail-cutoffs", """
                {"payment_rail_id":"%s","cutoff_time":"25:00","timezone":"Europe/London"}""".formatted(bacs), key(), TENANT);
        assertThat(badCutoff.body().get("code").asString()).isEqualTo("invalid_cutoff_time");
        assertThat(post("/v1/rail-cutoffs", """
                {"payment_rail_id":"%s","cutoff_time":"15:30","timezone":"Europe/London","days_of_week":[]}"""
                .formatted(bacs), key(), TENANT).status()).isEqualTo(201);
        assertThat(get("/v1/payment-rails/" + bacs + "/cutoffs", TENANT).body().get("data")).hasSize(1);

        // status is read-only in PATCH (suspend / resume own it)
        Res patch = send("PATCH", "/v1/payment-rails/" + fps, "{\"status\":\"SUSPENDED\",\"name\":\"FPS\"}", TENANT,
                "If-Match", "1");
        assertThat(patch.status()).isEqualTo(200);
        assertThat(patch.body().get("status").asString()).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ 2. cut-offs and calendars

    @Test
    @Order(2)
    void nextExecutionUsesCutoffAndHolidayCalendar() throws Exception {
        JsonNode today = get("/v1/payment-rails/" + bacs + "/next-execution?payment_type=DOMESTIC&currency=GBP", TENANT).body();
        assertThat(today.get("execution_date").asString()).isEqualTo("2026-10-06");
        assertThat(today.get("after_cutoff").asBoolean()).isFalse();
        JsonNode friday = get("/v1/payment-rails/" + bacs + "/next-execution?currency=GBP&at=2026-10-09T15:00:00Z",
                TENANT).body();
        assertThat(friday.get("after_cutoff").asBoolean()).isTrue();
        assertThat(friday.get("execution_date").asString()).isEqualTo("2026-10-13"); // Monday 12th is a holiday
        assertThat(fake.calls("reference-data-service", CALENDAR)).isNotEmpty();
        JsonNode instant = get("/v1/payment-rails/" + fps + "/next-execution?at=2026-10-11T23:00:00Z", TENANT).body();
        assertThat(instant.get("immediate").asBoolean()).isTrue();
        assertThat(instant.get("execution_date").asString()).isEqualTo("2026-10-11");
    }

    // ------------------------------------------------------------------ 3. routing rules

    @Test
    @Order(3)
    void routingRulesSimulateAndRoute() throws Exception {
        Res invalid = post("/v1/routing-rules", """
                {"name":"x","conditions":{"colour":"red"},"candidate_rail_ids":["%s"]}""".formatted(fps), key(), TENANT);
        assertThat(invalid.body().get("code").asString()).isEqualTo("invalid_condition");
        Res unknown = post("/v1/routing-rules", """
                {"name":"x","conditions":{},"candidate_rail_ids":["%s"]}""".formatted(UUID.randomUUID()), key(), TENANT);
        assertThat(unknown.body().get("code").asString()).isEqualTo("unknown_rail");
        assertThat(post("/v1/routing-rules", """
                {"name":"urgent","priority":10,"conditions":{"priorities":["URGENT"]},"strategy":"FASTEST",
                 "candidate_rail_ids":["%s","%s"]}""".formatted(fps, bacs), key(), TENANT).status()).isEqualTo(201);
        assertThat(post("/v1/routing-rules", """
                {"name":"domestic","priority":20,"conditions":{"payment_types":["DOMESTIC"],"currencies":["GBP"]},
                 "strategy":"CHEAPEST","candidate_rail_ids":["%s","%s"],"fallback_rail_id":"%s"}"""
                .formatted(fps, bacs, fps), key(), TENANT).status()).isEqualTo(201);

        JsonNode normal = route("NORMAL", "GBP", "250.00");
        assertThat(normal.get("routable").asBoolean()).isTrue();
        assertThat(normal.get("rail_code").asString()).isEqualTo("BACS");
        assertThat(normal.get("execution_date").asString()).isEqualTo("2026-10-06");
        assertThat(normal.get("settlement_account_id").asString()).isEqualTo(CLEARING);
        assertThat(normal.get("strategy").asString()).isEqualTo("CHEAPEST");
        assertThat(route("URGENT", "GBP", "250.00").get("rail_code").asString()).isEqualTo("FPS");
        JsonNode none = route("NORMAL", "USD", "10");
        assertThat(none.get("routable").asBoolean()).isFalse();
        assertThat(none.get("reason_code").asString()).isEqualTo("NO_ROUTE");

        JsonNode sim = post("/v1/routing-rules/simulate", paymentJson("URGENT", "GBP", "250.00"), key(), TENANT).body();
        assertThat(sim.get("rail_code").asString()).isEqualTo("FPS");
        assertThat(sim.get("evaluations").get(0).get("candidates")).hasSize(2);
        assertThat(sim.get("execution").get("immediate").asBoolean()).isTrue();
        Res badAmount = post("/v1/routing-rules/route", paymentJson("NORMAL", "GBP", "-1"), key(), TENANT);
        assertThat(badAmount.body().get("code").asString()).isEqualTo("invalid_amount");
    }

    // ------------------------------------------------------------------ 4. rail status

    @Test
    @Order(4)
    void suspendResumeAndMetrics() throws Exception {
        Res s = post("/v1/payment-rails/" + bacs + "/suspend", "{\"reason\":\"scheme outage\"}", key(), TENANT);
        assertThat(s.body().get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(s.body().get("status_reason").asString()).isEqualTo("scheme outage");
        assertThat(post("/v1/payment-rails/" + bacs + "/suspend", "{\"reason\":\"again\"}", key(), TENANT).status())
                .isEqualTo(409);
        assertThat(route("NORMAL", "GBP", "250.00").get("rail_code").asString()).isEqualTo("FPS");
        Res pacsOnSuspended = post("/v1/rail-messages/pacs008", pacsBody(bacs, UUID.randomUUID(), "E2E-X", "GBP"), key(),
                TENANT);
        assertThat(pacsOnSuspended.body().get("code").asString()).isEqualTo("rail_unavailable");
        assertThat(post("/v1/payment-rails/" + bacs + "/resume", "{}", key(), TENANT).body().get("status").asString())
                .isEqualTo("ACTIVE");
        assertThat(post("/v1/payment-rails/" + bacs + "/resume", "{}", key(), TENANT).status()).isEqualTo(409);

        JsonNode degraded = post("/v1/payment-rails/" + fps + "/metrics",
                "{\"success_rate_7d\":\"0.90\",\"p95_latency_ms\":2500}", key(), TENANT).body();
        assertThat(degraded.get("status").asString()).isEqualTo("DEGRADED");
        JsonNode recovered = post("/v1/payment-rails/" + fps + "/metrics",
                "{\"success_rate_7d\":\"0.999\",\"p95_latency_ms\":1800}", key(), TENANT).body();
        assertThat(recovered.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(recovered.get("p95_latency_ms").asInt()).isEqualTo(1800);
        assertThat(events("payments.payment_rail.status_changed")).isGreaterThanOrEqualTo(4);
    }

    // ------------------------------------------------------------------ 5. outbound pacs.008

    @Test
    @Order(5)
    void pacs008IsBuiltStoredAndBatched() throws Exception {
        Res unsupported = post("/v1/rail-messages/pacs008", pacsBody(bacs, UUID.randomUUID(), "E2E-Y", "EUR"), key(), TENANT);
        assertThat(unsupported.body().get("code").asString()).isEqualTo("currency_not_supported");
        Res msg = post("/v1/rail-messages/pacs008", pacsBody(bacs, outboundPaymentId, outboundE2e, "GBP"), key(), TENANT);
        assertThat(msg.status()).isEqualTo(200);
        outboundMessageId = msg.body().get("id").asString();
        assertThat(msg.body().get("status").asString()).isEqualTo("SENT");
        assertThat(msg.body().get("direction").asString()).isEqualTo("OUTBOUND");
        assertThat(msg.body().get("message_type").asString()).isEqualTo("pacs.008.001.08");
        assertThat(msg.body().has("payload")).isFalse();
        batchId = msg.body().get("rail_settlement_batch_id").asString();
        // same payment again (new key) -> the stored message
        assertThat(post("/v1/rail-messages/pacs008", pacsBody(bacs, outboundPaymentId, outboundE2e, "GBP"), key(), TENANT)
                .body().get("id").asString()).isEqualTo(outboundMessageId);

        Res payload = get("/v1/rail-messages/" + outboundMessageId + "/payload", TENANT);
        assertThat(payload.raw().headers().firstValue("Content-Type").orElse("")).startsWith("application/xml");
        assertThat(payload.raw().body()).contains("<EndToEndId>" + outboundE2e + "</EndToEndId>")
                .contains("<IBAN>GB82WEST12345698765432</IBAN>").contains("Smith &amp; Co");

        JsonNode batch = get("/v1/rail-settlement-batches/" + batchId, TENANT).body();
        assertThat(batch.get("status").asString()).isEqualTo("OPEN");
        assertThat(batch.get("debit_count").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(batch.get("debit_total").asString())).isEqualByComparingTo("250");
        assertThat(batch.get("cycle_id").asString()).isEqualTo("BACS-20300102-GBP");
        assertThat(events("payments.rail_message.sent")).isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------ 6. public inbound callback

    @Test
    @Order(6)
    void inboundStatusReportIsVerifiedStoredAndForwarded() throws Exception {
        fake.paymentsByE2e.put(outboundE2e, outboundPaymentId);
        String pacs002 = pacs002("STS-0001", outboundE2e, "ACSC");
        Res ok = inbound(bacs, "pacs.002.001.10", pacs002, BankCodes.sign(SECRET, pacs002));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("status").asString()).isEqualTo("PARSED");
        assertThat(ok.body().get("direction").asString()).isEqualTo("INBOUND");
        assertThat(ok.body().get("payment_id").asString()).isEqualTo(outboundPaymentId.toString());
        assertThat(ok.body().get("signature_valid").asBoolean()).isTrue();
        List<FakeDownstream.Call> reports = fake.calls("payment-service", "/status-report");
        assertThat(reports).hasSize(1);
        assertThat(reports.getFirst().path()).isEqualTo("/v1/payments/" + outboundPaymentId + "/status-report");
        assertThat(reports.getFirst().body().get("status").asString()).isEqualTo("ACSC");
        assertThat(reports.getFirst().key()).startsWith("rmsg:");

        // redelivery of the same MsgId: stored message returned, nothing forwarded twice
        Res again = inbound(bacs, "pacs.002.001.10", pacs002, BankCodes.sign(SECRET, pacs002));
        assertThat(again.body().get("id").asString()).isEqualTo(ok.body().get("id").asString());
        assertThat(fake.calls("payment-service", "/status-report")).hasSize(1);

        // bad signature: 401, stored as FAILED for audit, nothing forwarded
        String forged = pacs002("STS-FORGED", outboundE2e, "RJCT");
        Res bad = inbound(bacs, "pacs.002.001.10", forged, BankCodes.sign("wrong-secret", forged));
        assertThat(bad.status()).isEqualTo(401);
        assertThat(bad.body().get("code").asString()).isEqualTo("invalid_signature");
        assertThat(inbound(bacs, "pacs.002.001.10", forged, null).status()).isEqualTo(401);
        assertThat(fake.calls("payment-service", "/status-report")).hasSize(1);
        JsonNode failed = get("/v1/rail-messages?payment_rail_id=" + bacs + "&status=FAILED", TENANT).body().get("data");
        assertThat(failed).isNotEmpty();
        assertThat(failed.get(0).get("signature_valid").asBoolean()).isFalse();

        assertThat(inbound(UUID.randomUUID().toString(), "pacs.002.001.10", pacs002, BankCodes.sign(SECRET, pacs002))
                .status()).isEqualTo(404);
        String garbage = "<not-iso/>";
        assertThat(inbound(bacs, "pacs.002.001.10", garbage, BankCodes.sign(SECRET, garbage)).status()).isEqualTo(422);
        assertThat(events("payments.rail_message.received")).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(99)
    void inboundV2SignaturesAreFreshSingleUseAndBoundToTheRail() throws Exception {
        fake.paymentsByE2e.put(outboundE2e, outboundPaymentId);
        String pacs002 = pacs002("STS-V2-0001", outboundE2e, "ACSC");
        String ts = String.valueOf(java.time.Instant.now().getEpochSecond());
        String nonce = "evt-" + UUID.randomUUID();
        String sig = v2(ts, nonce, bacs, pacs002);
        Res ok = inbound(bacs, "pacs.002.001.10", pacs002, sig, ts, nonce);
        assertThat(ok.status()).as(String.valueOf(ok.body())).isEqualTo(200);
        Res replay = inbound(bacs, "pacs.002.001.10", pacs002, sig, ts, nonce);
        assertThat(replay.status()).isEqualTo(409);
        assertThat(replay.body().get("code").asString()).isEqualTo("callback_replayed");

        String other = UUID.randomUUID().toString();
        String n2 = "evt-" + UUID.randomUUID();
        assertThat(inbound(bacs, "pacs.002.001.10", pacs002, v2(ts, n2, other, pacs002), ts, n2).body().get("code")
                .asString()).as("signed for another rail").isEqualTo("invalid_signature");
        String old = String.valueOf(java.time.Instant.now().minusSeconds(3600).getEpochSecond());
        String n3 = "evt-" + UUID.randomUUID();
        assertThat(inbound(bacs, "pacs.002.001.10", pacs002, v2(old, n3, bacs, pacs002), old, n3).body().get("code")
                .asString()).isEqualTo("stale_callback");
    }

    private static String v2(String ts, String nonce, String rail, String payload) {
        return BankCodes.sign(SECRET, new String(io.meridian.web.callback.CallbackVerifier.v2Payload(ts, nonce, rail,
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @Order(7)
    void inboundCreditRecallReturnAndReplay() throws Exception {
        String pacs008 = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"><FIToFICstmrCdtTrf>
                 <GrpHdr><MsgId>IN-0001</MsgId><NbOfTxs>1</NbOfTxs></GrpHdr>
                 <CdtTrfTxInf><PmtId><EndToEndId>E2E-IN-1</EndToEndId><TxId>TX-IN-1</TxId></PmtId>
                  <IntrBkSttlmAmt Ccy="GBP">100.00</IntrBkSttlmAmt><IntrBkSttlmDt>%s</IntrBkSttlmDt>
                  <Dbtr><Nm>Jane Payer</Nm></Dbtr><DbtrAcct><Id><IBAN>GB33BUKB20201555555555</IBAN></Id></DbtrAcct>
                  <Cdtr><Nm>Our Customer</Nm></Cdtr><CdtrAcct><Id><IBAN>GB82WEST12345698765432</IBAN></Id></CdtrAcct>
                  <RmtInf><Ustrd>Rent</Ustrd></RmtInf></CdtTrfTxInf>
                </FIToFICstmrCdtTrf></Document>""".formatted(SETTLE_DATE);
        Res credit = inbound(bacs, "pacs.008.001.08", pacs008, BankCodes.sign(SECRET, pacs008));
        assertThat(credit.body().get("status").asString()).isEqualTo("PARSED");
        FakeDownstream.Call inboundCall = fake.calls("payment-service", "/v1/payments/inbound").getFirst();
        assertThat(inboundCall.body().get("creditor_account_number").asString()).isEqualTo("GB82WEST12345698765432");
        assertThat(inboundCall.body().get("amount").asString()).isEqualTo("100.00");
        assertThat(inboundCall.body().get("rail_code").asString()).isEqualTo("BACS");
        JsonNode batch = get("/v1/rail-settlement-batches/" + batchId, TENANT).body();
        assertThat(batch.get("credit_count").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(batch.get("net_position").asString())).isEqualByComparingTo("-150");

        // status report for an unknown payment: stored FAILED, then replayed once the payment is known
        String orphan = pacs002("STS-0002", "E2E-LATE", "ACCP");
        Res failed = inbound(bacs, "pacs.002.001.10", orphan, BankCodes.sign(SECRET, orphan));
        assertThat(failed.body().get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.body().get("error").asString()).contains("unknown_payment");
        UUID late = UUID.randomUUID();
        fake.paymentsByE2e.put("E2E-LATE", late);
        String failedId = failed.body().get("id").asString();
        Res replay = post("/v1/rail-messages/" + failedId + "/replay", "{\"reason\":\"payment registered\"}", key(), TENANT);
        assertThat(replay.body().get("status").asString()).isEqualTo("PARSED");
        assertThat(replay.body().get("original_message_id").asString()).isEqualTo(failedId);
        JsonNode replayed = get("/v1/rail-messages/" + replay.body().get("replayed_message_id").asString(), TENANT).body();
        assertThat(replayed.get("replay_of_id").asString()).isEqualTo(failedId);
        assertThat(fake.calls("payment-service", "/v1/payments/" + late + "/status-report")).hasSize(1);
        assertThat(post("/v1/rail-messages/" + outboundMessageId + "/replay", "{\"reason\":\"x\"}", key(), TENANT)
                .body().get("code").asString()).isEqualTo("not_replayable");

        fake.paymentsByE2e.put("E2E-IN-1", UUID.randomUUID());
        String camt = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.056.001.08"><FIToFIPmtCxlReq>
                 <Assgnmt><Id>RCL-0001</Id></Assgnmt>
                 <Undrlyg><TxInf><OrgnlEndToEndId>E2E-IN-1</OrgnlEndToEndId>
                  <CxlRsnInf><Orgtr><Nm>Payer Bank</Nm></Orgtr><Rsn><Cd>DUPL</Cd></Rsn></CxlRsnInf></TxInf></Undrlyg>
                </FIToFIPmtCxlReq></Document>""";
        assertThat(inbound(bacs, "camt.056.001.08", camt, BankCodes.sign(SECRET, camt)).body().get("status").asString())
                .isEqualTo("PARSED");
        FakeDownstream.Call recall = fake.calls("payment-service", "/v1/payment-recalls/inbound").getFirst();
        assertThat(recall.body().get("reason_code").asString()).isEqualTo("DUPL");
        assertThat(recall.body().get("requested_by").asString()).isEqualTo("Payer Bank");

        String ret = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.004.001.09"><PmtRtr>
                 <GrpHdr><MsgId>RTR-0001</MsgId></GrpHdr>
                 <TxInf><RtrId>RTN-1</RtrId><OrgnlEndToEndId>%s</OrgnlEndToEndId>
                  <RtrdIntrBkSttlmAmt Ccy="GBP">250.00</RtrdIntrBkSttlmAmt><RtrRsnInf><Rsn><Cd>AC04</Cd></Rsn></RtrRsnInf></TxInf>
                </PmtRtr></Document>""".formatted(outboundE2e);
        Res returned = inbound(bacs, "pacs.004.001.09", ret, BankCodes.sign(SECRET, ret));
        assertThat(returned.body().get("status").asString()).isEqualTo("PARSED");
        assertThat(fake.calls("payment-service", "/v1/payment-returns/inbound").getFirst().body().get("reason_code")
                .asString()).isEqualTo("AC04");
        Res mismatch = inbound(bacs, "pacs.002.001.10", ret, BankCodes.sign(SECRET, ret));
        assertThat(mismatch.body().get("code").asString()).isEqualTo("message_type_mismatch");
    }

    // ------------------------------------------------------------------ 7. settlement

    @Test
    @Order(8)
    void settlementBatchCloseAndSettlePostsNetPosition() throws Exception {
        assertThat(post("/v1/rail-settlement-batches/" + batchId + "/settle", null, key(), TENANT).status()).isEqualTo(409);
        JsonNode closed = post("/v1/rail-settlement-batches/" + batchId + "/close", null, key(), TENANT).body();
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        String fileId = closed.get("settlement_file_id").asString();
        assertThat(get("/v1/rail-messages/" + fileId + "/payload", TENANT).raw().body()).contains("<NetPos>-150.00</NetPos>");
        assertThat(post("/v1/rail-settlement-batches/" + batchId + "/close", null, key(), TENANT).status()).isEqualTo(409);
        assertThat(get("/v1/rail-settlement-batches/" + batchId + "/messages", TENANT).body().get("data").size())
                .isGreaterThanOrEqualTo(3);

        Res noNostro = post("/v1/rail-settlement-batches/" + batchId + "/settle", null, key(), TENANT);
        assertThat(noNostro.body().get("code").asString()).isEqualTo("nostro_not_found");
        assertThat(post("/v1/nostro-accounts", """
                {"name":"BoE settlement","account_type":"CENTRAL_BANK","institution_name":"Bank of England",
                 "account_number":"BOE-001","currency":"GBP","ledger_account_id":"%s","payment_rail_id":"%s",
                 "min_balance":"5000"}""".formatted(NOSTRO_LEDGER, bacs), key(), TENANT).status()).isEqualTo(201);
        String k = key();
        JsonNode settled = post("/v1/rail-settlement-batches/" + batchId + "/settle", null, k, TENANT).body();
        assertThat(settled.get("status").asString()).isEqualTo("SETTLED");
        assertThat(settled.get("journal_entry_id").isNull()).isFalse();
        JsonNode je = fake.journalsByKey.get("rsb-settle:" + batchId);
        assertThat(je.get("entries").get(0).get("ledger_account_id").asString()).isEqualTo(CLEARING);
        assertThat(je.get("entries").get(0).get("direction").asString()).isEqualTo("DEBIT");
        assertThat(je.get("entries").get(1).get("ledger_account_id").asString()).isEqualTo(NOSTRO_LEDGER);
        assertThat(je.get("entries").get(1).get("amount").asString()).isEqualTo("150.00000000");
        // idempotent replay: same response, one posting
        assertThat(post("/v1/rail-settlement-batches/" + batchId + "/settle", null, k, TENANT).body().get("status").asString())
                .isEqualTo("SETTLED");
        assertThat(fake.calls("ledger-service", "/v1/journal-entries")).hasSize(1);
        assertThat(events("payments.rail_settlement_batch.settled")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 8. liquidity

    @Test
    @Order(9)
    void liquidityPositionBreachAndTopUp() throws Exception {
        String nostro = get("/v1/nostro-accounts?payment_rail_id=" + bacs, TENANT).body().get("data").get(0).get("id").asString();
        fake.balances.put(UUID.fromString(NOSTRO_LEDGER), new BigDecimal("1000"));
        JsonNode pos = post("/v1/nostro-accounts/" + nostro + "/refresh-position", null, key(), TENANT).body();
        assertThat(pos.get("breach").asBoolean()).isTrue();
        assertThat(new BigDecimal(pos.get("current_balance").asString())).isEqualByComparingTo("1000");
        assertThat(events("payments.rail_liquidity_position.breached")).isEqualTo(1);

        assertThat(post("/v1/nostro-accounts/" + nostro + "/top-up", "{\"amount\":\"0\"}", key(), TENANT).status())
                .isEqualTo(422);
        JsonNode after = post("/v1/nostro-accounts/" + nostro + "/top-up", "{\"amount\":\"5000\",\"reason\":\"morning funding\"}",
                key(), TENANT).body();
        assertThat(after.get("breach").asBoolean()).isFalse();
        assertThat(new BigDecimal(after.get("available_liquidity").asString())).isEqualByComparingTo("6000");
        FakeDownstream.Call topUp = fake.calls("ledger-service", "/v1/journal-entries").getLast();
        assertThat(topUp.body().get("entries").get(0).get("ledger_account_id").asString()).isEqualTo(NOSTRO_LEDGER);
        assertThat(topUp.body().get("entries").get(1).get("ledger_account_id").asString()).isEqualTo(TREASURY);
        assertThat(get("/v1/nostro-accounts/" + nostro + "/positions", TENANT).body().get("data")).hasSize(2);
        assertThat(events("payments.nostro_account.topped_up")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 9. adapters

    @Test
    @Order(10)
    void adapterConnectionTestAndCredentialRotation() throws Exception {
        String adapter = post("/v1/rail-adapters", """
                {"payment_rail_id":"%s","name":"BACS via sponsor","connection_type":"SPONSOR_BANK",
                 "endpoint_url":"http://localhost:%d/actuator/health"}""".formatted(bacs, port), key(), TENANT)
                .body().get("id").asString();
        JsonNode test = post("/v1/rail-adapters/" + adapter + "/test-connection", null, key(), TENANT).body();
        assertThat(test.get("reachable").asBoolean()).isTrue();
        assertThat(test.get("http_status").asInt()).isEqualTo(200);
        assertThat(get("/v1/rail-adapters/" + adapter, TENANT).body().get("last_heartbeat_at").isNull()).isFalse();
        String dead = post("/v1/rail-adapters", """
                {"payment_rail_id":"%s","name":"dead","connection_type":"API","endpoint_url":"http://127.0.0.1:1/echo"}"""
                .formatted(bacs), key(), TENANT).body().get("id").asString();
        assertThat(post("/v1/rail-adapters/" + dead + "/test-connection", null, key(), TENANT).body().get("reachable")
                .asBoolean()).isFalse();

        assertThat(post("/v1/rail-adapters/" + adapter + "/rotate-credentials", "{\"credentials_ref\":\"hunter2\"}", key(),
                TENANT).body().get("code").asString()).isEqualTo("invalid_credentials_ref");
        JsonNode rotated = post("/v1/rail-adapters/" + adapter + "/rotate-credentials",
                "{\"credentials_ref\":\"vault:secret/rails/bacs/v2\",\"reason\":\"annual\"}", key(), TENANT).body();
        assertThat(rotated.get("credentials_version").asInt()).isEqualTo(2);
        assertThat(rotated.has("credentials_ref")).isFalse();
        assertThat(rotated.has("previous_credentials_ref")).isFalse();
        assertThat(post("/v1/rail-adapters/" + adapter + "/rotate-credentials",
                "{\"credentials_ref\":\"vault:secret/rails/bacs/v2\"}", key(), TENANT).body().get("code").asString())
                .isEqualTo("credentials_unchanged");
        assertThat(events("payments.rail_adapter.credentials_rotated")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 10. directory

    @Test
    @Order(11)
    void participantLookupByBicIbanAndClearingId() throws Exception {
        assertThat(post("/v1/rail-participants", """
                {"bic":"BAD","name":"x","country":"GB"}""", key(), TENANT).body().get("code").asString())
                .isEqualTo("invalid_bic");
        assertThat(post("/v1/rail-participants", """
                {"bic":"WESTGB2LXXX","clearing_system":"GBDSC","clearing_id":"123456","name":"West Bank plc",
                 "country":"GB","reachable_rails":["FPS","BACS"],"source":"SWIFT_BIC_DIRECTORY"}""", key(), TENANT)
                .status()).isEqualTo(201);
        JsonNode byIban = post("/v1/rail-participants/lookup", "{\"iban\":\"GB82 WEST 1234 5698 7654 32\"}", key(), TENANT).body();
        assertThat(byIban.get("found").asBoolean()).isTrue();
        assertThat(byIban.get("bic").asString()).isEqualTo("WESTGB2LXXX");
        assertThat(byIban.get("bank_code").asString()).isEqualTo("123456");
        assertThat(byIban.get("reachable_rails")).hasSize(2);
        assertThat(post("/v1/rail-participants/lookup", "{\"bic\":\"WESTGB2L\"}", key(), TENANT).body().get("name")
                .asString()).isEqualTo("West Bank plc");
        assertThat(post("/v1/rail-participants/lookup", "{\"clearing_system\":\"GBDSC\",\"clearing_id\":\"123456\"}", key(),
                TENANT).body().get("found").asBoolean()).isTrue();
        assertThat(post("/v1/rail-participants/lookup", "{\"iban\":\"GB82WEST12345698765433\"}", key(), TENANT).body()
                .get("code").asString()).isEqualTo("invalid_iban_checksum");
        assertThat(post("/v1/rail-participants/lookup", "{}", key(), TENANT).status()).isEqualTo(422);
    }

    // ------------------------------------------------------------------ 11. tenant isolation

    @Test
    @Order(12)
    void tenantsAreIsolated() throws Exception {
        assertThat(get("/v1/payment-rails/" + bacs, OTHER).status()).isEqualTo(404);
        assertThat(get("/v1/payment-rails", OTHER).body().get("data")).isEmpty();
        assertThat(get("/v1/rail-messages", OTHER).body().get("data")).isEmpty();
        assertThat(post("/v1/payment-rails/" + bacs + "/suspend", "{\"reason\":\"x\"}", key(), OTHER).status()).isEqualTo(404);
        assertThat(get("/v1/payment-rails", null).status()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode route(String priority, String currency, String amount) throws Exception {
        Res r = post("/v1/routing-rules/route", paymentJson(priority, currency, amount), key(), TENANT);
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    private static String paymentJson(String priority, String currency, String amount) {
        return """
                {"payment":{"payment_type":"DOMESTIC","direction":"OUTBOUND","amount":"%s","currency":"%s",
                 "creditor_country":"GB","priority":"%s"}}""".formatted(amount, currency, priority);
    }

    private static String pacsBody(String rail, UUID payment, String e2e, String currency) {
        return """
                {"payment_rail_id":"%s","payment":{"id":"%s","end_to_end_id":"%s","instruction_id":"INS-1",
                 "transaction_id":"TX-1","uetr":"%s","amount":"250.00","currency":"%s","value_date":"%s",
                 "charge_bearer":"SLEV","debtor_name":"Our Customer","debtor_account_number":"GB29NWBK60161331926819",
                 "debtor_account_id_type":"IBAN","creditor_name":"Smith & Co","creditor_account_number":"GB82WEST12345698765432",
                 "creditor_account_id_type":"IBAN","creditor_agent_bic":"WESTGB2L","remittance_unstructured":"INV-9"}}"""
                .formatted(rail, payment, e2e, UUID.randomUUID(), currency, SETTLE_DATE);
    }

    private static String pacs002(String msgId, String e2e, String status) {
        return """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10"><FIToFIPmtStsRpt>
                 <GrpHdr><MsgId>%s</MsgId></GrpHdr>
                 <TxInfAndSts><OrgnlEndToEndId>%s</OrgnlEndToEndId><TxSts>%s</TxSts><ClrSysRef>CLR-1</ClrSysRef></TxInfAndSts>
                </FIToFIPmtStsRpt></Document>""".formatted(msgId, e2e, status);
    }

    private Res inbound(String rail, String type, String payload, String signature) throws Exception {
        return inbound(rail, type, payload, signature, null, null);
    }

    private Res inbound(String rail, String type, String payload, String signature, String ts, String nonce)
            throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("message_type", type, "payload", payload));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/payment-rails/" + rail
                        + "/inbound")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            b.header("X-Rail-Signature", signature);
        }
        if (ts != null) {
            b.header("X-Callback-Timestamp", ts).header("X-Callback-Nonce", nonce);
        }
        return res(http.send(b.build(), HttpResponse.BodyHandlers.ofString()));
    }

    private long events(String type) {
        return jdbc.sql("select count(*) from outbox where event_type = ? and tenant_id = ?::uuid")
                .params(type, TENANT).query(Long.class).single();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private Res get(String path, String tenant) throws Exception {
        return send("GET", path, null, tenant, null, null);
    }

    private Res post(String path, String body, String idemKey, String tenant) throws Exception {
        return send("POST", path, body, tenant, "Idempotency-Key", idemKey);
    }

    private Res send(String method, String path, String body, String tenant, String header, String value) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (tenant != null) {
            b.header("X-Tenant-Id", tenant);
        }
        if (header != null) {
            b.header(header, value);
        }
        return res(http.send(b.build(), HttpResponse.BodyHandlers.ofString()));
    }

    private Res res(HttpResponse<String> r) {
        JsonNode body = null;
        String ct = r.headers().firstValue("Content-Type").orElse("");
        if (r.body() != null && !r.body().isBlank() && ct.contains("json")) {
            body = json.readTree(r.body());
        }
        return new Res(r.statusCode(), body, r);
    }
}
