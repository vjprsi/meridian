package io.meridian.collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.collections.infrastructure.DelinquencyListener;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
 * collections-service end to end over HTTP against PostgreSQL 18 (RLS enforced) with loan-service stubbed behind a
 * mocked {@link ServiceClient} ({@link FakeLoanService}). The end-of-day strategy run is called over HTTP the way
 * business-day-service calls it, for consecutive business dates.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.collections.sync-page-limit=2"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CollectionsFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000c011";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-00000000c012";
    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DelinquencyListener listener;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakeLoanService loans;
    final UUID customer = UUID.randomUUID();
    UUID l1;
    UUID l2;
    UUID l3;
    UUID cl1;
    String queue;
    String early;
    String case1;
    String case2;
    String case3;
    String caseCl;

    @BeforeAll
    void createFake() {
        loans = new FakeLoanService(json);
        l1 = loans.loan("INSTALMENT", customer, 20, "300.00", "5000.00");
        l2 = loans.loan("INSTALMENT", customer, 95, "1200.00", "8000.00");
        l3 = loans.loan("BNPL", customer, 3, "50.00", "200.00");
        cl1 = loans.creditLine(customer, 35, "900.00");
    }

    @BeforeEach
    void stub() {
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> loans.get(i.getArgument(0), i.getArgument(1)));
    }

    // ------------------------------------------------------------------ 1. configuration

    @Test
    @Order(1)
    void strategiesStepsAndQueuesAreValidated() throws Exception {
        queue = post("/v1/collections/queues", """
                {"code":"Q-EARLY","name":"Early collectors","assignment_mode":"ROUND_ROBIN","agent_user_ids":["ann","bob"]}""",
                "admin", 201).get("id").asString();
        expect(send("POST", "/v1/collections/queues", """
                {"code":"Q-BAD","name":"Bad","agent_user_ids":["ann","ann"]}""", "admin"), 422, "invalid_agents");

        expect(send("POST", "/v1/collections/strategies", """
                {"code":"BAD","name":"Bad","segment_rules":{},"min_dpd":30,"max_dpd":10}""", "admin"), 422, "invalid_dpd_range");
        expect(send("POST", "/v1/collections/strategies", """
                {"code":"BAD","name":"Bad","segment_rules":{"colour":"red"},"min_dpd":1}""", "admin"), 422,
                "invalid_segment_rules");
        expect(send("POST", "/v1/collections/strategies", """
                {"code":"BAD","name":"Bad","segment_rules":{},"min_dpd":1,"quiet_hours":{"start":"late"}}""", "admin"),
                422, "invalid_quiet_hours");

        early = post("/v1/collections/strategies", """
                {"code":"EARLY","name":"Early arrears","segment_rules":{},"min_dpd":1,"max_dpd":59,"priority":100,
                 "max_contacts_per_week":3,"quiet_hours":{"start":"21:00","end":"08:00"}}""", "admin", 201).get("id").asString();
        String late = post("/v1/collections/strategies", """
                {"code":"LATE","name":"Late arrears","segment_rules":{"min_balance":"1000"},"min_dpd":60,"priority":100}""",
                "admin", 201).get("id").asString();

        post("/v1/collections/strategies/" + early + "/steps", step(early, 1, 1, "SMS", null), "admin", 201);
        post("/v1/collections/strategies/" + early + "/steps", step(early, 2, 5, "EMAIL", null), "admin", 201);
        post("/v1/collections/strategies/" + early + "/steps", step(early, 3, 15, "CALL", queue), "admin", 201);
        expect(send("POST", "/v1/collections/strategies/" + early + "/steps", step(early, 3, 20, "LETTER", null), "admin"), 409,
                "duplicate_step_no");
        expect(send("POST", "/v1/collections/strategies/" + early + "/steps", step(early, 4, 75, "LETTER", null), "admin"), 422,
                "trigger_dpd_out_of_range");
        expect(send("POST", "/v1/collections/strategies/" + early + "/steps", step(early, 4, 20, "CALL",
                UUID.randomUUID().toString()), "admin"), 422, "queue_not_found");
        post("/v1/collections/strategies/" + late + "/steps", step(late, 1, 60, "LETTER", null), "admin", 201);
        post("/v1/collections/strategies/" + late + "/steps", step(late, 2, 90, "LEGAL_NOTICE", queue), "admin", 201);

        assertThat(get("/v1/collections/strategies/" + early + "/steps").get("data")).hasSize(3);
    }

    // ------------------------------------------------------------------ 2. EOD strategy run

    @Test
    @Order(2)
    void endOfDayOpensCasesRunsStepsAndDistributesTheQueue() throws Exception {
        JsonNode run = post("/v1/collections/cases/eod/strategy-run?business_date=" + TODAY, null, "system:eod", 200);
        assertThat(run.get("status").asString()).as(run.toString()).isEqualTo("COMPLETED");
        assertThat(run.get("replayed").asBoolean()).isFalse();
        JsonNode counts = run.get("counts");
        assertThat(counts.get("delinquent_exposures").asInt()).isEqualTo(4);
        assertThat(counts.get("cases_opened").asInt()).isEqualTo(4);
        // L1: SMS + EMAIL; L2: LETTER; L3: SMS; CL1: SMS + EMAIL
        assertThat(counts.get("actions_sent").asInt()).isEqualTo(6);
        // CALL of L1 and CL1, LEGAL_NOTICE of L2 route to the queue
        assertThat(counts.get("cases_routed").asInt()).isEqualTo(3);
        assertThat(counts.get("cases_assigned").asInt()).isEqualTo(3);
        assertThat(loans.calls("GET /v1/loans?status=IN_ARREARS")).isGreaterThanOrEqualTo(2); // paged (limit 2)

        case1 = caseOf(l1);
        case2 = caseOf(l2);
        case3 = caseOf(l3);
        caseCl = caseOf(cl1);
        JsonNode c1 = get("/v1/collections/cases/" + case1);
        assertThat(c1.get("case_number").asString()).startsWith("COL-");
        assertThat(c1.get("delinquency_bucket").asString()).isEqualTo("DPD_1_29");
        assertThat(c1.get("collection_strategy_id").asString()).isEqualTo(early);
        assertThat(c1.get("last_step_no").asInt()).isEqualTo(3);
        assertThat(c1.get("collection_queue_id").asString()).isEqualTo(queue);
        assertThat(c1.get("assigned_to").asString()).isIn("ann", "bob");
        assertThat(c1.get("priority_score").asInt()).isPositive();
        assertThat(get("/v1/collections/cases/" + case3).get("exposure_type").asString()).isEqualTo("BNPL");
        JsonNode cl = get("/v1/collections/cases/" + caseCl);
        assertThat(cl.get("exposure_type").asString()).isEqualTo("CREDIT_LINE");
        assertThat(new BigDecimal(cl.get("total_outstanding").asString())).isEqualByComparingTo("915.00");
        assertThat(get("/v1/collections/cases/" + case2).get("delinquency_bucket").asString()).isEqualTo("DPD_90_119");

        JsonNode actions = get("/v1/collections/cases/" + case1 + "/actions").get("data");
        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).get("performed_by").asString()).isEqualTo("SYSTEM");
        assertThat(actions.get(0).get("outcome").asString()).isEqualTo("SENT");

        // queue view ordered by priority (L2 is the most urgent)
        JsonNode queued = get("/v1/collections/queues/" + queue + "/cases").get("data");
        assertThat(queued).hasSize(3);
        assertThat(queued.get(0).get("id").asString()).isEqualTo(case2);

        // same business date again: replayed from the run ledger, nothing executed twice
        JsonNode again = post("/v1/collections/cases/eod/strategy-run?business_date=" + TODAY, null, "system:eod", 200);
        assertThat(again.get("replayed").asBoolean()).isTrue();
        assertThat(get("/v1/collections/cases/" + case1 + "/actions").get("data")).hasSize(2);
        assertThat(events("lending.collection_case.opened", null)).isEqualTo(4);
        assertThat(get("/v1/collections/eod-runs?job=COLLECTIONS_STRATEGY_RUN").get("data")).hasSize(1);
        assertThat(send("POST", "/v1/collections/cases/eod/strategy-run", null, "system:eod").statusCode()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ 3. working a case

    @Test
    @Order(3)
    void assignAndLogContacts() throws Exception {
        expect(send("POST", "/v1/collections/cases/" + case1 + "/assign", "{\"assigned_to\":\"zed\"}", "lead"), 422,
                "agent_not_in_queue");
        expect(send("POST", "/v1/collections/cases/" + case1 + "/assign", "{}", "lead"), 422, "assignment_required");
        assertThat(post("/v1/collections/cases/" + case1 + "/assign", "{\"assigned_to\":\"bob\"}", "lead", 200)
                .get("assigned_to").asString()).isEqualTo("bob");

        JsonNode action = post("/v1/collections/cases/" + case1 + "/log-action",
                "{\"action_type\":\"CALL\",\"outcome\":\"NO_ANSWER\",\"notes\":\"voicemail\"}", "bob", 200);
        assertThat(action.get("performed_by").asString()).isEqualTo("bob");
        JsonNode c = get("/v1/collections/cases/" + case1);
        assertThat(c.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(c.hasNonNull("last_contact_at")).isTrue();
        expect(send("POST", "/v1/collections/cases/" + case1 + "/log-action",
                "{\"action_type\":\"TELEPATHY\",\"outcome\":\"SENT\"}", "bob"), 400, null);
        assertThat(get("/v1/collections/actions?collection_case_id=" + case1).get("data")).hasSize(3);
    }

    // ------------------------------------------------------------------ 4. promises: kept and broken

    @Test
    @Order(4)
    void promisesAreKeptOrBrokenByTheRepaymentsSeen() throws Exception {
        String body = "{\"promised_amount\":\"150.00\",\"promised_date\":\"" + TODAY.plusDays(3) + "\",\"channel\":\"PHONE\"}";
        String key = UUID.randomUUID().toString();
        HttpResponse<String> first = send("POST", "/v1/collections/cases/" + case1 + "/promise", body, key, "bob", TENANT);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        HttpResponse<String> replay = send("POST", "/v1/collections/cases/" + case1 + "/promise", body, key, "bob", TENANT);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(json.readTree(first.body()).get("id").asString());
        String promise1 = json.readTree(first.body()).get("id").asString();
        assertThat(get("/v1/collections/cases/" + case1).get("status").asString()).isEqualTo("PROMISE_ACTIVE");

        expect(send("POST", "/v1/collections/cases/" + case1 + "/promise", body, "bob"), 409, "promise_already_active");
        expect(send("POST", "/v1/collections/cases/" + case2 + "/promise",
                "{\"promised_amount\":\"200.00\",\"promised_date\":\"" + TODAY.plusDays(45) + "\"}", "bob"), 422,
                "invalid_promised_date");
        expect(send("POST", "/v1/collections/cases/" + case2 + "/promise",
                "{\"promised_amount\":\"-5\",\"promised_date\":\"" + TODAY.plusDays(2) + "\"}", "bob"), 422, "invalid_amount");
        expect(send("PATCH", "/v1/collections/promises/" + promise1, "{\"promised_amount\":\"1.00\"}", "bob"), 422,
                "field_not_updatable");
        String promise2 = post("/v1/collections/cases/" + case2 + "/promise",
                "{\"promised_amount\":\"200.00\",\"promised_date\":\"" + TODAY.plusDays(2) + "\"}", "bob", 200)
                .get("id").asString();

        // L1 pays the promised amount; a reversed payment on L2 does not count
        loans.repay(l1, "150.00", TODAY.plusDays(1));
        UUID bounced = loans.repay(l2, "200.00", TODAY.plusDays(1));
        loans.reverse(l2, bounced, TODAY.plusDays(2));

        JsonNode day1 = post("/v1/collections/cases/eod/strategy-run?business_date=" + TODAY.plusDays(1), null, "system:eod", 200);
        assertThat(day1.get("counts").get("promises_kept").asInt()).as(day1.toString()).isEqualTo(1);
        JsonNode p1 = get("/v1/collections/promises/" + promise1);
        assertThat(p1.get("status").asString()).isEqualTo("KEPT");
        assertThat(new BigDecimal(p1.get("paid_amount").asString())).isEqualByComparingTo("150.00");
        JsonNode c1 = get("/v1/collections/cases/" + case1);
        assertThat(c1.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(c1.hasNonNull("last_payment_at")).isTrue();
        assertThat(get("/v1/collections/promises/" + promise2).get("status").asString()).isEqualTo("ACTIVE");

        JsonNode day3 = post("/v1/collections/cases/eod/strategy-run?business_date=" + TODAY.plusDays(3), null, "system:eod", 200);
        assertThat(day3.get("counts").get("promises_broken").asInt()).as(day3.toString()).isEqualTo(1);
        assertThat(get("/v1/collections/promises/" + promise2).get("status").asString()).isEqualTo("BROKEN");
        assertThat(get("/v1/collections/cases/" + case2).get("broken_promises").asInt()).isEqualTo(1);
        assertThat(events("lending.promise_to_pay.kept", promise1)).isEqualTo(1);
        assertThat(events("lending.promise_to_pay.broken", promise2)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 5. payment plan four-eyes

    @Test
    @Order(5)
    void paymentPlanOutsidePolicyNeedsASecondUser() throws Exception {
        JsonNode plan = post("/v1/collections/cases/" + case2 + "/payment-plan", """
                {"installment_count":13,"frequency":"MONTHLY","start_date":"%s"}""".formatted(TODAY.plusDays(5)), "maker", 200);
        assertThat(plan.get("status").asString()).isEqualTo("PROPOSED");
        assertThat(plan.get("requires_approval").asBoolean()).isTrue();
        assertThat(new BigDecimal(plan.get("total_amount").asString())).isEqualByComparingTo("1200.00");
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode i : plan.get("installments")) {
            sum = sum.add(new BigDecimal(i.get("amount").asString()));
        }
        assertThat(sum).isEqualByComparingTo("1200.00");
        assertThat(plan.get("installments").get(12).get("amount").asString()).isEqualTo("92.40"); // 12 x 92.30 + true-up
        String id = plan.get("id").asString();
        expect(send("POST", "/v1/collections/cases/" + case2 + "/payment-plan", """
                {"installment_count":3,"frequency":"MONTHLY","start_date":"%s"}""".formatted(TODAY.plusDays(5)), "maker"),
                409, "payment_plan_exists");
        expect(send("POST", "/v1/collections/payment-plans/" + id + "/activate", null, "maker"), 422, "four_eyes_violation");
        JsonNode active = post("/v1/collections/payment-plans/" + id + "/activate", null, "checker", 200);
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(active.get("activated_by").asString()).isEqualTo("checker");
        assertThat(get("/v1/collections/cases/" + case2).get("status").asString()).isEqualTo("PLAN_ACTIVE");
        expect(send("POST", "/v1/collections/payment-plans/" + id + "/activate", null, "checker"), 409, "invalid_transition");
        assertThat(post("/v1/collections/payment-plans/" + id + "/cancel", "{\"reason\":\"customer moved to legal\"}",
                "checker", 200).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(get("/v1/collections/cases/" + case2).get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(events("lending.payment_plan.activated", id)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 6. legal four-eyes and stages

    @Test
    @Order(6)
    void legalProceedingsNeedApprovalAndFollowTheCourtStages() throws Exception {
        expect(send("POST", "/v1/collections/cases/" + case2 + "/start-legal", "{\"claim_amount\":\"9999.00\"}", "maker"),
                422, "amount_exceeds_outstanding");
        JsonNode lp = post("/v1/collections/cases/" + case2 + "/start-legal",
                "{\"claim_amount\":\"8000.00\",\"law_firm\":\"Panel LLP\"}", "maker", 200);
        String id = lp.get("id").asString();
        assertThat(lp.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        expect(send("POST", "/v1/collections/cases/" + case2 + "/start-legal", "{\"claim_amount\":\"100.00\"}", "maker"),
                409, "legal_proceeding_exists");
        expect(send("POST", "/v1/collections/legal-proceedings/" + id + "/approve", "{}", "maker"), 422, "four_eyes_violation");
        JsonNode approved = post("/v1/collections/legal-proceedings/" + id + "/approve", "{\"comment\":\"ok\"}", "checker", 200);
        assertThat(approved.get("status").asString()).isEqualTo("INITIATED");
        assertThat(approved.get("approved_by").asString()).isEqualTo("checker");
        assertThat(get("/v1/collections/cases/" + case2).get("status").asString()).isEqualTo("LEGAL");

        expect(send("POST", "/v1/collections/legal-proceedings/" + id + "/advance", "{\"status\":\"HEARING\"}", "legal"),
                409, "invalid_transition");
        expect(send("POST", "/v1/collections/legal-proceedings/" + id + "/advance", "{\"status\":\"FILED\"}", "legal"),
                422, "filing_details_required");
        JsonNode filed = post("/v1/collections/legal-proceedings/" + id + "/advance",
                "{\"status\":\"FILED\",\"court\":\"County Court\",\"case_reference\":\"CC-2026-1\"}", "legal", 200);
        assertThat(filed.get("status").asString()).isEqualTo("FILED");
        assertThat(filed.get("filed_at").asString()).isNotBlank();
        expect(send("POST", "/v1/collections/cases/" + case2 + "/promise",
                "{\"promised_amount\":\"10.00\",\"promised_date\":\"" + TODAY.plusDays(2) + "\"}", "bob"), 409, "case_in_legal");
        assertThat(events("lending.legal_proceeding.started", id)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 7. next best action

    @Test
    @Order(7)
    void nextBestActionIsExplainedAndCanBeAcceptedOnce() throws Exception {
        JsonNode nba = post("/v1/collections/cases/" + case1 + "/next-best-action", null, "bob", 200);
        assertThat(nba.get("status").asString()).isEqualTo("SUGGESTED");
        assertThat(nba.get("model_version").asString()).isEqualTo("nba-rules-1.0");
        assertThat(nba.get("reason_codes")).isNotEmpty();
        assertThat(nba.get("alternatives")).hasSize(7);
        assertThat(nba.get("explanation").asString()).startsWith("Suggest " + nba.get("suggested_action").asString());
        assertThat(new BigDecimal(nba.get("propensity_to_pay").asString())).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        String id = nba.get("id").asString();
        JsonNode accepted = post("/v1/collections/next-best-actions/" + id + "/accept", null, "bob", 200);
        assertThat(accepted.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(accepted.get("decided_by").asString()).isEqualTo("bob");
        assertThat(get("/v1/collections/cases/" + case1).hasNonNull("next_action_at")).isTrue();
        expect(send("POST", "/v1/collections/next-best-actions/" + id + "/reject", "{\"reason\":\"no\"}", "bob"), 409,
                "invalid_transition");
        // carrying out the suggested channel marks the suggestion executed
        post("/v1/collections/cases/" + case1 + "/log-action", "{\"action_type\":\"" + nba.get("suggested_action").asString()
                + "\",\"outcome\":\"RIGHT_PARTY_CONTACT\"}", "bob", 200);
        assertThat(get("/v1/collections/next-best-actions/" + id).get("status").asString()).isEqualTo("EXECUTED");

        // a disputed case only gets written communication
        post("/v1/collections/cases/" + case3 + "/mark-dispute", "{\"is_disputed\":true,\"notes\":\"not my purchase\"}", "bob", 200);
        JsonNode disputed = post("/v1/collections/cases/" + case3 + "/next-best-action", null, "bob", 200);
        assertThat(disputed.get("suggested_action").asString()).isIn("LETTER", "EMAIL");
        assertThat(disputed.get("reason_codes").get(0).get("code").asString()).isEqualTo("DEBT_DISPUTED");
    }

    // ------------------------------------------------------------------ 8. suppression, rebalance, agency

    @Test
    @Order(8)
    void disputedCasesHaveAutomatedContactSuppressed() throws Exception {
        loans.delinquency(l3, 6, "50.00", "200.00");
        JsonNode day4 = post("/v1/collections/cases/eod/strategy-run?business_date=" + TODAY.plusDays(4), null, "system:eod", 200);
        assertThat(day4.get("counts").get("actions_suppressed").asInt()).as(day4.toString()).isEqualTo(1);
        JsonNode actions = get("/v1/collections/actions?collection_case_id=" + case3 + "&outcome=SUPPRESSED").get("data");
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).get("action_type").asString()).isEqualTo("EMAIL");
        assertThat(get("/v1/collections/cases/" + case3).get("days_past_due").asInt()).isEqualTo(6);
    }

    @Test
    @Order(9)
    void rebalanceSpreadsTheQueueEvenly() throws Exception {
        post("/v1/collections/cases/" + caseCl + "/assign", "{\"assigned_to\":\"ann\"}", "lead", 200);
        post("/v1/collections/cases/" + case1 + "/assign", "{\"assigned_to\":\"ann\"}", "lead", 200);
        JsonNode r = post("/v1/collections/queues/" + queue + "/rebalance", null, "lead", 200);
        assertThat(r.get("cases").asInt()).isEqualTo(3);
        Set<Integer> loads = new HashSet<>(List.of(r.get("per_agent").get("ann").asInt(), r.get("per_agent").get("bob").asInt()));
        assertThat(loads).as(r.toString()).containsExactlyInAnyOrder(1, 2);
        assertThat(r.get("unassigned").asInt()).isZero();

        assertThat(post("/v1/collections/cases/" + caseCl + "/send-to-agency", "{\"agency_name\":\"Acme Recoveries\"}",
                "lead", 200).get("agency_name").asString()).isEqualTo("Acme Recoveries");
        expect(send("POST", "/v1/collections/cases/" + caseCl + "/send-to-agency", "{\"agency_name\":\"Other\"}", "lead"),
                409, "already_with_agency");
    }

    // ------------------------------------------------------------------ 9. cure, pay-off, write-off, close

    @Test
    @Order(10)
    void casesCloseOnCurePayOffAndFollowWriteOff() throws Exception {
        loans.delinquency(l1, 0, "0", "4700.00");   // cured
        loans.status(l3, "PAID_OFF");                // paid off
        loans.status(cl1, "CHARGED_OFF");           // written off
        JsonNode day5 = post("/v1/collections/cases/eod/strategy-run?business_date=" + TODAY.plusDays(5), null, "system:eod", 200);
        assertThat(day5.get("counts").get("cases_cured").asInt()).as(day5.toString()).isEqualTo(2);
        assertThat(day5.get("counts").get("cases_written_off").asInt()).isEqualTo(1);
        JsonNode c1 = get("/v1/collections/cases/" + case1);
        assertThat(c1.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(c1.get("resolution").asString()).isEqualTo("CURED");
        assertThat(get("/v1/collections/cases/" + case3).get("resolution").asString()).isEqualTo("PAID_OFF");
        assertThat(get("/v1/collections/cases/" + caseCl).get("status").asString()).isEqualTo("WRITTEN_OFF");
        assertThat(get("/v1/collections/next-best-actions?collection_case_id=" + case3 + "&status=SUGGESTED").get("data")).isEmpty();

        expect(send("POST", "/v1/collections/cases/" + case1 + "/log-action", "{\"action_type\":\"SMS\",\"outcome\":\"SENT\"}",
                "bob"), 409, "case_closed");
        JsonNode closed = post("/v1/collections/cases/" + case2 + "/close", "{\"resolution\":\"SETTLED\",\"notes\":\"court settlement\"}",
                "lead", 200);
        assertThat(closed.get("status").asString()).isEqualTo("RESOLVED");
        expect(send("POST", "/v1/collections/cases/" + case2 + "/close", "{\"resolution\":\"OTHER\"}", "lead"), 409, "case_closed");
        assertThat(events("lending.collection_case.closed", null)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ 10. manual open and the delinquency event

    @Test
    @Order(11)
    void manualOpenAndDelinquencyEvents() throws Exception {
        UUID loan = UUID.randomUUID();
        expect(send("POST", "/v1/collections/cases/open", openBody(loan, 0, "0", "100.00"), "ops"), 422, "not_delinquent");
        expect(send("POST", "/v1/collections/cases/open", openBody(loan, 10, "500.00", "100.00"), "ops"), 422, "invalid_amount");
        expect(send("POST", "/v1/collections/cases/open", "{\"exposure_type\":\"LOAN\"}", "ops"), 400, null);
        JsonNode opened = post("/v1/collections/cases/open", openBody(loan, 10, "50.00", "100.00"), "ops", 200);
        assertThat(opened.get("status").asString()).isEqualTo("OPEN");

        // the loan-service event refreshes the same case, and a DPD 0 event cures it
        consume(loan, 31, "80.00");
        JsonNode refreshed = get("/v1/collections/cases/" + opened.get("id").asString());
        assertThat(refreshed.get("days_past_due").asInt()).isEqualTo(31);
        assertThat(refreshed.get("delinquency_bucket").asString()).isEqualTo("DPD_30_59");
        assertThat(refreshed.get("max_days_past_due").asInt()).isEqualTo(31);
        consume(loan, 0, "0");
        JsonNode cured = get("/v1/collections/cases/" + opened.get("id").asString());
        assertThat(cured.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(cured.get("resolution").asString()).isEqualTo("CURED");
    }

    @Test
    @Order(12)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/collections/cases/" + case1, null, null, "ops", OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/v1/collections/cases/" + case1 + "/assign", "{\"assigned_to\":\"x\"}",
                UUID.randomUUID().toString(), "ops", OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(json.readTree(send("GET", "/v1/collections/cases", null, null, "ops", OTHER_TENANT).body()).get("data")).isEmpty();
        assertThat(json.readTree(send("GET", "/v1/collections/strategies", null, null, "ops", OTHER_TENANT).body())
                .get("data")).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private void consume(UUID loan, int dpd, String overdue) throws Exception {
        String payload = """
                {"id":"%s","loan_id":"%s","customer_id":"%s","days_past_due":%d,"overdue_amount":"%s",
                 "total_outstanding":"100.00","currency":"EUR","delinquency_bucket":"X"}"""
                .formatted(UUID.randomUUID(), loan, customer, dpd, overdue);
        ConsumerRecord<String, String> rec = new ConsumerRecord<>("meridian.lending.v1", 0, 0L, loan.toString(), payload);
        rec.headers().add("ce_type", "lending.loan.delinquency_changed".getBytes(StandardCharsets.UTF_8));
        rec.headers().add("ce_tenantid", TENANT.getBytes(StandardCharsets.UTF_8));
        rec.headers().add("ce_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        listener.onEvent(rec);
    }

    private String openBody(UUID loan, int dpd, String overdue, String total) {
        return """
                {"customer_id":"%s","exposure_type":"LOAN","exposure_id":"%s","days_past_due":%d,"overdue_amount":"%s",
                 "total_outstanding":"%s","currency":"EUR"}""".formatted(customer, loan, dpd, overdue, total);
    }

    private static String step(String strategy, int no, int dpd, String type, String queueId) {
        return "{\"collection_strategy_id\":\"" + strategy + "\",\"step_no\":" + no + ",\"trigger_dpd\":" + dpd + ",\"action_type\":\"" + type + "\""
                + (queueId == null ? "" : ",\"queue_id\":\"" + queueId + "\"")
                + (type.equals("SMS") ? ",\"template_code\":\"ARREARS_SMS_1\"" : "") + "}";
    }

    private String caseOf(UUID exposure) throws Exception {
        JsonNode data = get("/v1/collections/cases?exposure_id=" + exposure).get("data");
        assertThat(data).hasSize(1);
        return data.get(0).get("id").asString();
    }

    private <T> T asTenant(Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", body);
    }

    private int events(String type, String aggregateId) throws Exception {
        return asTenant(() -> jdbc.sql("""
                select count(*) from outbox where tenant_id = ?::uuid and event_type = ?
                  and (?::uuid is null or aggregate_id = ?::uuid)""")
                .params(TENANT, type, aggregateId, aggregateId).query(Integer.class).single());
    }

    private void expect(HttpResponse<String> r, int status, String code) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        if (code != null) {
            assertThat(json.readTree(r.body()).get("code").asString()).as(r.body()).isEqualTo(code);
        }
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), user, TENANT);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, "ops", TENANT);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String user) throws Exception {
        return send(method, path, body, "POST".equals(method) ? UUID.randomUUID().toString() : null, user, TENANT);
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String user, String tenant)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        if ("PATCH".equals(method)) {
            b.header("If-Match", "\"1\"");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unused")
    private static List<String> ids(JsonNode data) {
        List<String> out = new ArrayList<>();
        data.forEach(d -> out.add(d.get("id").asString()));
        return out;
    }
}
