package io.meridian.carddispute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

/**
 * card-dispute-service end to end over HTTP against PostgreSQL 18 (RLS enforced) with card-service and ledger-service
 * stubbed behind a mocked {@link ServiceClient} ({@link FakeDownstream}). Ledger balances of the cardholder, dispute
 * suspense, scheme receivable and loss expense accounts are asserted after each outcome.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.cards.ledger-id=0199c000-0000-7000-8000-0000000000a1",
        "meridian.cards.dispute.suspense-account=0199c000-0000-7000-8000-0000000000d1",
        "meridian.cards.dispute.loss-expense-account=0199c000-0000-7000-8000-0000000000d2",
        "meridian.cards.dispute.scheme-receivable-account=0199c000-0000-7000-8000-0000000000d3"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CardDisputeFlowIT {
    static final String TENANT = "0199c000-0000-7000-8000-00000000d001";
    static final String OTHER_TENANT = "0199c000-0000-7000-8000-00000000d002";
    static final UUID SUSPENSE = UUID.fromString("0199c000-0000-7000-8000-0000000000d1");
    static final UUID LOSS = UUID.fromString("0199c000-0000-7000-8000-0000000000d2");
    static final UUID RECEIVABLE = UUID.fromString("0199c000-0000-7000-8000-0000000000d3");

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
    LocalDate today;
    String disputeA;

    @BeforeAll
    void setUp() {
        fake = new FakeDownstream(json);
        today = LocalDate.parse(jdbc.sql("select current_date::text").query(String.class).single());
    }

    @BeforeEach
    void stub() {
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> fake.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        when(serviceClient.get(anyString(), anyString()))
                .thenAnswer(i -> fake.get(i.getArgument(0), i.getArgument(1)));
    }

    /** A card with its own cardholder ledger account. */
    record Card(UUID id, UUID customer, UUID ledger) {}

    Card card() {
        Card c = new Card(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        fake.addCard(c.id(), c.customer(), c.ledger());
        return c;
    }

    String body(Card c, String scheme, String category, String amount, LocalDate txDate, String extra) {
        return "{\"card_id\":\"" + c.id() + "\",\"scheme\":\"" + scheme + "\",\"category\":\"" + category
                + "\",\"disputed_amount\":\"" + amount + "\",\"currency\":\"USD\""
                + (txDate == null ? "" : ",\"transaction_date\":\"" + txDate + "\"")
                + (extra == null ? "" : "," + extra) + "}";
    }

    // ------------------------------------------------------------------ 1. intake

    @Test
    @Order(1)
    void openWithValidationDuplicatesAndIdempotency() throws Exception {
        Card c = card();
        String clearing = UUID.randomUUID().toString();
        String b = body(c, "VISA", "NOT_RECEIVED", "100.00", today.minusDays(10),
                "\"clearing_record_id\":\"" + clearing + "\",\"customer_description\":\"Shoes never arrived\"");
        HttpResponse<String> r = send("POST", "/v1/card-disputes", b, "open-a", "maker", TENANT, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        JsonNode d = json.readTree(r.body());
        disputeA = d.get("id").asString();
        assertThat(d.get("stage").asString()).isEqualTo("INQUIRY");
        assertThat(d.get("status").asString()).isEqualTo("OPEN");
        assertThat(d.get("customer_id").asString()).isEqualTo(c.customer().toString());
        assertThat(d.get("ledger_account_id").asString()).isEqualTo(c.ledger().toString());
        assertThat(d.get("suggested_reason_code").asString()).isEqualTo("13.1");
        assertThat(d.get("stage_deadline").asString()).isEqualTo(today.minusDays(10).plusDays(120).toString());
        assertThat(d.get("regulatory_deadline").asString()).isEqualTo(today.plusDays(45).toString());
        assertThat(events("cards.card_dispute.opened", disputeA)).isEqualTo(1);

        HttpResponse<String> replay = send("POST", "/v1/card-disputes", b, "open-a", "maker", TENANT, null);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(disputeA);

        assertThat(post("/v1/card-disputes", b, "maker", 409).get("code").asString()).isEqualTo("duplicate_dispute");
        Card unknown = new Card(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThat(post("/v1/card-disputes", body(unknown, "VISA", "FRAUD", "10", null, null), "maker", 422)
                .get("code").asString()).isEqualTo("card_not_found");
        assertThat(post("/v1/card-disputes", body(c, "VISA", "FRAUD", "0", null, null), "maker", 422)
                .get("code").asString()).isEqualTo("invalid_amount");
        assertThat(post("/v1/card-disputes", body(c, "VISA", "FRAUD", "10", null, "\"reason_code\":\"4837\""), "maker", 422)
                .get("code").asString()).isEqualTo("invalid_reason_code");
        assertThat(post("/v1/card-disputes", body(c, "VISA", "FRAUD", "10", today.plusDays(2), null), "maker", 422)
                .get("code").asString()).isEqualTo("invalid_transaction_date");
        assertThat(send("POST", "/v1/card-disputes", b, null, "maker", TENANT, null).statusCode()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ 2. happy path to WON

    @Test
    @Order(2)
    void provisionalCreditChargebackAndWin() throws Exception {
        Card c = cardOf(disputeA);
        JsonNode ev1 = post("/v1/card-dispute-evidence", evidence(disputeA, "RECEIPT"), "analyst", 201);
        post("/v1/card-dispute-evidence", evidence(disputeA, "CORRESPONDENCE"), "analyst", 201);
        JsonNode ev3 = post("/v1/card-dispute-evidence", evidence(disputeA, "SCREENSHOT"), "analyst", 201);
        assertThat(send("DELETE", "/v1/card-dispute-evidence/" + ev3.get("id").asString(), null, null, "analyst", TENANT, null)
                .statusCode()).isEqualTo(204);

        assertThat(post("/v1/card-disputes/" + disputeA + "/provisional-credit", "{\"amount\":\"150\"}", "analyst", 422)
                .get("code").asString()).isEqualTo("amount_exceeds_dispute");
        JsonNode pc = post("/v1/card-disputes/" + disputeA + "/provisional-credit", "{\"amount\":\"100.00\"}", "analyst", 200);
        assertThat(dec(pc, "provisional_credit_amount")).isEqualByComparingTo("100");
        assertThat(fake.balance(c.ledger())).isEqualByComparingTo("100");
        assertThat(fake.balance(SUSPENSE)).isEqualByComparingTo("-100");
        FakeDownstream.Call posting = fake.calls("POST", "/v1/journal-entries").getLast();
        assertThat(posting.key()).isEqualTo("dispute-pc:" + disputeA);
        assertThat(posting.body().get("entry_type").asString()).isEqualTo("ADJUSTMENT");
        assertThat(post("/v1/card-disputes/" + disputeA + "/provisional-credit", "{\"amount\":\"1\"}", "analyst", 409)
                .get("code").asString()).isEqualTo("already_credited");
        assertThat(events("cards.card_dispute.provisional_credit_granted", disputeA)).isEqualTo(1);

        assertThat(post("/v1/card-disputes/" + disputeA + "/record-representment", "{\"amount\":\"10\"}", "analyst", 409)
                .get("code").asString()).isEqualTo("invalid_transition");
        JsonNode ret = post("/v1/card-disputes/" + disputeA + "/request-retrieval", "{\"note\":\"need receipt\"}", "analyst", 200);
        assertThat(ret.get("stage").asString()).isEqualTo("RETRIEVAL");
        assertThat(ret.get("status").asString()).isEqualTo("AWAITING_MERCHANT");
        assertThat(ret.get("stage_deadline").asString()).isEqualTo(today.plusDays(30).toString());

        assertThat(post("/v1/card-disputes/" + disputeA + "/submit-chargeback", "{\"reason_code\":\"4855\",\"amount\":\"100\"}",
                "analyst", 422).get("code").asString()).isEqualTo("invalid_reason_code");
        JsonNode cb = post("/v1/card-disputes/" + disputeA + "/submit-chargeback",
                "{\"reason_code\":\"13.1\",\"amount\":\"100.00\"}", "analyst", 200);
        assertThat(cb.get("stage").asString()).isEqualTo("FIRST_CHARGEBACK");
        assertThat(cb.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(cb.get("submitted_at").isNull()).isFalse();
        assertThat(cb.get("stage_deadline").asString()).isEqualTo(today.plusDays(30).toString());
        JsonNode evs = get("/v1/card-dispute-evidence?card_dispute_id=" + disputeA).get("data");
        assertThat(evs).hasSize(2);
        evs.forEach(e -> assertThat(e.get("submitted_to_scheme").asBoolean()).isTrue());
        assertThat(send("DELETE", "/v1/card-dispute-evidence/" + ev1.get("id").asString(), null, null, "analyst", TENANT, null)
                .body()).contains("evidence_submitted");
        HttpResponse<String> patch = send("PATCH", "/v1/card-disputes/" + disputeA, "{\"reason_code\":\"13.3\"}", null,
                "analyst", TENANT, version(disputeA));
        assertThat(patch.statusCode()).as(patch.body()).isEqualTo(409);

        assertThat(post("/v1/card-disputes/" + disputeA + "/close", "{\"outcome\":\"WON\",\"recovered_amount\":\"150\"}",
                "analyst", 422).get("code").asString()).isEqualTo("invalid_recovered_amount");
        JsonNode won = post("/v1/card-disputes/" + disputeA + "/close", "{\"outcome\":\"WON\",\"note\":\"merchant did not respond\"}",
                "analyst", 200);
        assertThat(won.get("stage").asString()).isEqualTo("CLOSED");
        assertThat(won.get("status").asString()).isEqualTo("WON");
        assertThat(won.get("outcome").asString()).isEqualTo("WON");
        assertThat(dec(won, "recovered_amount")).isEqualByComparingTo("100");
        assertThat(dec(won, "written_off_amount")).isEqualByComparingTo("0");
        assertThat(won.get("closed_at").isNull()).isFalse();
        assertThat(won.get("outcome_entry_id").isNull()).isFalse();
        assertThat(fake.balance(c.ledger())).isEqualByComparingTo("100");
        assertThat(fake.balance(SUSPENSE)).isEqualByComparingTo("0");
        assertThat(fake.balance(RECEIVABLE)).isEqualByComparingTo("-100");
        assertThat(events("cards.card_dispute.closed", disputeA)).isEqualTo(1);
        assertThat(events("cards.card_dispute.stage_changed", disputeA)).isEqualTo(3);

        assertThat(post("/v1/card-disputes/" + disputeA + "/close", "{\"outcome\":\"LOST\"}", "analyst", 409)
                .get("code").asString()).isEqualTo("invalid_transition");
        assertThat(post("/v1/card-dispute-evidence", evidence(disputeA, "OTHER"), "analyst", 409)
                .get("code").asString()).isEqualTo("dispute_closed");
        assertThat(post("/v1/card-disputes/" + disputeA + "/assign", "{\"assigned_to\":\"bob\"}", "analyst", 409)
                .get("code").asString()).isEqualTo("invalid_transition");

        List<String> types = new ArrayList<>();
        get("/v1/card-disputes/" + disputeA + "/events").get("data").forEach(e -> types.add(e.get("event_type").asString()));
        assertThat(types).contains("CREATED", "EVIDENCE_ADDED", "PROVISIONAL_CREDIT", "RETRIEVAL_REQUESTED",
                "CHARGEBACK_SENT", "WON");
    }

    // ------------------------------------------------------------------ 3. LOST via accepted representment

    @Test
    @Order(3)
    void representmentAcceptedReversesProvisionalCredit() throws Exception {
        Card c = card();
        String id = post("/v1/card-disputes", body(c, "MASTERCARD", "FRAUD", "80.00", today.minusDays(5), null),
                "maker", 201).get("id").asString();
        assertThat(get("/v1/card-disputes/" + id).get("suggested_reason_code").asString()).isEqualTo("4837");
        post("/v1/card-disputes/" + id + "/provisional-credit", "{\"amount\":\"80\"}", "analyst", 200);
        JsonNode cb = post("/v1/card-disputes/" + id + "/submit-chargeback", "{\"reason_code\":\"4837\",\"amount\":\"80\"}",
                "analyst", 200);
        assertThat(cb.get("stage_deadline").asString()).isEqualTo(today.plusDays(45).toString());
        assertThat(post("/v1/card-disputes/" + id + "/record-representment", "{\"amount\":\"90\"}", "analyst", 422)
                .get("code").asString()).isEqualTo("invalid_amount");
        JsonNode rep = post("/v1/card-disputes/" + id + "/record-representment",
                "{\"amount\":\"80\",\"note\":\"signed delivery proof\",\"scheme_case_id\":\"MC-778\"}", "analyst", 200);
        assertThat(rep.get("stage").asString()).isEqualTo("REPRESENTMENT");
        assertThat(rep.get("status").asString()).isEqualTo("AWAITING_CUSTOMER");
        assertThat(rep.get("scheme_case_id").asString()).isEqualTo("MC-778");
        assertThat(post("/v1/card-disputes/" + id + "/escalate", "{\"stage\":\"RETRIEVAL\"}", "analyst", 422)
                .get("code").asString()).isEqualTo("invalid_stage");
        assertThat(post("/v1/card-disputes/" + id + "/escalate", "{\"stage\":\"ARBITRATION\"}", "analyst", 409)
                .get("code").asString()).isEqualTo("invalid_transition");

        JsonNode lost = post("/v1/card-disputes/" + id + "/accept-representment", "{\"note\":\"evidence is valid\"}",
                "analyst", 200);
        assertThat(lost.get("status").asString()).isEqualTo("LOST");
        assertThat(lost.get("outcome").asString()).isEqualTo("LOST");
        assertThat(lost.get("provisional_credit_reversed").asBoolean()).isTrue();
        assertThat(fake.calls("POST", "/v1/journal-entries/" + get("/v1/card-disputes/" + id)
                .get("provisional_credit_entry_id").asString() + "/reverse")).hasSize(1);
        assertThat(fake.balance(c.ledger())).isEqualByComparingTo("0");
        assertThat(dec(lost, "written_off_amount")).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ 4. SPLIT with issuer write-off (four-eyes)

    @Test
    @Order(4)
    void splitAfterArbitrationWithWriteOffNeedsFourEyes() throws Exception {
        Card c = card();
        String id = post("/v1/card-disputes", body(c, "VISA", "NOT_AS_DESCRIBED", "200.00", today.minusDays(20), null),
                "maker", 201).get("id").asString();
        post("/v1/card-disputes/" + id + "/provisional-credit", "{\"amount\":\"200\"}", "analyst", 200);
        post("/v1/card-disputes/" + id + "/submit-chargeback", "{\"reason_code\":\"13.3\",\"amount\":\"200\"}", "analyst", 200);
        post("/v1/card-disputes/" + id + "/record-representment", "{\"amount\":\"120\"}", "analyst", 200);
        JsonNode pre = post("/v1/card-disputes/" + id + "/escalate", "{\"stage\":\"PRE_ARBITRATION\"}", "analyst", 200);
        assertThat(pre.get("stage").asString()).isEqualTo("PRE_ARBITRATION");
        JsonNode arb = post("/v1/card-disputes/" + id + "/escalate", "{\"stage\":\"ARBITRATION\",\"note\":\"filed\"}",
                "analyst", 200);
        assertThat(arb.get("stage").asString()).isEqualTo("ARBITRATION");
        assertThat(arb.get("stage_deadline").asString()).isEqualTo(today.plusDays(10).toString());

        assertThat(post("/v1/card-disputes/" + id + "/close", "{\"outcome\":\"SPLIT\"}", "checker", 422)
                .get("code").asString()).isEqualTo("invalid_recovered_amount");
        String split = "{\"outcome\":\"SPLIT\",\"recovered_amount\":\"80\",\"customer_liable\":false}";
        assertThat(post("/v1/card-disputes/" + id + "/close", split, "maker", 422).get("code").asString())
                .isEqualTo("four_eyes_violation");
        JsonNode closed = post("/v1/card-disputes/" + id + "/close", split, "checker", 200);
        assertThat(closed.get("outcome").asString()).isEqualTo("SPLIT");
        assertThat(closed.get("status").asString()).isEqualTo("WON");
        assertThat(dec(closed, "recovered_amount")).isEqualByComparingTo("80");
        assertThat(dec(closed, "written_off_amount")).isEqualByComparingTo("120");
        assertThat(fake.balance(c.ledger())).isEqualByComparingTo("200");
        assertThat(fake.balance(RECEIVABLE)).isEqualByComparingTo("-180"); // 100 (dispute A) + 80
        assertThat(fake.balance(LOSS)).isEqualByComparingTo("-120");
        assertThat(fake.balance(SUSPENSE)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ 5. withdrawal and explicit reversal

    @Test
    @Order(5)
    void withdrawalAndExplicitReversal() throws Exception {
        Card c = card();
        String d = post("/v1/card-disputes", body(c, "VISA", "DUPLICATE", "50", today.minusDays(3), null), "maker", 201)
                .get("id").asString();
        post("/v1/card-disputes/" + d + "/provisional-credit", "{\"amount\":\"50\"}", "analyst", 200);
        assertThat(fake.balance(c.ledger())).isEqualByComparingTo("50");
        JsonNode w = post("/v1/card-disputes/" + d + "/close", "{\"outcome\":\"WITHDRAWN\",\"note\":\"merchant refunded\"}",
                "analyst", 200);
        assertThat(w.get("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(fake.balance(c.ledger())).isEqualByComparingTo("0");

        Card c2 = card();
        String e = post("/v1/card-disputes", body(c2, "VISA", "PROCESSING_ERROR", "30", null, null), "maker", 201)
                .get("id").asString();
        assertThat(post("/v1/card-disputes/" + e + "/reverse-provisional-credit", "{\"reason\":\"x\"}", "analyst", 409)
                .get("code").asString()).isEqualTo("no_provisional_credit");
        post("/v1/card-disputes/" + e + "/provisional-credit", "{\"amount\":\"30\"}", "analyst", 200);
        JsonNode rev = post("/v1/card-disputes/" + e + "/reverse-provisional-credit", "{\"reason\":\"duplicate claim\"}",
                "analyst", 200);
        assertThat(rev.get("provisional_credit_reversed").asBoolean()).isTrue();
        assertThat(fake.balance(c2.ledger())).isEqualByComparingTo("0");
        assertThat(events("cards.card_dispute.provisional_credit_reversed", e)).isEqualTo(1);
        assertThat(post("/v1/card-disputes/" + e + "/reverse-provisional-credit", "{\"reason\":\"again\"}", "analyst", 409)
                .get("code").asString()).isEqualTo("already_reversed");
        int postings = fake.calls("POST", "/v1/journal-entries").size();
        JsonNode lost = post("/v1/card-disputes/" + e + "/close", "{\"outcome\":\"LOST\"}", "analyst", 200);
        assertThat(lost.hasNonNull("outcome_entry_id")).isFalse();
        assertThat(fake.calls("POST", "/v1/journal-entries")).hasSize(postings);
    }

    // ------------------------------------------------------------------ 6. deadlines

    @Test
    @Order(6)
    void deadlineScanNotifiesOnceAndExpiresMissedDisputes() throws Exception {
        Card c = card();
        String soon = post("/v1/card-disputes", body(c, "VISA", "FRAUD", "25", today.minusDays(118), null), "maker", 201)
                .get("id").asString();
        Card g = card();
        String missed = post("/v1/card-disputes", body(g, "VISA", "NOT_RECEIVED", "40", today.minusDays(125), null),
                "maker", 201).get("id").asString();
        post("/v1/card-disputes/" + missed + "/provisional-credit", "{\"amount\":\"40\"}", "analyst", 200);
        assertThat(post("/v1/card-disputes/" + missed + "/submit-chargeback", "{\"reason_code\":\"13.1\",\"amount\":\"40\"}",
                "analyst", 422).get("code").asString()).isEqualTo("deadline_passed");
        BigDecimal lossBefore = fake.balance(LOSS);

        JsonNode scan = post("/v1/card-disputes/deadline-scan", null, "scheduler", 200);
        assertThat(scan.get("as_of").asString()).isEqualTo(today.toString());
        assertThat(scan.get("approaching").asInt()).isEqualTo(1);
        assertThat(scan.get("expired").asInt()).isEqualTo(1);
        assertThat(events("cards.card_dispute.deadline_approaching", soon)).isEqualTo(1);
        JsonNode exp = get("/v1/card-disputes/" + missed);
        assertThat(exp.get("status").asString()).isEqualTo("EXPIRED");
        assertThat(exp.get("outcome").asString()).isEqualTo("EXPIRED");
        assertThat(dec(exp, "written_off_amount")).isEqualByComparingTo("40");
        assertThat(fake.balance(g.ledger())).isEqualByComparingTo("40");
        assertThat(fake.balance(LOSS).subtract(lossBefore)).isEqualByComparingTo("-40");
        assertThat(fake.balance(SUSPENSE)).isEqualByComparingTo("0");

        JsonNode again = post("/v1/card-disputes/deadline-scan", null, "scheduler", 200);
        assertThat(again.get("approaching").asInt()).isZero();
        assertThat(again.get("expired").asInt()).isZero();
        assertThat(events("cards.card_dispute.deadline_approaching", soon)).isEqualTo(1);
        JsonNode future = post("/v1/card-disputes/deadline-scan?as_of=" + today.plusDays(3), null, "scheduler", 200);
        assertThat(future.get("expired").asInt()).isEqualTo(1); // soon's deadline (today+2) has passed by then
    }

    // ------------------------------------------------------------------ 7. copilot, assign, PATCH

    @Test
    @Order(7)
    void aiSummaryAssignAndGuardedPatch() throws Exception {
        Card c = card();
        String id = post("/v1/card-disputes", body(c, "MASTERCARD", "NOT_RECEIVED", "60", today.minusDays(7),
                "\"customer_description\":\"Concert cancelled\""), "maker", 201).get("id").asString();
        JsonNode s1 = post("/v1/card-disputes/" + id + "/ai-summary", null, "analyst", 200);
        JsonNode s2 = post("/v1/card-disputes/" + id + "/ai-summary", null, "analyst", 200);
        assertThat(s1.get("ai_summary").asString()).isEqualTo(s2.get("ai_summary").asString())
                .contains("4855", "Concert cancelled", "60.00 USD");
        assertThat(s1.get("suggested_reason_code").asString()).isEqualTo("4855");

        JsonNode a = post("/v1/card-disputes/" + id + "/assign", "{\"assigned_to\":\"alice\"}", "lead", 200);
        assertThat(a.get("assigned_to").asString()).isEqualTo("alice");
        assertThat(get("/v1/card-disputes?assigned_to=alice").get("data")).hasSize(1);

        String v = version(id);
        HttpResponse<String> bad = send("PATCH", "/v1/card-disputes/" + id, "{\"disputed_amount\":\"1\"}", null, "x", TENANT, v);
        assertThat(bad.statusCode()).isEqualTo(422);
        HttpResponse<String> stale = send("PATCH", "/v1/card-disputes/" + id, "{\"scheme_case_id\":\"X1\"}", null, "x",
                TENANT, "\"1\"");
        assertThat(stale.statusCode()).isEqualTo(409);
        HttpResponse<String> ok = send("PATCH", "/v1/card-disputes/" + id,
                "{\"scheme_case_id\":\"X1\",\"transaction_date\":\"" + today.minusDays(1) + "\"}", null, "x", TENANT, v);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        JsonNode patched = json.readTree(ok.body());
        assertThat(patched.get("scheme_case_id").asString()).isEqualTo("X1");
        assertThat(patched.get("stage_deadline").asString()).isEqualTo(today.minusDays(1).plusDays(120).toString());
        List<String> types = new ArrayList<>();
        get("/v1/card-disputes/" + id + "/events").get("data").forEach(e -> types.add(e.get("event_type").asString()));
        assertThat(types).contains("AI_SUMMARY", "ASSIGNED", "CREATED");
    }

    // ------------------------------------------------------------------ 8. tenant isolation

    @Test
    @Order(8)
    void tenantIsolation() throws Exception {
        assertThat(send("GET", "/v1/card-disputes/" + disputeA, null, null, "x", OTHER_TENANT, null).statusCode()).isEqualTo(404);
        assertThat(json.readTree(send("GET", "/v1/card-disputes", null, null, "x", OTHER_TENANT, null).body()).get("data"))
                .isEmpty();
        assertThat(send("GET", "/v1/card-disputes/" + disputeA + "/events", null, null, "x", OTHER_TENANT, null).statusCode())
                .isEqualTo(404);
        assertThat(send("POST", "/v1/card-disputes/" + disputeA + "/assign", "{\"assigned_to\":\"eve\"}",
                UUID.randomUUID().toString(), "x", OTHER_TENANT, null).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/v1/card-dispute-evidence", evidence(disputeA, "OTHER"), UUID.randomUUID().toString(), "x",
                OTHER_TENANT, null).statusCode()).isEqualTo(422);
    }

    // ------------------------------------------------------------------ helpers

    private Card cardOf(String disputeId) throws Exception {
        JsonNode d = get("/v1/card-disputes/" + disputeId);
        return new Card(UUID.fromString(d.get("card_id").asString()), UUID.fromString(d.get("customer_id").asString()),
                UUID.fromString(d.get("ledger_account_id").asString()));
    }

    private String evidence(String disputeId, String type) {
        return "{\"card_dispute_id\":\"" + disputeId + "\",\"evidence_type\":\"" + type + "\",\"document_id\":\""
                + UUID.randomUUID() + "\",\"description\":\"" + type.toLowerCase() + "\"}";
    }

    private String version(String id) throws Exception {
        return "\"" + get("/v1/card-disputes/" + id).get("row_version").asInt() + "\"";
    }

    private int events(String type, String aggregateId) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", () -> jdbc.sql("""
                select count(*) from outbox where tenant_id = ?::uuid and event_type = ? and aggregate_id = ?::uuid""")
                .params(TENANT, type, aggregateId).query(Integer.class).single());
    }

    private static BigDecimal dec(JsonNode n, String field) {
        return new BigDecimal(n.get(field).asString());
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), user, TENANT, null);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, "ops", TENANT, null);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String user, String tenant,
                                      String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
