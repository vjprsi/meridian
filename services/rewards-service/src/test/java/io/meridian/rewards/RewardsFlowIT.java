package io.meridian.rewards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * rewards-service end to end over HTTP against PostgreSQL 18 with row-level security. ledger-service (payouts) and
 * deposit-service (account lookups) are stubbed through ServiceClient by {@link FakeServices}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RewardsFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000e1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000e2";
    static final String ALICE = "0199a000-0000-7000-8000-00000000a001";
    static final String BOB = "0199a000-0000-7000-8000-00000000a002";
    static final String CAROL = "0199a000-0000-7000-8000-00000000a003";
    static final String DAVE = "0199a000-0000-7000-8000-00000000a004";
    static final String ERIN = "0199a000-0000-7000-8000-00000000a005";
    static final String FRANK = "0199a000-0000-7000-8000-00000000a006";
    static final UUID ALICE_ACC = UUID.fromString("0199a000-0000-7000-8000-0000000ac001");
    static final UUID BOB_ACC = UUID.fromString("0199a000-0000-7000-8000-0000000ac002");
    static final String LEDGER = "0199a000-0000-7000-8000-00000000ad01";
    static final String FUNDING = "0199a000-0000-7000-8000-00000000ad02";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakeServices fake;
    LocalDate today;
    OffsetDateTime now;

    String stars;
    String cashbackProgram;
    String gold;
    String aliceAccount;
    String carolAccount;
    String groceryTxn2;
    String scItem;
    String mugItem;
    String bobCashback;

    @BeforeAll
    void setUp() {
        fake = new FakeServices(json);
        fake.depositAccount(ALICE_ACC, "GBP");
        fake.depositAccount(BOB_ACC, "GBP");
        now = jdbc.sql("select now()").query(OffsetDateTime.class).single().withOffsetSameInstant(ZoneOffset.UTC);
        today = now.toLocalDate();
    }

    @BeforeEach
    void stub() {
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> fake.get(i.getArgument(0), i.getArgument(1)));
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> fake.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
    }

    // ------------------------------------------------------------------ programs, tiers, rules

    @Test
    @Order(1)
    void programsWithMakerCheckerTiersAndRules() throws Exception {
        assertThat(code(post("/v1/rewards/programs", """
                {"code":"CB-BAD","name":"Cashback","program_type":"CASHBACK","starts_on":"%s"}""".formatted(today), 422)))
                .isEqualTo("invalid_program");
        String body = """
                {"code":"stars","name":"Stars","program_type":"POINTS","points_expiry_months":12,"auto_enrol":false,
                 "ledger_id":"%s","funding_ledger_account_id":"%s","starts_on":"%s"}""".formatted(LEDGER, FUNDING, today);
        HttpResponse<String> created = send("POST", "/v1/rewards/programs", body, "program-stars", TENANT, "maker");
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode p = json.readTree(created.body());
        stars = p.get("id").asString();
        assertThat(p.get("code").asString()).isEqualTo("STARS");
        assertThat(p.get("status").asString()).isEqualTo("DRAFT");
        HttpResponse<String> replay = send("POST", "/v1/rewards/programs", body, "program-stars", TENANT, "maker");
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(code(post("/v1/rewards/programs", body, 409))).isEqualTo("duplicate_code");

        assertThat(code(post("/v1/rewards/programs/" + stars + "/activate", "{}", 422))).isEqualTo("four_eyes_violation");
        JsonNode active = post("/v1/rewards/programs/" + stars + "/activate", "{\"comment\":\"ok\"}", 200, "checker");
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(active.get("approved_by").asString()).isEqualTo("checker");
        assertThat(code(post("/v1/rewards/programs/" + stars + "/activate", "{}", 409, "checker"))).isEqualTo("invalid_transition");
        assertThat(outbox("engagement.rewards_program.activated", stars)).isEqualTo(1);

        post("/v1/rewards/tiers", """
                {"rewards_program_id":"%s","code":"BLUE","name":"Blue","rank":1}""".formatted(stars), 201);
        gold = post("/v1/rewards/tiers", """
                {"rewards_program_id":"%s","code":"GOLD","name":"Gold","rank":2,"qualify_spend":"500","earn_multiplier":"2",
                 "benefits":[{"code":"LOUNGE","description":"Airport lounge"}]}""".formatted(stars), 201).get("id").asString();
        assertThat(code(post("/v1/rewards/tiers", """
                {"rewards_program_id":"%s","code":"DUP","name":"Dup","rank":2}""".formatted(stars), 409))).isEqualTo("duplicate_tier");
        assertThat(get("/v1/rewards/programs/" + stars + "/tiers").get("data")).hasSize(2);

        assertThat(code(post("/v1/rewards/rules", rule(stars, "bad", "\"cashback_rate\":\"0.01\""), 422))).isEqualTo("invalid_rule");
        assertThat(code(post("/v1/rewards/rules", rule(stars, "bad", "\"points_per_unit\":\"1\",\"valid_time_from\":\"25:00\",\"valid_time_to\":\"02:00\""), 422)))
                .isEqualTo("invalid_rule");
        String base = post("/v1/rewards/rules", rule(stars, "Base", "\"points_per_unit\":\"1\""), 201).get("id").asString();
        String grocery = post("/v1/rewards/rules", rule(stars, "Groceries",
                "\"points_per_unit\":\"2\",\"mcc_include\":[\"5411\"],\"priority\":50,\"cap_amount\":\"100\",\"cap_period\":\"DAY\""),
                201).get("id").asString();
        String travel = post("/v1/rewards/rules", rule(stars, "Travel", """
                "points_per_unit":"4","mcc_include":["4511"],"priority":40,"stackable":false,"settle_after_days":14"""), 201)
                .get("id").asString();
        String night = post("/v1/rewards/rules", rule(stars, "Night owl", """
                "fixed_award":"25","valid_time_from":"23:00","valid_time_to":"01:00","valid_days_of_week":[6,7],"priority":10"""), 201)
                .get("id").asString();
        for (String r : new String[] {base, grocery, travel, night}) {
            assertThat(post("/v1/rewards/rules/" + r + "/activate", null, 200).get("status").asString()).isEqualTo("ACTIVE");
        }
        int baseVersion = get("/v1/rewards/rules/" + base).get("row_version").asInt();
        assertThat(code(patch("/v1/rewards/rules/" + base, "{\"priority\":1}", baseVersion, 409))).isEqualTo("rule_active");
        assertThat(get("/v1/rewards/programs/" + stars + "/rules").get("data")).hasSize(4);

        // Simulation: Saturday 23:30 UTC grocery spend -> night owl (25) applies with the stackable rules.
        JsonNode sim = post("/v1/rewards/rules/simulate", """
                {"rewards_program_id":"%s","trigger":"CARD_SPEND","amount":"40","currency":"GBP","mcc":"5411",
                 "transaction_at":"2026-10-03T23:30:00Z"}""".formatted(stars), 200);
        assertThat(sim.get("points").asLong()).isEqualTo(40 + 80 + 25);
        JsonNode weekday = post("/v1/rewards/rules/simulate", """
                {"rewards_program_id":"%s","trigger":"CARD_SPEND","amount":"40","currency":"GBP","mcc":"4511",
                 "transaction_at":"2026-10-05T12:00:00Z"}""".formatted(stars), 200);
        assertThat(weekday.get("points").asLong()).isEqualTo(160); // travel is non-stackable and wins on priority
        assertThat(get("/v1/rewards/transactions").get("data")).isEmpty();

        cashbackProgram = post("/v1/rewards/programs", """
                {"code":"CB","name":"Cashback","program_type":"CASHBACK","value_currency":"GBP","ledger_id":"%s",
                 "funding_ledger_account_id":"%s","starts_on":"%s"}""".formatted(LEDGER, FUNDING, today), 201).get("id").asString();
        post("/v1/rewards/programs/" + cashbackProgram + "/activate", "{}", 200, "checker");
        String cbRule = post("/v1/rewards/rules", rule(cashbackProgram, "2% back", "\"cashback_rate\":\"0.02\",\"settle_after_days\":30"), 201)
                .get("id").asString();
        post("/v1/rewards/rules/" + cbRule + "/activate", null, 200);
    }

    // ------------------------------------------------------------------ earning

    @Test
    @Order(2)
    void earnWithCapsPendingPointsAndTiers() throws Exception {
        JsonNode acct = post("/v1/rewards/points-accounts", """
                {"rewards_program_id":"%s","customer_id":"%s","default_credit_account_id":"%s"}""".formatted(stars, ALICE, ALICE_ACC), 201);
        aliceAccount = acct.get("id").asString();
        assertThat(acct.get("reward_tier_id").isNull()).isFalse();
        assertThat(code(post("/v1/rewards/points-accounts", """
                {"rewards_program_id":"%s","customer_id":"%s"}""".formatted(stars, ALICE), 409))).isEqualTo("already_enrolled");

        JsonNode t1 = txn(ALICE, "100", "5411", 201);
        assertThat(t1.get("points_awarded").asLong()).isEqualTo(200);
        assertThat(t1.get("awards")).hasSize(2);
        String src = t1.get("source_id").asString();
        JsonNode again = post("/v1/rewards/transactions", """
                {"rewards_program_id":"%s","customer_id":"%s","trigger":"CARD_SPEND","source_type":"CARD_AUTHORIZATION",
                 "source_id":"%s","amount":"100","currency":"GBP","mcc":"5411","transaction_at":"%s"}""".formatted(stars, ALICE, src, now), 201);
        assertThat(again.get("id").asString()).isEqualTo(t1.get("id").asString());
        JsonNode t2 = txn(ALICE, "50", "5411", 201);
        assertThat(t2.get("points_awarded").asLong()).isEqualTo(50); // grocery cap reached for the day
        groceryTxn2 = t2.get("id").asString();
        JsonNode t3 = txn(ALICE, "100", "4511", 201);
        assertThat(t3.get("points_awarded").asLong()).isEqualTo(400);
        JsonNode a = get("/v1/rewards/points-accounts/" + aliceAccount);
        assertThat(a.get("available_points").asLong()).isEqualTo(250);
        assertThat(a.get("pending_points").asLong()).isEqualTo(400);
        assertThat(a.get("lifetime_earned").asLong()).isEqualTo(250);
        assertThat(a.get("next_expiry_date").asString()).isEqualTo(today.plusMonths(12).toString());
        assertThat(outbox("engagement.points.earned", null)).isEqualTo(4);
        assertThat(code(txn(CAROL, "10", "5411", 422))).isEqualTo("not_enrolled");

        // Tier: 550 spend in the period qualifies for GOLD; GOLD doubles base earn.
        txn(ALICE, "300", "5999", 201);
        JsonNode tiered = post("/v1/rewards/points-accounts/" + aliceAccount + "/recalculate-tier", null, 200);
        assertThat(tiered.get("reward_tier_id").asString()).isEqualTo(gold);
        assertThat(tiered.get("tier_qualified_until").asString()).isEqualTo(today.plusMonths(12).toString());
        assertThat(outbox("engagement.points_account.tier_changed", aliceAccount)).isEqualTo(2);
        assertThat(txn(ALICE, "10", "5999", 201).get("points_awarded").asLong()).isEqualTo(20);
        assertThat(get("/v1/rewards/points-accounts/" + aliceAccount).get("available_points").asLong()).isEqualTo(570);

        // Refund: the awards are clawed back once.
        JsonNode reversed = post("/v1/rewards/transactions/" + groceryTxn2 + "/reverse", "{\"reversal_reason\":\"refund\"}", 200);
        assertThat(reversed.get("status").asString()).isEqualTo("REVERSED");
        assertThat(code(post("/v1/rewards/transactions/" + groceryTxn2 + "/reverse", "{\"reversal_reason\":\"again\"}", 409)))
                .isEqualTo("invalid_transition");
        assertThat(get("/v1/rewards/points-accounts/" + aliceAccount).get("available_points").asLong()).isEqualTo(520);
        JsonNode lots = get("/v1/rewards/points-accounts/" + aliceAccount + "/lots");
        assertThat(lots.get("lots_total").asLong()).isEqualTo(520);
    }

    @Test
    @Order(3)
    void cashbackPendingConfirmationAndLedgerPayout() throws Exception {
        String source = UUID.randomUUID().toString();
        String ingest = """
                {"customer_id":"%s","trigger":"CARD_SPEND","source_type":"CARD_AUTHORIZATION","source_id":"%s",
                 "amount":"200","currency":"GBP","mcc":"5999","credit_account_id":"%s"}""".formatted(BOB, source, BOB_ACC);
        JsonNode r = post("/v1/rewards/transactions/ingest", ingest, 200);
        assertThat(r.get("results")).hasSize(1); // STARS has no auto-enrolment
        assertThat(r.get("cashback_awarded").asString()).isEqualTo("4");
        JsonNode r2 = post("/v1/rewards/transactions/ingest", ingest, 200);
        assertThat(r2.get("results").get(0).get("reward_transaction_id").asString())
                .isEqualTo(r.get("results").get(0).get("reward_transaction_id").asString());
        JsonNode cb = get("/v1/rewards/cashback?customer_id=" + BOB).get("data").get(0);
        bobCashback = cb.get("id").asString();
        assertThat(cb.get("status").asString()).isEqualTo("PENDING");
        assertThat(code(post("/v1/rewards/cashback/" + bobCashback + "/pay", null, 409))).isEqualTo("invalid_transition");

        String date = today.plusDays(31).toString();
        JsonNode eod = post("/v1/rewards/eod-runs/points-confirmation?business_date=" + date, null, 200);
        assertThat(eod.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(eod.get("counts").get("points_entries_confirmed").asInt()).isEqualTo(1);
        assertThat(eod.get("counts").get("cashback_confirmed").asInt()).isEqualTo(1);
        assertThat(post("/v1/rewards/eod-runs/points-confirmation?business_date=" + date, null, 200).get("replayed").asBoolean()).isTrue();
        JsonNode a = get("/v1/rewards/points-accounts/" + aliceAccount);
        assertThat(a.get("available_points").asLong()).isEqualTo(920);
        assertThat(a.get("pending_points").asLong()).isZero();
        assertThat(outbox("engagement.points.confirmed", null)).isEqualTo(1);

        JsonNode pay = post("/v1/rewards/eod-runs/cashback-payout?business_date=" + date, null, 200);
        assertThat(pay.get("counts").get("cashback_paid").asInt()).isEqualTo(1);
        JsonNode paid = get("/v1/rewards/cashback/" + bobCashback);
        assertThat(paid.get("status").asString()).isEqualTo("PAID");
        FakeServices.Call posting = fake.lastPosting();
        assertThat(posting.key()).isEqualTo("rewards-cashback:" + bobCashback);
        assertThat(posting.body().get("ledger_id").asString()).isEqualTo(LEDGER);
        assertThat(posting.body().get("entries").get(0).get("ledger_account_id").asString()).isEqualTo(FUNDING);
        assertThat(posting.body().get("entries").get(1).get("ledger_account_id").asString())
                .isEqualTo(fake.ledgerAccountOf(BOB_ACC).toString());
        assertThat(posting.body().get("entries").get(1).get("amount").asString()).isEqualTo("4.00");

        JsonNode rev = post("/v1/rewards/cashback/" + bobCashback + "/reverse", "{\"reversal_reason\":\"chargeback\"}", 200);
        assertThat(rev.get("status").asString()).isEqualTo("REVERSED");
        assertThat(rev.get("reversal_journal_entry_id").isNull()).isFalse();
        assertThat(outbox("engagement.cashback.reversed", bobCashback)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ adjustments, redemptions, transfers

    @Test
    @Order(4)
    void adjustmentsNeedFourEyes() throws Exception {
        JsonNode adj = post("/v1/rewards/points-accounts/" + aliceAccount + "/adjust", "{\"points\":1000,\"reason\":\"goodwill\"}", 200);
        assertThat(adj.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        String id = adj.get("id").asString();
        assertThat(code(post("/v1/rewards/points-adjustments/" + id + "/approve", "{}", 422))).isEqualTo("four_eyes_violation");
        JsonNode ok = post("/v1/rewards/points-adjustments/" + id + "/approve", "{\"comment\":\"fine\"}", 200, "checker");
        assertThat(ok.get("status").asString()).isEqualTo("APPLIED");
        assertThat(code(post("/v1/rewards/points-adjustments/" + id + "/reject", "{\"reason\":\"x\"}", 409, "checker")))
                .isEqualTo("invalid_transition");
        assertThat(get("/v1/rewards/points-accounts/" + aliceAccount).get("available_points").asLong()).isEqualTo(1920);
        assertThat(code(post("/v1/rewards/points-accounts/" + aliceAccount + "/adjust", "{\"points\":-99999,\"reason\":\"x\"}", 422)))
                .isEqualTo("insufficient_points");
        String neg = post("/v1/rewards/points-accounts/" + aliceAccount + "/adjust", "{\"points\":-20,\"reason\":\"error\"}", 200)
                .get("id").asString();
        assertThat(post("/v1/rewards/points-adjustments/" + neg + "/reject", "{\"reason\":\"no\"}", 200, "checker")
                .get("status").asString()).isEqualTo("REJECTED");
    }

    @Test
    @Order(5)
    void redemptionsStatementCreditPartnerItemsAndCancellation() throws Exception {
        scItem = post("/v1/rewards/catalog", """
                {"rewards_program_id":"%s","sku":"SC-5","name":"GBP 5 statement credit","item_type":"STATEMENT_CREDIT",
                 "points_cost":500,"cash_value":"5.00","currency":"GBP"}""".formatted(stars), 201).get("id").asString();
        mugItem = post("/v1/rewards/catalog", """
                {"rewards_program_id":"%s","sku":"MUG","name":"Mug","item_type":"MERCHANDISE","points_cost":100,"stock":1}"""
                .formatted(stars), 201).get("id").asString();
        assertThat(code(post("/v1/rewards/catalog", """
                {"rewards_program_id":"%s","sku":"SC-X","name":"x","item_type":"STATEMENT_CREDIT","points_cost":1}""".formatted(stars), 422)))
                .isEqualTo("invalid_catalog_item");

        JsonNode sc = post("/v1/rewards/redemptions", redeem(scItem, 1), 201);
        assertThat(sc.get("status").asString()).isEqualTo("FULFILLED");
        assertThat(sc.get("points_spent").asLong()).isEqualTo(500);
        assertThat(fake.lastPosting().body().get("entries").get(1).get("amount").asString()).isEqualTo("5.00");
        assertThat(fake.lastPosting().body().get("entries").get(1).get("ledger_account_id").asString())
                .isEqualTo(fake.ledgerAccountOf(ALICE_ACC).toString());
        assertThat(code(post("/v1/rewards/redemptions/" + sc.get("id").asString() + "/cancel", "{\"reason\":\"x\"}", 409)))
                .isEqualTo("invalid_transition");
        assertThat(code(post("/v1/rewards/redemptions", redeem(scItem, 4), 422))).isEqualTo("insufficient_points");

        JsonNode mug = post("/v1/rewards/redemptions", redeem(mugItem, 1), 201);
        assertThat(mug.get("status").asString()).isEqualTo("PROCESSING");
        assertThat(code(post("/v1/rewards/redemptions", redeem(mugItem, 1), 422))).isEqualTo("out_of_stock");
        JsonNode cancelled = post("/v1/rewards/redemptions/" + mug.get("id").asString() + "/cancel", "{\"reason\":\"changed mind\"}", 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(get("/v1/rewards/catalog/" + mugItem).get("stock").asInt()).isEqualTo(1);
        JsonNode mug2 = post("/v1/rewards/redemptions", redeem(mugItem, 1), 201);
        assertThat(post("/v1/rewards/redemptions/" + mug2.get("id").asString() + "/fulfil", "{\"partner_reference\":\"DHL-1\"}", 200)
                .get("status").asString()).isEqualTo("FULFILLED");
        assertThat(get("/v1/rewards/points-accounts/" + aliceAccount).get("available_points").asLong()).isEqualTo(1920 - 500 - 100);

        fake.ledgerDown.set(true);
        JsonNode failed = post("/v1/rewards/redemptions", redeem(scItem, 1), 201);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("failure_reason").asString()).startsWith("account_frozen");
        fake.ledgerDown.set(false);
        JsonNode retried = post("/v1/rewards/redemptions/" + failed.get("id").asString() + "/retry", null, 200);
        assertThat(retried.get("status").asString()).isEqualTo("FULFILLED");
        assertThat(get("/v1/rewards/points-accounts/" + aliceAccount).get("available_points").asLong()).isEqualTo(820);
        assertThat(outbox("engagement.redemption.failed", failed.get("id").asString())).isEqualTo(1);
    }

    @Test
    @Order(6)
    void transfersAndAccountStatus() throws Exception {
        carolAccount = post("/v1/rewards/points-accounts", """
                {"rewards_program_id":"%s","customer_id":"%s"}""".formatted(stars, CAROL), 201).get("id").asString();
        JsonNode from = post("/v1/rewards/points-accounts/" + aliceAccount + "/transfer", """
                {"to_points_account_id":"%s","points":100,"reason":"gift"}""".formatted(carolAccount), 200);
        assertThat(from.get("available_points").asLong()).isEqualTo(720);
        assertThat(get("/v1/rewards/points-accounts/" + carolAccount).get("available_points").asLong()).isEqualTo(100);
        assertThat(code(post("/v1/rewards/points-accounts/" + carolAccount + "/transfer", """
                {"to_points_account_id":"%s","points":101}""".formatted(aliceAccount), 422))).isEqualTo("insufficient_points");

        post("/v1/rewards/points-accounts/" + carolAccount + "/suspend", "{\"reason\":\"fraud review\"}", 200);
        assertThat(code(txn(CAROL, "10", "5999", 422))).isEqualTo("account_not_active");
        assertThat(code(post("/v1/rewards/points-accounts/" + carolAccount + "/suspend", "{\"reason\":\"again\"}", 409)))
                .isEqualTo("invalid_transition");
        assertThat(post("/v1/rewards/points-accounts/" + carolAccount + "/reactivate", "{}", 200).get("status").asString())
                .isEqualTo("ACTIVE");
        assertThat(outbox("engagement.points_account.status_changed", carolAccount)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ referrals

    @Test
    @Order(7)
    void referralsWithFraudGuardsQualificationAndPayout() throws Exception {
        assertThat(code(post("/v1/referral-programs", """
                {"code":"BAD","name":"x","qualification_rules":[{"event":"NOPE"}],"starts_on":"%s"}""".formatted(today), 422)))
                .isEqualTo("invalid_referral_program");
        String program = post("/v1/referral-programs", """
                {"code":"FRIENDS","name":"Refer a friend","qualification_rules":[{"event":"ACCOUNT_OPENED"},
                 {"event":"FIRST_DEPOSIT","min_amount":"100"}],"referrer_reward":"10","referee_points":500,
                 "rewards_program_id":"%s","currency":"GBP","budget_amount":"1000","ledger_id":"%s",
                 "funding_ledger_account_id":"%s","starts_on":"%s"}""".formatted(stars, LEDGER, FUNDING, today), 201).get("id").asString();
        assertThat(code(post("/v1/referrals/code", """
                {"referral_program_id":"%s","customer_id":"%s"}""".formatted(program, ALICE), 422))).isEqualTo("program_not_active");
        post("/v1/referral-programs/" + program + "/activate", null, 200);
        JsonNode holder = post("/v1/referrals/code", """
                {"referral_program_id":"%s","customer_id":"%s","device_fingerprint":"dev-1"}""".formatted(program, ALICE), 200);
        String code = holder.get("referral_code").asString();
        assertThat(code).hasSize(8);
        assertThat(holder.get("is_code_holder").asBoolean()).isTrue();
        assertThat(holder.has("device_hash")).isFalse();
        assertThat(post("/v1/referrals/code", """
                {"referral_program_id":"%s","customer_id":"%s"}""".formatted(program, ALICE), 200).get("referral_code").asString())
                .isEqualTo(code);

        JsonNode self = post("/v1/referrals/sign-up", signUp(code, ALICE, "dev-9"), 200);
        assertThat(self.get("status").asString()).isEqualTo("REJECTED");
        assertThat(self.get("rejection_reason").asString()).isEqualTo("self_referral");
        JsonNode reuse = post("/v1/referrals/sign-up", signUp(code, DAVE, "dev-1"), 200);
        assertThat(reuse.get("rejection_reason").asString()).isEqualTo("device_reuse");
        JsonNode erin = post("/v1/referrals/sign-up", signUp(code.toLowerCase(), ERIN, "dev-2"), 200);
        assertThat(erin.get("status").asString()).isEqualTo("SIGNED_UP");
        String referral = erin.get("id").asString();
        assertThat(code(post("/v1/referrals/sign-up", signUp(code, ERIN, "dev-2"), 409))).isEqualTo("already_referred");
        assertThat(code(post("/v1/referrals/sign-up", signUp("NOSUCH12", FRANK, "dev-5"), 422))).isEqualTo("invalid_referral_code");

        JsonNode e1 = post("/v1/referrals/qualification-events", """
                {"referee_customer_id":"%s","event_type":"ACCOUNT_OPENED"}""".formatted(ERIN), 200);
        assertThat(e1.get("referrals").get(0).get("status").asString()).isEqualTo("QUALIFYING");
        JsonNode small = post("/v1/referrals/qualification-events", """
                {"referee_customer_id":"%s","event_type":"FIRST_DEPOSIT","amount":"50","currency":"GBP"}""".formatted(ERIN), 200);
        assertThat(small.get("referrals").get(0).get("outcome").asString()).isEqualTo("no_match");

        fake.ledgerDown.set(true);
        JsonNode e2 = post("/v1/referrals/qualification-events", """
                {"referee_customer_id":"%s","event_type":"FIRST_DEPOSIT","amount":"150","currency":"GBP"}""".formatted(ERIN), 200);
        assertThat(e2.get("referrals").get(0).get("status").asString()).isEqualTo("QUALIFIED");
        JsonNode pending = get("/v1/referrals/" + referral);
        assertThat(pending.get("payout_failure_reason").asString()).contains("account_frozen");
        assertThat(pending.get("referee_points_entry_id").isNull()).isFalse();
        fake.ledgerDown.set(false);
        JsonNode rewarded = post("/v1/referrals/" + referral + "/pay", null, 200);
        assertThat(rewarded.get("status").asString()).isEqualTo("REWARDED");
        assertThat(fake.lastPosting().key()).isEqualTo("rewards-referral:" + referral + ":referrer");
        assertThat(fake.lastPosting().body().get("entries").get(1).get("ledger_account_id").asString())
                .isEqualTo(fake.ledgerAccountOf(ALICE_ACC).toString());
        JsonNode erinAccount = get("/v1/rewards/points-accounts?customer_id=" + ERIN).get("data").get(0);
        assertThat(erinAccount.get("available_points").asLong()).isEqualTo(500);
        assertThat(code(post("/v1/referrals/" + referral + "/reject", "{\"rejection_reason\":\"late\"}", 409))).isEqualTo("invalid_transition");
        assertThat(get("/v1/referral-programs/" + program).get("spent_amount").asString()).startsWith("10");
        assertThat(outbox("engagement.referral.rewarded", referral)).isEqualTo(1);
        assertThat(outbox("engagement.referral.payout_failed", referral)).isEqualTo(1);

        String frank = post("/v1/referrals/sign-up", signUp(code, FRANK, "dev-3"), 200).get("id").asString();
        JsonNode exp = post("/v1/rewards/eod-runs/referral-expiry?business_date=" + today.plusDays(61), null, 200);
        assertThat(exp.get("counts").get("referrals_expired").asInt()).isEqualTo(1);
        assertThat(get("/v1/referrals/" + frank).get("status").asString()).isEqualTo("EXPIRED");
    }

    // ------------------------------------------------------------------ end of day: expiry and requalification

    @Test
    @Order(8)
    void fifoExpiryAndTierRequalification() throws Exception {
        JsonNode lots = get("/v1/rewards/points-accounts/" + aliceAccount + "/lots");
        long available = get("/v1/rewards/points-accounts/" + aliceAccount).get("available_points").asLong();
        assertThat(lots.get("lots_total").asLong()).isEqualTo(available);
        String date = today.plusMonths(12).toString();
        JsonNode run = post("/v1/rewards/eod-runs/points-expiry?business_date=" + date, null, 200);
        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(run.get("counts").get("points_expired").asInt()).isGreaterThanOrEqualTo((int) available);
        assertThat(post("/v1/rewards/eod-runs/points-expiry?business_date=" + date, null, 200).get("replayed").asBoolean()).isTrue();
        JsonNode a = get("/v1/rewards/points-accounts/" + aliceAccount);
        assertThat(a.get("available_points").asLong()).isZero();
        assertThat(a.get("expiring_points").asLong()).isZero();
        assertThat(get("/v1/rewards/points-entries?points_account_id=" + aliceAccount + "&entry_type=EXPIRE").get("data")).isNotEmpty();
        // a second business date finds nothing left to expire
        assertThat(post("/v1/rewards/eod-runs/points-expiry?business_date=" + today.plusMonths(13), null, 200)
                .get("counts").get("lots_expired").asInt()).isZero();

        JsonNode tiers = post("/v1/rewards/eod-runs/tier-requalification?business_date=" + today.plusMonths(12).plusDays(1), null, 200);
        assertThat(tiers.get("counts").get("tiers_changed").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(get("/v1/rewards/points-accounts/" + aliceAccount).get("reward_tier_id").asString()).isNotEqualTo(gold);

        JsonNode closed = post("/v1/rewards/points-accounts/" + carolAccount + "/close", "{\"reason\":\"customer left\"}", 200);
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
    }

    // ------------------------------------------------------------------ tenancy and protocol

    @Test
    @Order(9)
    void tenantIsolationAndProtocolErrors() throws Exception {
        assertThat(send("GET", "/v1/rewards/programs/" + stars, null, null, OTHER_TENANT, "reader").statusCode()).isEqualTo(404);
        JsonNode other = json.readTree(send("GET", "/v1/rewards/points-accounts", null, null, OTHER_TENANT, "reader").body());
        assertThat(other.get("data")).isEmpty();
        assertThat(send("POST", "/v1/rewards/programs/" + stars + "/pause", "{\"reason\":\"x\"}", null, TENANT, "maker").statusCode())
                .isEqualTo(400);
        post("/v1/rewards/programs/" + stars + "/pause", "{}", 400);
        assertThat(post("/v1/rewards/programs/" + stars + "/pause", "{\"reason\":\"review\"}", 200).get("status").asString())
                .isEqualTo("PAUSED");
        assertThat(code(txn(ALICE, "10", "5999", 422))).isEqualTo("program_not_active");
    }

    // ------------------------------------------------------------------ helpers

    private String rule(String program, String name, String extra) {
        return """
                {"rewards_program_id":"%s","name":"%s","trigger":"CARD_SPEND","valid_from":"2026-01-01T00:00:00Z",%s}"""
                .formatted(program, name, extra);
    }

    private String redeem(String item, int qty) {
        return """
                {"points_account_id":"%s","reward_catalog_item_id":"%s","quantity":%d}""".formatted(aliceAccount, item, qty);
    }

    private static String signUp(String code, String referee, String device) {
        return """
                {"referral_code":"%s","referee_customer_id":"%s","device_fingerprint":"%s"}""".formatted(code, referee, device);
    }

    private JsonNode txn(String customer, String amount, String mcc, int expected) throws Exception {
        return post("/v1/rewards/transactions", """
                {"rewards_program_id":"%s","customer_id":"%s","trigger":"CARD_SPEND","source_type":"CARD_AUTHORIZATION",
                 "source_id":"%s","amount":"%s","currency":"GBP","mcc":"%s","channel":"POS","transaction_at":"%s"}"""
                .formatted(stars, customer, UUID.randomUUID(), amount, mcc, now), expected);
    }

    private String code(JsonNode problem) {
        return problem.path("code").asString();
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        return post(path, body, expected, "maker");
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), TENANT, user);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private JsonNode patch(String path, String body, int version, int expected) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "maker").header("Content-Type", "application/json")
                .header("If-Match", "\"" + version + "\"")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, TENANT, "reader");
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant, String user)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int outbox(String type, String aggregateId) {
        return aggregateId == null
                ? jdbc.sql("select count(*) from outbox where event_type = ? and tenant_id = ?::uuid")
                        .params(type, TENANT).query(Integer.class).single()
                : jdbc.sql("select count(*) from outbox where event_type = ? and aggregate_id = ?::uuid")
                        .params(type, aggregateId).query(Integer.class).single();
    }
}
