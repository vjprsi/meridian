package io.meridian.cardclearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * card-clearing-service end to end over HTTP against PostgreSQL 18 (RLS enforced) with card-authorization-service,
 * card-service and ledger-service stubbed behind a mocked {@link ServiceClient} ({@link FakeNetwork}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.cards.ledger-id=0199a000-0000-7000-8000-0000000000aa",
        "meridian.cards.clearing.settlement-accounts.VISA=0199a000-0000-7000-8000-00000000c501",
        "meridian.cards.clearing.fee-income-account=0199a000-0000-7000-8000-00000000c510",
        "meridian.cards.clearing.interchange-income-account=0199a000-0000-7000-8000-00000000c511",
        "meridian.cards.clearing.nostro-account=0199a000-0000-7000-8000-00000000c512",
        "meridian.cards.clearing.tax-account=0199a000-0000-7000-8000-00000000c513",
        "meridian.cards.clearing.atm-fee=2.50", "meridian.cards.clearing.match-tolerance-pct=0.20"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CardClearingFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000e101";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-00000000e102";
    static final UUID SETTLE = UUID.fromString("0199a000-0000-7000-8000-00000000c501");
    static final UUID FEE_INCOME = UUID.fromString("0199a000-0000-7000-8000-00000000c510");
    static final UUID IC_INCOME = UUID.fromString("0199a000-0000-7000-8000-00000000c511");
    static final UUID NOSTRO = UUID.fromString("0199a000-0000-7000-8000-00000000c512");
    static final UUID TAX = UUID.fromString("0199a000-0000-7000-8000-00000000c513");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakeNetwork net;
    final UUID program = UUID.randomUUID();
    final UUID cardA = UUID.randomUUID();
    final UUID cardB = UUID.randomUUID();
    final UUID accountA = UUID.randomUUID();
    final UUID accountB = UUID.randomUUID();
    ObjectNode a1;
    ObjectNode a2;
    ObjectNode a3;
    ObjectNode a4;
    ObjectNode a5;
    ObjectNode a6;
    ObjectNode a7;
    ObjectNode a8;
    ObjectNode a10;
    ObjectNode a11;
    JsonNode file1;
    String settlementId;
    String feeId;

    /** @MockitoBean resets stubs after every test method; the fakes keep their state across the ordered tests. */
    @BeforeEach
    void stubs() {
        when(serviceClient.get(anyString(), anyString()))
                .thenAnswer(i -> net.get(i.getArgument(0), i.getArgument(1)));
        when(serviceClient.post(anyString(), anyString(), any(), nullable(String.class)))
                .thenAnswer(i -> net.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
    }

    @BeforeAll
    void setUp() {
        net = new FakeNetwork(json);
        net.card(cardA, accountA, program);
        net.card(cardB, accountB, program);
        a1 = net.auth(cardA, program, accountA, "100.00", "USD", "NT1", "A00001", null, "APPROVED");
        a2 = net.auth(cardA, program, accountA, "50.00", "USD", null, "AC2", null, "APPROVED");
        a3 = net.auth(cardA, program, accountA, "40.00", "USD", null, null, "RRN3", "APPROVED");
        a4 = net.auth(cardA, program, accountA, "100.00", "USD", "NT4", null, null, "APPROVED");
        a5 = net.auth(cardA, program, accountA, "100.00", "USD", "NT5", null, null, "APPROVED");
        a6 = net.auth(cardA, program, accountA, "80.00", "USD", "NT6", null, null, "APPROVED");
        a7 = net.auth(cardA, program, accountA, "70.00", "USD", "NT7", null, null, "EXPIRED");
        a8 = net.auth(cardA, program, accountA, "30.00", "USD", "NT8", null, null, "APPROVED");
        a10 = net.auth(cardA, program, accountA, "200.00", "USD", "NT10", null, null, "APPROVED");
        a11 = net.auth(cardA, program, accountA, "100.00", "USD", "NT11", null, null, "APPROVED");
        a11.put("fx_markup", "2.00");
        a11.put("conversion_rate", "1.0870");
    }

    // ------------------------------------------------------------------ http helpers

    HttpResponse<String> send(String method, String path, Object body, String key, String user, String tenant) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("X-Tenant-Id", tenant).header("X-User", user)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    JsonNode ok(HttpResponse<String> r, int status) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return json.readTree(r.body());
    }

    JsonNode post(String path, Object body, String user) {
        return ok(send("POST", path, body, UUID.randomUUID().toString(), user, TENANT), 200);
    }

    JsonNode get(String path) {
        return ok(send("GET", path, null, null, "ops", TENANT), 200);
    }

    String code(HttpResponse<String> r) {
        return json.readTree(r.body()).path("code").asString();
    }

    ObjectNode fileBody(String name, String date) {
        ObjectNode b = json.createObjectNode();
        b.put("scheme", "VISA");
        b.put("file_name", name);
        b.put("processing_date", date);
        b.put("card_program_id", program.toString());
        return b;
    }

    List<JsonNode> records(String fileId) {
        List<JsonNode> out = new ArrayList<>();
        get("/v1/card-clearing/files/" + fileId + "/records?limit=200").get("data").forEach(out::add);
        return out;
    }

    JsonNode byNtid(List<JsonNode> recs, String ntid) {
        return recs.stream().filter(r -> ntid.equals(r.path("network_transaction_id").asString(null)))
                .findFirst().orElseThrow();
    }

    List<String> events(String type) {
        return asTenant(() -> jdbc.sql("select payload::text from outbox where event_type = ?").param(type)
                .query(String.class).list());
    }

    long count(String sql) {
        return asTenant(() -> jdbc.sql(sql).query(Long.class).single());
    }

    <T> T asTenant(java.util.concurrent.Callable<T> body) {
        try {
            return io.meridian.common.tenancy.TenantContext.callAs(UUID.fromString(TENANT), "test", body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Order(1)
    void csvFileIsParsedMatchedAndPosted() {
        String csv = String.join("\n",
                "record_type,card_id,network_transaction_id,auth_code,rrn,arn,merchant_name,mcc,merchant_country,"
                        + "transaction_date,transaction_amount,transaction_currency,billing_amount,billing_currency",
                "PRESENTMENT,,NT1,,,74000000000000000000001,\"Coffee, \"\"Best\"\" Inc\",5814,US,2026-10-01,100.00,USD,,",
                "PRESENTMENT," + cardA + ",,AC2,,,Shop2,5411,US,2026-10-01,50.00,USD,,",
                "PRESENTMENT," + cardA + ",,,RRN3,,Shop3,5411,US,2026-10-01,40.00,USD,,",
                "PRESENTMENT,,NT4,,,,Hotel,7011,US,2026-10-01,130.00,USD,,",
                "PRESENTMENT,,NT5,,,,Restaurant,5812,US,2026-10-01,110.00,USD,,",
                "PRESENTMENT,,NT6,,,,Fuel,5542,US,2026-10-01,60.00,USD,,",
                "PRESENTMENT,,NT7,,,,Late,5999,US,2026-10-01,70.00,USD,,",
                "PRESENTMENT,,NTX,,,,Unknown,5999,US,2026-10-01,30.00,USD,,",
                "REFUND," + cardA + ",NTR,,,,Shop2,5411,US,2026-10-01,15.00,USD,,",
                "PRESENTMENT," + cardB + ",NT9,,,,NoAuth,5999,US,2026-10-01,25.00,USD,,",
                "PRESENTMENT,,NT10,,,,ATM,6011,US,2026-10-01,200.00,USD,,",
                "PRESENTMENT,,NT11,,,,Paris,5999,FR,2026-10-01,92.00,EUR,100.00,USD") + "\n";
        ObjectNode body = fileBody("visa-2026-10-01.csv", "2026-10-02");
        body.put("file_format", "CSV");
        body.put("content", csv);
        String key = "file-1";
        HttpResponse<String> r = send("POST", "/v1/card-clearing/files", body, key, "ops", TENANT);
        file1 = ok(r, 201);
        assertThat(file1.get("status").asString()).isEqualTo("POSTED");
        assertThat(file1.get("record_count").asInt()).isEqualTo(12);
        assertThat(file1.get("matched_count").asInt()).isEqualTo(10);
        assertThat(file1.get("unmatched_count").asInt()).isEqualTo(1);
        assertThat(file1.get("exception_count").asInt()).isEqualTo(1);
        assertThat(file1.get("checksum").asString()).hasSize(64);
        assertThat(file1.get("file_uri").asString()).startsWith("inline://clearing/");
        assertThat(new BigDecimal(file1.get("total_amount").asString())).isEqualByComparingTo("930.00");
        assertThat(file1.has("content")).isFalse();

        // idempotent replay returns the original response without reprocessing
        HttpResponse<String> replay = send("POST", "/v1/card-clearing/files", body, key, "ops", TENANT);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(file1.get("id").asString());
        // same content under a new key is a duplicate file
        HttpResponse<String> dup = send("POST", "/v1/card-clearing/files", body, "file-1-again", "ops", TENANT);
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(code(dup)).isEqualTo("duplicate_file");

        List<JsonNode> recs = records(file1.get("id").asString());
        assertThat(recs).hasSize(12);
        JsonNode r1 = byNtid(recs, "NT1");
        assertThat(r1.get("match_status").asString()).isEqualTo("MATCHED");
        assertThat(r1.get("match_rule").asString()).isEqualTo("NETWORK_TRANSACTION_ID");
        assertThat(r1.get("card_authorization_id").asString()).isEqualTo(a1.get("id").asString());
        assertThat(r1.get("hold_id").asString()).isEqualTo(a1.get("hold_id").asString());
        assertThat(r1.get("merchant_name").asString()).isEqualTo("Coffee, \"Best\" Inc");
        assertThat(new BigDecimal(r1.get("interchange_amount").asString())).isEqualByComparingTo("1.15");
        assertThat(r1.hasNonNull("journal_entry_id")).isTrue();
        assertThat(r1.get("card_id").asString()).isEqualTo(cardA.toString());

        JsonNode r2 = recs.stream().filter(x -> "AC2".equals(x.path("auth_code").asString(null))).findFirst().orElseThrow();
        assertThat(r2.get("match_rule").asString()).isEqualTo("AUTH_CODE");
        assertThat(r2.get("card_authorization_id").asString()).isEqualTo(a2.get("id").asString());
        JsonNode r3 = recs.stream().filter(x -> "RRN3".equals(x.path("rrn").asString(null))).findFirst().orElseThrow();
        assertThat(r3.get("match_rule").asString()).isEqualTo("RRN");
        assertThat(r3.get("match_status").asString()).isEqualTo("MATCHED");

        JsonNode r4 = byNtid(recs, "NT4");
        assertThat(r4.get("match_status").asString()).isEqualTo("EXCEPTION");
        assertThat(r4.get("exception_reason").asString()).contains("beyond the tolerance");
        assertThat(r4.hasNonNull("journal_entry_id")).isFalse();

        // cleared above the authorisation within tolerance: capture 100 + adjustment 10
        JsonNode r5 = byNtid(recs, "NT5");
        assertThat(r5.get("match_status").asString()).isEqualTo("MATCHED");
        assertThat(new BigDecimal(r5.get("adjustment_amount").asString())).isEqualByComparingTo("10");
        assertThat(net.calls("POST", "/v1/journal-entries").stream()
                .filter(c -> c.key().startsWith("clearing-adjust:")).toList()).hasSize(1);
        assertThat(a5.get("status").asString()).isEqualTo("CLEARED");
        assertThat(new BigDecimal(a5.get("cleared_amount").asString())).isEqualByComparingTo("110");

        // cleared below the authorisation: the remainder is released with the capture
        JsonNode r6 = byNtid(recs, "NT6");
        assertThat(new BigDecimal(r6.get("adjustment_amount").asString())).isEqualByComparingTo("-20");
        JsonNode hold6 = net.holds.get(UUID.fromString(a6.get("hold_id").asString()));
        assertThat(new BigDecimal(hold6.get("captured_amount").asString())).isEqualByComparingTo("60");
        assertThat(hold6.get("released_remainder").asBoolean()).isTrue();
        assertThat(net.calls("POST", "/v1/holds/" + a6.get("hold_id").asString() + "/capture").getFirst().body()
                .get("counter_account_id").asString()).isEqualTo(SETTLE.toString());

        // expired authorisation -> force post without a hold
        JsonNode r7 = byNtid(recs, "NT7");
        assertThat(r7.get("match_status").asString()).isEqualTo("FORCE_POST");
        assertThat(r7.get("card_authorization_id").asString()).isEqualTo(a7.get("id").asString());
        assertThat(r7.get("exception_reason").asString()).contains("EXPIRED");

        assertThat(byNtid(recs, "NTX").get("match_status").asString()).isEqualTo("UNMATCHED");
        JsonNode refund = byNtid(recs, "NTR");
        assertThat(refund.get("match_status").asString()).isEqualTo("FORCE_POST");
        assertThat(refund.get("record_type").asString()).isEqualTo("REFUND");
        JsonNode r9 = byNtid(recs, "NT9");
        assertThat(r9.get("match_status").asString()).isEqualTo("FORCE_POST");
        assertThat(r9.get("match_rule").asString()).isEqualTo("NO_AUTHORIZATION");

        // ledger effects: cardholder A debited for every presentment (+ ATM fee), credited for the refund
        assertThat(net.balance(accountA)).isEqualByComparingTo("-717.50");
        assertThat(net.balance(accountB)).isEqualByComparingTo("-25.00");
        assertThat(net.balance(FEE_INCOME)).isEqualByComparingTo("2.50");
        BigDecimal postedInterchange = recs.stream()
                .filter(x -> "PRESENTMENT".equals(x.get("record_type").asString()))
                .filter(x -> List.of("MATCHED", "FORCE_POST").contains(x.get("match_status").asString()))
                .map(x -> new BigDecimal(x.get("interchange_amount").asString())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net.balance(IC_INCOME)).isEqualByComparingTo(postedInterchange);
        // the settlement account receives purchases net of refunds, minus interchange receivable
        assertThat(net.balance(SETTLE)).isEqualByComparingTo(
                new BigDecimal("717.50").subtract(new BigDecimal("2.50")).add(new BigDecimal("25.00"))
                        .subtract(postedInterchange));
        // every matched presentment told card-authorization-service it was cleared, keyed by record
        assertThat(net.calls("POST", "/clear")).hasSize(7);
        assertThat(net.calls("POST", "/clear")).allMatch(c -> c.key().startsWith("clear:"));
    }

    @Test
    @Order(2)
    void feesFromClearingAndSettlementPosition() {
        String fileId = file1.get("id").asString();
        List<JsonNode> recs = records(fileId);
        JsonNode atm = byNtid(recs, "NT10");
        JsonNode fees = get("/v1/card-fees?clearing_record_id=" + atm.get("id").asString());
        List<String> types = new ArrayList<>();
        fees.get("data").forEach(f -> types.add(f.get("fee_type").asString()));
        assertThat(types).containsExactlyInAnyOrder("ATM_WITHDRAWAL", "INTERCHANGE_INCOME");
        JsonNode fx = get("/v1/card-fees?clearing_record_id=" + byNtid(recs, "NT11").get("id").asString()
                + "&fee_type=FX_MARKUP").get("data").get(0);
        assertThat(new BigDecimal(fx.get("amount").asString())).isEqualByComparingTo("2.00");
        assertThat(fx.hasNonNull("journal_entry_id")).isFalse();
        assertThat(events("cards.card_fee.charged")).hasSize(1);

        JsonNode settlements = get("/v1/card-clearing/settlements?scheme=VISA&settlement_date=2026-10-02");
        assertThat(settlements.get("data")).hasSize(1);
        JsonNode s = settlements.get("data").get(0);
        settlementId = s.get("id").asString();
        BigDecimal ic = recs.stream().filter(x -> "PRESENTMENT".equals(x.get("record_type").asString()))
                .map(x -> new BigDecimal(x.get("interchange_amount").asString())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(s.get("transaction_count").asInt()).isEqualTo(12);
        assertThat(new BigDecimal(s.get("gross_purchases").asString())).isEqualByComparingTo("915.00");
        assertThat(new BigDecimal(s.get("gross_refunds").asString())).isEqualByComparingTo("15.00");
        assertThat(new BigDecimal(s.get("interchange").asString())).isEqualByComparingTo(ic);
        assertThat(new BigDecimal(s.get("net_amount").asString()))
                .isEqualByComparingTo(new BigDecimal("900.00").subtract(ic));
        assertThat(s.get("status").asString()).isEqualTo("PENDING");
        assertThat(s.get("card_program_id").asString()).isEqualTo(program.toString());

        assertThat(events("cards.clearing_file.processed")).hasSize(1);
        assertThat(events("cards.clearing_record.posted")).hasSize(10);
        assertThat(events("cards.clearing_record.unmatched")).hasSize(1);
        assertThat(events("cards.card_settlement.calculated")).hasSize(1);
    }

    @Test
    @Order(3)
    void jsonRecordsUploadAndParseFailures() {
        ObjectNode body = fileBody("visa-json.json", "2026-10-02");
        ArrayNode records = body.putArray("records");
        ObjectNode rec = records.addObject();
        rec.put("record_type", "PRESENTMENT");
        rec.put("network_transaction_id", "NT8");
        rec.put("transaction_date", "2026-10-01");
        rec.put("transaction_amount", 30);
        rec.put("transaction_currency", "USD");
        JsonNode f = ok(send("POST", "/v1/card-clearing/files", body, "file-json", "ops", TENANT), 201);
        assertThat(f.get("file_format").asString()).isEqualTo("JSON");
        assertThat(f.get("matched_count").asInt()).isEqualTo(1);
        assertThat(a8.get("status").asString()).isEqualTo("CLEARED");
        // same settlement date: the PENDING position is accumulated
        JsonNode s = get("/v1/card-clearing/settlements/" + settlementId);
        assertThat(s.get("transaction_count").asInt()).isEqualTo(13);
        assertThat(new BigDecimal(s.get("gross_purchases").asString())).isEqualByComparingTo("945.00");

        // a PAN column fails the file (201 FAILED) without echoing the number
        ObjectNode bad = fileBody("bad.csv", "2026-10-03");
        bad.put("content", "record_type,pan,transaction_date,transaction_amount,transaction_currency\n"
                + "PRESENTMENT,4111111111111111,2026-10-01,10.00,USD\n");
        JsonNode failed = ok(send("POST", "/v1/card-clearing/files", bad, "file-bad", "ops", TENANT), 201);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("error").asString()).contains("'pan' is not accepted").doesNotContain("4111111111111111");
        assertThat(events("cards.clearing_file.failed")).hasSize(1);
        assertThat(count("select count(*) from outbox where payload::text like '%4111111111111111%'")).isZero();
        assertThat(count("select count(*) from clearing_record where raw::text like '%4111111111111111%'")).isZero();
        HttpResponse<String> re = send("POST", "/v1/card-clearing/files/" + failed.get("id").asString() + "/reprocess",
                null, "re-bad", "ops", TENANT);
        assertThat(re.statusCode()).isEqualTo(422);
        assertThat(code(re)).isEqualTo("file_not_reprocessable");

        // validation: neither content nor records
        HttpResponse<String> none = send("POST", "/v1/card-clearing/files", fileBody("empty", "2026-10-03"),
                "file-empty", "ops", TENANT);
        assertThat(none.statusCode()).isEqualTo(422);
        assertThat(code(none)).isEqualTo("content_required");
        // missing required field -> 400
        ObjectNode noScheme = fileBody("x", "2026-10-03");
        noScheme.remove("scheme");
        noScheme.put("content", "a\n1\n");
        assertThat(send("POST", "/v1/card-clearing/files", noScheme, "file-noscheme", "ops", TENANT).statusCode())
                .isEqualTo(400);
    }

    @Test
    @Order(4)
    void forceMatchAndReprocess() {
        String fileId = file1.get("id").asString();
        List<JsonNode> recs = records(fileId);
        JsonNode unmatched = byNtid(recs, "NTX");
        ObjectNode a12 = net.auth(cardA, program, accountA, "30.00", "USD", "NT12", null, null, "APPROVED");
        ObjectNode req = json.createObjectNode();
        req.put("card_authorization_id", a12.get("id").asString());
        req.put("reason", "Matched by ARN after scheme inquiry");
        JsonNode fm = post("/v1/card-clearing/records/" + unmatched.get("id").asString() + "/force-match", req, "ops");
        assertThat(fm.get("match_status").asString()).isEqualTo("MATCHED");
        assertThat(fm.get("match_rule").asString()).isEqualTo("FORCE");
        assertThat(fm.get("force_matched_from_id").asString()).isEqualTo(unmatched.get("id").asString());
        assertThat(fm.get("card_authorization_id").asString()).isEqualTo(a12.get("id").asString());
        assertThat(a12.get("status").asString()).isEqualTo("CLEARED");
        assertThat(events("cards.clearing_record.force_matched")).hasSize(1);
        HttpResponse<String> again = send("POST", "/v1/card-clearing/records/" + unmatched.get("id").asString()
                + "/force-match", req, UUID.randomUUID().toString(), "ops", TENANT);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(code(again)).isEqualTo("already_resolved");
        // a posted record cannot be force-matched
        HttpResponse<String> posted = send("POST", "/v1/card-clearing/records/" + byNtid(recs, "NT1").get("id").asString()
                + "/force-match", req, UUID.randomUUID().toString(), "ops", TENANT);
        assertThat(posted.statusCode()).isEqualTo(409);
        // card mismatch
        JsonNode r4 = byNtid(recs, "NT4");
        ObjectNode other = net.auth(cardB, program, accountB, "130.00", "USD", "NTB", null, null, "APPROVED");
        ObjectNode bad = json.createObjectNode();
        bad.put("card_authorization_id", other.get("id").asString());
        bad.put("reason", "wrong");
        // NT4 record has no card_id on the record but resolves card A via its authorisation only when matched;
        // the record row stores card A from the authorisation, so another card's authorisation is refused
        HttpResponse<String> mismatch = send("POST", "/v1/card-clearing/records/" + r4.get("id").asString()
                + "/force-match", bad, UUID.randomUUID().toString(), "ops", TENANT);
        assertThat(mismatch.statusCode()).isEqualTo(422);
        assertThat(code(mismatch)).isEqualTo("card_mismatch");

        // the merchant's acquirer re-authorised NT4 (incremental): reprocess now matches it
        a4.put("approved_amount", "130.00");
        a4.put("billing_amount", "130.00");
        net.holds.get(UUID.fromString(a4.get("hold_id").asString())).put("amount", "130.00");
        JsonNode file = post("/v1/card-clearing/files/" + fileId + "/reprocess", null, "ops");
        assertThat(file.get("exception_count").asInt()).isZero();
        assertThat(file.get("unmatched_count").asInt()).isZero();
        assertThat(file.get("matched_count").asInt()).isEqualTo(12);
        List<JsonNode> after = records(fileId);
        assertThat(after).hasSize(14);
        JsonNode retry = after.stream().filter(x -> r4.get("id").asString().equals(x.path("force_matched_from_id")
                .asString(null))).findFirst().orElseThrow();
        assertThat(retry.get("match_status").asString()).isEqualTo("MATCHED");
        assertThat(retry.get("match_rule").asString()).isEqualTo("NETWORK_TRANSACTION_ID_RETRY");
        // records are append-only
        assertThat(count("select count(*) from clearing_record where match_status = 'EXCEPTION'")).isEqualTo(1L);
    }

    @Test
    @Order(5)
    void settlementConfirmPostsAgainstNostro() {
        JsonNode s = get("/v1/card-clearing/settlements/" + settlementId);
        BigDecimal netAmount = new BigDecimal(s.get("net_amount").asString());
        BigDecimal settleBefore = net.balance(SETTLE);
        ObjectNode req = json.createObjectNode();
        req.put("recon_reference", "STMT-2026-10-02-001");
        JsonNode c = post("/v1/card-clearing/settlements/" + settlementId + "/confirm", req, "treasury");
        assertThat(c.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(c.get("recon_reference").asString()).isEqualTo("STMT-2026-10-02-001");
        assertThat(c.get("confirmed_by").asString()).isEqualTo("treasury");
        assertThat(c.hasNonNull("confirmed_at")).isTrue();
        assertThat(c.hasNonNull("journal_entry_id")).isTrue();
        assertThat(net.balance(NOSTRO)).isEqualByComparingTo(netAmount);
        assertThat(net.balance(SETTLE)).isEqualByComparingTo(settleBefore.subtract(netAmount));
        assertThat(events("cards.card_settlement.confirmed")).hasSize(1);
        HttpResponse<String> again = send("POST", "/v1/card-clearing/settlements/" + settlementId + "/confirm", req,
                UUID.randomUUID().toString(), "treasury", TENANT);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(code(again)).isEqualTo("invalid_transition");

        // a later file for the confirmed date is posted but its settlement amounts are flagged
        ObjectNode body = fileBody("late.json", "2026-10-02");
        ObjectNode rec = body.putArray("records").addObject();
        rec.put("record_type", "PRESENTMENT");
        rec.put("card_id", cardB.toString());
        rec.put("transaction_date", "2026-10-01");
        rec.put("transaction_amount", "5.00");
        rec.put("transaction_currency", "USD");
        JsonNode late = ok(send("POST", "/v1/card-clearing/files", body, "file-late", "ops", TENANT), 201);
        assertThat(late.get("status").asString()).isEqualTo("POSTED");
        assertThat(late.get("error").asString()).contains("CONFIRMED");
        assertThat(get("/v1/card-clearing/settlements/" + settlementId).get("transaction_count").asInt()).isEqualTo(13);
    }

    @Test
    @Order(6)
    void feeChargeAndWaiveWithFourEyes() {
        ObjectNode req = json.createObjectNode();
        req.put("card_id", cardA.toString());
        req.put("fee_type", "MONTHLY");
        req.put("amount", "5.00");
        req.put("tax_amount", "1.00");
        req.put("currency", "USD");
        BigDecimal before = net.balance(accountA);
        BigDecimal incomeBefore = net.balance(FEE_INCOME);
        JsonNode fee = ok(send("POST", "/v1/card-fees", req, "fee-1", "alice", TENANT), 201);
        feeId = fee.get("id").asString();
        assertThat(fee.get("ledger_account_id").asString()).isEqualTo(accountA.toString());
        assertThat(fee.hasNonNull("journal_entry_id")).isTrue();
        assertThat(net.balance(accountA)).isEqualByComparingTo(before.subtract(new BigDecimal("6.00")));
        assertThat(net.balance(FEE_INCOME)).isEqualByComparingTo(incomeBefore.add(new BigDecimal("5.00")));
        assertThat(net.balance(TAX)).isEqualByComparingTo("1.00");

        ObjectNode bad = req.deepCopy();
        bad.put("amount", "0");
        HttpResponse<String> zero = send("POST", "/v1/card-fees", bad, "fee-zero", "alice", TENANT);
        assertThat(zero.statusCode()).isEqualTo(422);
        ObjectNode unknown = req.deepCopy();
        unknown.put("card_id", UUID.randomUUID().toString());
        assertThat(code(send("POST", "/v1/card-fees", unknown, "fee-unknown", "alice", TENANT))).isEqualTo("card_unknown");

        ObjectNode w = json.createObjectNode();
        w.put("reason", "Goodwill");
        HttpResponse<String> self = send("POST", "/v1/card-fees/" + feeId + "/waive", w, "waive-1", "alice", TENANT);
        assertThat(self.statusCode()).isEqualTo(422);
        assertThat(code(self)).isEqualTo("four_eyes_violation");
        JsonNode waiver = post("/v1/card-fees/" + feeId + "/waive", w, "bob");
        assertThat(waiver.get("waived").asBoolean()).isTrue();
        assertThat(waiver.get("waives_fee_id").asString()).isEqualTo(feeId);
        assertThat(net.balance(accountA)).isEqualByComparingTo(before);
        assertThat(net.balance(TAX)).isEqualByComparingTo("0");
        HttpResponse<String> twice = send("POST", "/v1/card-fees/" + feeId + "/waive", w, "waive-2", "bob", TENANT);
        assertThat(twice.statusCode()).isEqualTo(409);
        assertThat(code(twice)).isEqualTo("already_waived");
        HttpResponse<String> waiveWaiver = send("POST", "/v1/card-fees/" + waiver.get("id").asString() + "/waive", w,
                "waive-3", "carol", TENANT);
        assertThat(code(waiveWaiver)).isEqualTo("not_waivable");
        assertThat(events("cards.card_fee.waived")).hasSize(1);
        assertThat(get("/v1/card-fees?waived=true&card_id=" + cardA).get("data")).hasSize(1);
    }

    @Test
    @Order(7)
    void tenantIsolation() {
        String fileId = file1.get("id").asString();
        assertThat(send("GET", "/v1/card-clearing/files/" + fileId, null, null, "ops", OTHER_TENANT).statusCode())
                .isEqualTo(404);
        assertThat(ok(send("GET", "/v1/card-clearing/records", null, null, "ops", OTHER_TENANT), 200).get("data"))
                .isEmpty();
        assertThat(send("GET", "/v1/card-clearing/files/" + fileId + "/records", null, null, "ops", OTHER_TENANT)
                .statusCode()).isEqualTo(404);
        assertThat(send("POST", "/v1/card-fees/" + feeId + "/waive", json.createObjectNode().put("reason", "x"),
                "other-waive", "eve", OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/v1/card-clearing/settlements/" + settlementId + "/confirm",
                json.createObjectNode().put("recon_reference", "x"), "other-confirm", "eve", OTHER_TENANT).statusCode())
                .isEqualTo(404);
    }
}
