package io.meridian.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.crm.application.TimelineService;
import io.meridian.crm.domain.Redactor;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * crm-service end to end over HTTP against PostgreSQL 18 with row-level security. ledger-service (redress
 * postings) and reference-data-service (holiday calendars) are stubbed through ServiceClient.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.crm.timeline.consumer-enabled=false",
                "meridian.crm.redress.ledger-id=0199a000-0000-7000-8000-00000000ad01",
                "meridian.crm.redress.funding-account-id=0199a000-0000-7000-8000-00000000ad02"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrmFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000d1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000d2";
    static final String ALICE = "0199a000-0000-7000-8000-00000000a001";
    static final String BOB = "0199a000-0000-7000-8000-00000000a002";
    static final String AGENT1 = "0199a000-0000-7000-8000-00000000f001";
    static final String AGENT2 = "0199a000-0000-7000-8000-00000000f002";
    static final String HOLIDAYS = "0199a000-0000-7000-8000-00000000c001";
    static final String CUSTOMER_ACCOUNT = "0199a000-0000-7000-8000-00000000ac01";
    static final String FUNDING = "0199a000-0000-7000-8000-00000000ad02";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TimelineService timelineService;

    @MockitoBean
    ServiceClient serviceClient;

    @Autowired
    io.meridian.web.time.BusinessDates businessDates;

    final HttpClient http = HttpClient.newHttpClient();
    final AtomicBoolean ledgerDown = new AtomicBoolean(false);
    final List<JsonNode> postings = new ArrayList<>();

    String casePolicy;
    String paymentsPolicy;
    String supportQueue;
    String escalationQueue;
    String cardCase;
    String article;

    @BeforeEach
    void stubOtherServices() {
        when(serviceClient.get(eq("reference-data-service"), startsWith("/v1/reference/holidays"))).thenAnswer(inv ->
                json.readTree("{\"data\":[{\"holiday_date\":\"2026-12-25\",\"is_half_day\":false}],\"has_more\":false}"));
        when(serviceClient.post(eq("ledger-service"), eq("/v1/journal-entries"), any(), anyString())).thenAnswer(inv -> {
            if (ledgerDown.get()) {
                throw new ApiException(422, "account_frozen", "ledger-service: credit account is frozen");
            }
            JsonNode body = json.valueToTree(inv.getArgument(2));
            postings.add(body);
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"entry_count\":2}");
        });
    }

    // ------------------------------------------------------------------ SLA policies and queues

    @Test
    @Order(1)
    void slaPoliciesAndQueues() throws Exception {
        assertThat(code(post("/v1/servicing/sla-policies", """
                {"name":"bad","applies_to":"CASE","first_response_minutes":0,"resolution_minutes":10,
                 "business_day_start":"25:00"}""", 422))).isEqualTo("invalid_sla_policy");
        assertThat(code(post("/v1/servicing/sla-policies", """
                {"name":"bad","applies_to":"CASE","complaint_category":"FEES_AND_CHARGES","first_response_minutes":10,
                 "resolution_minutes":20}""", 422))).isEqualTo("invalid_sla_policy");
        assertThat(code(post("/v1/servicing/sla-policies", """
                {"name":"bad","applies_to":"CASE","first_response_minutes":10,"resolution_minutes":20,
                 "time_zone":"Mars/Olympus","working_days":[1,8]}""", 422))).isEqualTo("invalid_sla_policy");
        casePolicy = post("/v1/servicing/sla-policies", """
                {"name":"Cards 24x7","applies_to":"CASE","case_type":"CARD_ISSUE","first_response_minutes":60,
                 "resolution_minutes":480,"business_hours_only":false,
                 "escalation_rules":[{"at_percent":100,"priority":"URGENT"}]}""", 201).get("id").asString();
        JsonNode payments = post("/v1/servicing/sla-policies", """
                {"name":"Payments business hours","applies_to":"CASE","case_type":"PAYMENT_ISSUE",
                 "first_response_minutes":120,"resolution_minutes":960,"business_day_start":"09:00",
                 "business_day_end":"17:00","time_zone":"Europe/London","holiday_calendar_id":"%s"}""".formatted(HOLIDAYS), 201);
        paymentsPolicy = payments.get("id").asString();
        assertThat(payments.get("pause_on_pending_customer").asBoolean()).isTrue();
        String throwaway = post("/v1/servicing/sla-policies", """
                {"name":"Tmp","applies_to":"COMPLAINT","first_response_minutes":10,"resolution_minutes":20,"is_active":false}""",
                201).get("id").asString();
        assertThat(send("DELETE", "/v1/servicing/sla-policies/" + throwaway, null, null, TENANT, "maker").statusCode()).isEqualTo(204);

        assertThat(code(post("/v1/servicing/work-queues", """
                {"code":"SUPPORT","name":"Support","routing":"SKILLS_BASED"}""", 422))).isEqualTo("invalid_work_queue");
        assertThat(code(post("/v1/servicing/work-queues", """
                {"code":"bad code","name":"Support"}""", 422))).isEqualTo("invalid_work_queue");
        String body = """
                {"code":"SUPPORT","name":"Card support","routing":"SKILLS_BASED","required_skills":["cards"],
                 "max_items_per_agent":1}""";
        HttpResponse<String> created = send("POST", "/v1/servicing/work-queues", body, "queue-support", TENANT, "maker");
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode queue = json.readTree(created.body());
        supportQueue = queue.get("id").asString();
        assertThat(queue.get("required_skills").get(0).asString()).isEqualTo("CARDS");
        HttpResponse<String> replay = send("POST", "/v1/servicing/work-queues", body, "queue-support", TENANT, "maker");
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(supportQueue);
        escalationQueue = post("/v1/servicing/work-queues", """
                {"code":"ESCALATIONS","name":"Team leaders","routing":"ROUND_ROBIN"}""", 201).get("id").asString();
        assertThat(code(patch("/v1/servicing/work-queues/" + supportQueue, "{\"code\":\"OTHER\"}", 1, 422)))
                .isEqualTo("immutable_field");
    }

    // ------------------------------------------------------------------ cases, work items, comments

    @Test
    @Order(2)
    void caseLifecycleWithRoutingAndComments() throws Exception {
        JsonNode c = post("/v1/servicing/cases", """
                {"customer_id":"%s","case_type":"CARD_ISSUE","subject":"Card declined abroad","channel":"PHONE",
                 "description":"My card was declined at a shop in Spain","work_queue_id":"%s","tags":["Travel","travel"]}"""
                .formatted(ALICE, supportQueue), 201);
        cardCase = c.get("id").asString();
        assertThat(c.get("case_number").asString()).startsWith("CAS-");
        assertThat(c.get("status").asString()).isEqualTo("NEW");
        assertThat(c.get("priority").asString()).isEqualTo("MEDIUM");
        assertThat(c.get("tags")).hasSize(1);
        assertThat(c.get("sla_policy_id").asString()).isEqualTo(casePolicy);
        OffsetDateTime createdAt = ts(c, "created_at");
        assertThat(Duration.between(createdAt, ts(c, "resolution_due_at")).toMinutes()).isBetween(479L, 480L);
        assertThat(Duration.between(createdAt, ts(c, "first_response_due_at")).toMinutes()).isBetween(59L, 60L);

        JsonNode items = get("/v1/servicing/work-items?subject_id=" + cardCase).get("data");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("status").asString()).isEqualTo("QUEUED");
        assertThat(ts(items.get(0), "due_at")).isEqualTo(ts(c, "resolution_due_at"));

        // Skills routing: the queue requires CARDS.
        assertThat(code(post("/v1/servicing/work-items/next", """
                {"work_queue_id":"%s","assignee_id":"%s","skills":["loans"]}""".formatted(supportQueue, AGENT1), 404)))
                .isEqualTo("not_found");
        JsonNode pulled = post("/v1/servicing/work-items/next", """
                {"work_queue_id":"%s","assignee_id":"%s","skills":["Cards","Loans"]}""".formatted(supportQueue, AGENT1), 200);
        assertThat(pulled.get("subject_id").asString()).isEqualTo(cardCase);
        assertThat(pulled.get("status").asString()).isEqualTo("ASSIGNED");
        assertThat(pulled.get("assigned_to").asString()).isEqualTo(AGENT1);

        String second = post("/v1/servicing/cases", """
                {"customer_id":"%s","case_type":"CARD_ISSUE","subject":"PIN reminder","channel":"WEB","work_queue_id":"%s",
                 "priority":"HIGH"}""".formatted(BOB, supportQueue), 201).get("id").asString();
        assertThat(code(post("/v1/servicing/work-items/next", """
                {"work_queue_id":"%s","assignee_id":"%s","skills":["cards"]}""".formatted(supportQueue, AGENT1), 409)))
                .isEqualTo("agent_at_capacity");
        JsonNode agent2Item = post("/v1/servicing/work-items/next", """
                {"assignee_id":"%s","skills":["cards"]}""".formatted(AGENT2), 200);
        assertThat(agent2Item.get("subject_id").asString()).isEqualTo(second);
        JsonNode stats = get("/v1/servicing/work-queues/" + supportQueue + "/stats");
        assertThat(stats.get("by_status").get("ASSIGNED").asInt()).isEqualTo(2);
        assertThat(stats.get("assignees")).hasSize(2);
        assertThat(stats.get("assignees").get(0).get("utilisation_percent").asInt()).isEqualTo(100);
        JsonNode released = post("/v1/servicing/work-items/" + agent2Item.get("id").asString() + "/release",
                "{\"reason\":\"Shift ended\"}", 200);
        assertThat(released.get("status").asString()).isEqualTo("QUEUED");
        assertThat(released.get("release_count").asInt()).isEqualTo(1);
        assertThat(released.hasNonNull("assigned_to")).isFalse();
        assertThat(code(post("/v1/servicing/work-items/" + agent2Item.get("id").asString() + "/complete", null, 409)))
                .isEqualTo("invalid_transition");

        // Case lifecycle.
        String assignKey = key();
        String assignBody = "{\"assigned_to\":\"" + AGENT1 + "\"}";
        HttpResponse<String> assigned = send("POST", "/v1/servicing/cases/" + cardCase + "/assign", assignBody, assignKey, TENANT, "maker");
        assertThat(assigned.statusCode()).isEqualTo(200);
        assertThat(json.readTree(assigned.body()).get("status").asString()).isEqualTo("OPEN");
        HttpResponse<String> again = send("POST", "/v1/servicing/cases/" + cardCase + "/assign", assignBody, assignKey, TENANT, "maker");
        assertThat(again.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(code(post("/v1/servicing/cases/" + cardCase + "/close", "{}", 409))).isEqualTo("invalid_transition");

        JsonNode pending = post("/v1/servicing/cases/" + cardCase + "/pending-customer",
                "{\"message\":\"Please confirm the merchant name and amount.\"}", 200);
        assertThat(pending.get("status").asString()).isEqualTo("PENDING_CUSTOMER");
        assertThat(pending.hasNonNull("sla_paused_at")).isTrue();
        assertThat(pending.hasNonNull("first_responded_at")).isTrue();
        assertThat(code(post("/v1/servicing/cases/" + cardCase + "/comments", """
                {"body":"secret","visibility":"INTERNAL","author_type":"CUSTOMER"}""", 422))).isEqualTo("invalid_comment");
        post("/v1/servicing/cases/" + cardCase + "/comments", """
                {"body":"It was 45 EUR at Zara Madrid","visibility":"CUSTOMER","author_type":"CUSTOMER","author_id":"%s"}"""
                .formatted(ALICE), 200);
        JsonNode resumed = get("/v1/servicing/cases/" + cardCase);
        assertThat(resumed.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(resumed.hasNonNull("sla_paused_at")).isFalse();
        assertThat(ts(resumed, "resolution_due_at")).isAfterOrEqualTo(ts(c, "resolution_due_at"));
        assertThat(get("/v1/servicing/cases/" + cardCase + "/comments").get("data")).hasSize(2);

        JsonNode resolved = post("/v1/servicing/cases/" + cardCase + "/resolve", """
                {"resolution_code":"MERCHANT_BLOCK","resolution_summary":"Merchant category was blocked; unblocked."}""", 200);
        assertThat(resolved.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(resolved.get("sla_breached").asBoolean()).isFalse();
        assertThat(get("/v1/servicing/work-items/" + pulled.get("id").asString()).get("status").asString()).isEqualTo("DONE");
        assertThat(code(post("/v1/servicing/cases/" + cardCase + "/close", "{\"csat_score\":9}", 422))).isEqualTo("invalid_csat");
        JsonNode closed = post("/v1/servicing/cases/" + cardCase + "/close", "{\"csat_score\":5}", 200);
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("csat_score").asInt()).isEqualTo(5);
        assertThat(code(patch("/v1/servicing/cases/" + cardCase, "{\"subject\":\"x\"}", closed.get("row_version").asInt(), 409)))
                .isEqualTo("case_closed");
        JsonNode reopened = post("/v1/servicing/cases/" + cardCase + "/reopen", "{\"reason\":\"Declined again\"}", 200);
        assertThat(reopened.get("status").asString()).isEqualTo("REOPENED");
        assertThat(reopened.get("reopened_count").asInt()).isEqualTo(1);
        assertThat(reopened.hasNonNull("csat_score")).isFalse();
        assertThat(get("/v1/servicing/work-items?subject_id=" + cardCase + "&status=QUEUED").get("data")).hasSize(1);
        assertThat(code(patch("/v1/servicing/cases/" + cardCase, "{\"customer_id\":\"" + BOB + "\"}",
                reopened.get("row_version").asInt(), 422))).isEqualTo("immutable_field");
        assertThat(outbox("servicing.service_case.created", cardCase)).isEqualTo(1);
        assertThat(outbox("servicing.case_comment.added", null)).isGreaterThanOrEqualTo(2);
    }

    // ------------------------------------------------------------------ SLA monitor, escalation, business hours

    @Test
    @Order(3)
    void slaMonitorEscalatesAndBusinessHoursClock() throws Exception {
        String late = post("/v1/servicing/cases", """
                {"customer_id":"%s","case_type":"CARD_ISSUE","subject":"Card not received","channel":"EMAIL","priority":"MEDIUM"}"""
                .formatted(ALICE), 201).get("id").asString();
        asTenant(() -> jdbc.sql("""
                update service_case set created_at = now() - interval '10 hours',
                  first_response_due_at = now() - interval '9 hours', resolution_due_at = now() - interval '2 hours'
                where id = ?::uuid""").param(late).update());
        JsonNode check = post("/v1/servicing/cases/sla-check", null, 200);
        assertThat(check.get("breached").toString()).contains(late);
        assertThat(check.get("escalated").toString()).contains(late);
        JsonNode escalated = get("/v1/servicing/cases/" + late);
        assertThat(escalated.get("sla_breached").asBoolean()).isTrue();
        assertThat(escalated.get("status").asString()).isEqualTo("ESCALATED");
        assertThat(escalated.get("priority").asString()).isEqualTo("URGENT");
        assertThat(escalated.get("escalation_level").asInt()).isEqualTo(1);
        assertThat(outbox("servicing.service_case.sla_breached", late)).isEqualTo(1);
        JsonNode again = post("/v1/servicing/cases/sla-check", null, 200);
        assertThat(again.get("breached").toString()).doesNotContain(late);
        assertThat(again.get("escalated").toString()).doesNotContain(late);

        JsonNode manual = post("/v1/servicing/cases/" + late + "/escalate", """
                {"work_queue_id":"%s","reason":"Customer called three times"}""".formatted(escalationQueue), 200);
        assertThat(manual.get("escalation_level").asInt()).isEqualTo(2);
        assertThat(manual.get("work_queue_id").asString()).isEqualTo(escalationQueue);
        assertThat(get("/v1/servicing/work-queues/" + escalationQueue + "/items").get("data").get(0).get("subject_id").asString())
                .isEqualTo(late);
        JsonNode cancelled = post("/v1/servicing/cases/" + late + "/cancel", "{\"reason\":\"Duplicate of an earlier case\"}", 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(get("/v1/servicing/work-items?subject_id=" + late + "&status=CANCELLED").get("data")).hasSize(1);
        assertThat(code(post("/v1/servicing/cases/" + late + "/comments", "{\"body\":\"x\",\"visibility\":\"INTERNAL\"}", 409)))
                .isEqualTo("case_closed");

        // Business-hours policy: the 16 business-hour resolution target lands inside London business hours.
        JsonNode pay = post("/v1/servicing/cases", """
                {"customer_id":"%s","case_type":"PAYMENT_ISSUE","subject":"Transfer missing","channel":"WEB","priority":"LOW"}"""
                .formatted(BOB), 201);
        assertThat(pay.get("sla_policy_id").asString()).isEqualTo(paymentsPolicy);
        ZonedDateTime due = ts(pay, "resolution_due_at").atZoneSameInstant(ZoneId.of("Europe/London"));
        assertThat(due.getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        assertThat(due.toLocalTime()).isBetween(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(17, 0));
        assertThat(Duration.between(ts(pay, "created_at"), ts(pay, "resolution_due_at")).toHours()).isGreaterThanOrEqualTo(16);
        verify(serviceClient, atLeastOnce()).get(eq("reference-data-service"), contains(HOLIDAYS));
    }

    // ------------------------------------------------------------------ complaints and redress

    @Test
    @Order(4)
    void complaintsWithRegulatoryTimelinesAndRedress() throws Exception {
        JsonNode note = post("/v1/servicing/customer-notes", """
                {"customer_id":"%s","body":"Recently bereaved; speak slowly and offer a call back.","category":"vulnerability"}"""
                .formatted(BOB), 201);
        assertThat(note.get("category").asString()).isEqualTo("VULNERABILITY");
        assertThat(note.get("is_pinned").asBoolean()).isTrue();

        JsonNode cmp = post("/v1/servicing/complaints", """
                {"customer_id":"%s","category":"FEES_AND_CHARGES","channel":"PHONE","summary":"Unfair overdraft fees",
                 "details":"I was charged 5 fees in a month"}""".formatted(BOB), 201);
        String complaint = cmp.get("id").asString();
        assertThat(cmp.get("complaint_number").asString()).startsWith("CMP-");
        assertThat(cmp.get("status").asString()).isEqualTo("RECEIVED");
        assertThat(cmp.get("country").asString()).isEqualTo("GB");
        assertThat(cmp.get("is_vulnerable_customer").asBoolean()).isTrue();
        OffsetDateTime received = ts(cmp, "received_at");
        assertThat(ts(cmp, "final_response_due_at")).isEqualTo(received.plusDays(56));
        assertThat(ts(cmp, "acknowledgement_due_at").getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        assertThat(Duration.between(received, ts(cmp, "acknowledgement_due_at")).toDays()).isBetween(3L, 5L);
        JsonNode psd = post("/v1/servicing/complaints", """
                {"customer_id":"%s","category":"PAYMENTS","channel":"WEB","summary":"Payment delayed"}""".formatted(ALICE), 201);
        assertThat(Duration.between(ts(psd, "received_at"), ts(psd, "final_response_due_at")).toDays()).isBetween(19L, 23L);

        assertThat(code(post("/v1/servicing/complaints/" + complaint + "/investigate", null, 409))).isEqualTo("invalid_transition");
        assertThat(code(post("/v1/servicing/complaints/" + complaint + "/escalate", "{\"escalated_to\":\"FOS\"}", 422)))
                .isEqualTo("escalation_premature");
        assertThat(post("/v1/servicing/complaints/" + complaint + "/acknowledge", "{\"message\":\"We are on it\"}", 200)
                .get("status").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(post("/v1/servicing/complaints/" + complaint + "/assign", "{\"assigned_to\":\"" + AGENT1 + "\"}", 200)
                .get("assigned_to").asString()).isEqualTo(AGENT1);
        post("/v1/servicing/complaints/" + complaint + "/investigate", null, 200);
        JsonNode proposed = post("/v1/servicing/complaints/" + complaint + "/propose-resolution", """
                {"outcome":"UPHELD","root_cause":"Fee cap not applied","root_cause_category":"SYSTEM_ERROR",
                 "resolution_summary":"We will refund the excess fees."}""", 200);
        assertThat(proposed.get("status").asString()).isEqualTo("RESOLUTION_PROPOSED");

        // Redress: validation, four eyes, failed then successful ledger posting.
        assertThat(code(post("/v1/servicing/complaint-redress", """
                {"complaint_id":"%s","redress_type":"FEE_REVERSAL"}""".formatted(complaint), 422))).isEqualTo("invalid_redress");
        String redress = post("/v1/servicing/complaint-redress", """
                {"complaint_id":"%s","redress_type":"FEE_REVERSAL","amount":"50.00","currency":"GBP","target_account_id":"%s",
                 "description":"Refund of 2 overdraft fees"}""".formatted(complaint, CUSTOMER_ACCOUNT), 201).get("id").asString();
        assertThat(code(post("/v1/servicing/complaint-redress/" + redress + "/pay", null, 409))).isEqualTo("invalid_transition");
        assertThat(code(send("POST", "/v1/servicing/complaint-redress/" + redress + "/approve", "{}", key(), TENANT, "maker")))
                .isEqualTo("four_eyes_violation");
        JsonNode approved = json.readTree(send("POST", "/v1/servicing/complaint-redress/" + redress + "/approve",
                "{\"comment\":\"Fees confirmed\"}", key(), TENANT, "checker").body());
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("approved_by").asString()).isEqualTo("checker");
        ledgerDown.set(true);
        JsonNode failed = post("/v1/servicing/complaint-redress/" + redress + "/pay", null, 200);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("failure_reason").asString()).contains("account_frozen");
        assertThat(outbox("servicing.complaint_redress.failed", redress)).isEqualTo(1);
        ledgerDown.set(false);
        postings.clear();
        JsonNode paid = post("/v1/servicing/complaint-redress/" + redress + "/pay", null, 200);
        assertThat(paid.get("status").asString()).isEqualTo("PAID");
        assertThat(paid.hasNonNull("journal_entry_id")).isTrue();
        assertThat(postings).hasSize(1);
        JsonNode entries = postings.getFirst().get("entries");
        assertThat(entries.get(0).get("direction").asString()).isEqualTo("DEBIT");
        assertThat(entries.get(0).get("ledger_account_id").asString()).isEqualTo(FUNDING);
        assertThat(entries.get(1).get("ledger_account_id").asString()).isEqualTo(CUSTOMER_ACCOUNT);
        assertThat(postings.getFirst().get("source_type").asString()).isEqualTo("COMPLAINT_REDRESS");

        String apology = post("/v1/servicing/complaint-redress", """
                {"complaint_id":"%s","redress_type":"APOLOGY","description":"Written apology"}""".formatted(complaint), 201)
                .get("id").asString();
        send("POST", "/v1/servicing/complaint-redress/" + apology + "/approve", "{}", key(), TENANT, "checker");
        assertThat(post("/v1/servicing/complaint-redress/" + apology + "/pay", null, 200).get("status").asString()).isEqualTo("PAID");
        assertThat(postings).hasSize(1);

        assertThat(code(post("/v1/servicing/complaints/" + complaint + "/close", null, 409))).isEqualTo("invalid_transition");
        JsonNode resolved = post("/v1/servicing/complaints/" + complaint + "/final-response",
                "{\"final_response_document_id\":\"" + UUID.randomUUID() + "\"}", 200);
        assertThat(resolved.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(resolved.hasNonNull("final_response_at")).isTrue();
        assertThat(new java.math.BigDecimal(resolved.get("redress_amount").asString())).isEqualByComparingTo("50");
        assertThat(resolved.get("redress_currency").asString()).isEqualTo("GBP");
        String extra = post("/v1/servicing/complaint-redress", """
                {"complaint_id":"%s","redress_type":"GOODWILL_PAYMENT","amount":"20","currency":"GBP","target_account_id":"%s"}"""
                .formatted(complaint, CUSTOMER_ACCOUNT), 201).get("id").asString();
        assertThat(code(post("/v1/servicing/complaints/" + complaint + "/close", null, 422))).isEqualTo("redress_outstanding");
        post("/v1/servicing/complaint-redress/" + extra + "/reject", "{\"reason\":\"Not warranted\"}", 200);
        assertThat(get("/v1/servicing/complaints/" + complaint + "/redress").get("data")).hasSize(3);
        assertThat(post("/v1/servicing/complaints/" + complaint + "/close", null, 200).get("status").asString()).isEqualTo("CLOSED");
        assertThat(code(post("/v1/servicing/complaints/" + complaint + "/escalate", "{\"escalated_to\":\"FOS\"}", 409)))
                .isEqualTo("invalid_transition");

        // Conversion from a case, withdrawal.
        String caseId = post("/v1/servicing/cases", """
                {"customer_id":"%s","case_type":"ACCOUNT_MAINTENANCE","subject":"Statement wrong","channel":"BRANCH"}"""
                .formatted(ALICE), 201).get("id").asString();
        JsonNode converted = post("/v1/servicing/cases/" + caseId + "/convert-to-complaint", "{\"category\":\"SERVICE_QUALITY\"}", 200);
        assertThat(converted.get("service_case_id").asString()).isEqualTo(caseId);
        assertThat(converted.get("channel").asString()).isEqualTo("BRANCH");
        assertThat(get("/v1/servicing/cases/" + caseId).get("complaint_id").asString()).isEqualTo(converted.get("id").asString());
        assertThat(code(post("/v1/servicing/cases/" + caseId + "/convert-to-complaint", "{\"category\":\"OTHER\"}", 409)))
                .isEqualTo("already_converted");
        JsonNode withdrawn = post("/v1/servicing/complaints/" + converted.get("id").asString() + "/withdraw",
                "{\"reason\":\"Customer satisfied after call\"}", 200);
        assertThat(withdrawn.get("status").asString()).isEqualTo("CLOSED");
        assertThat(withdrawn.get("outcome").asString()).isEqualTo("WITHDRAWN");

        // Deadline monitor: one breached, one at risk; both alerts fire once.
        String overdue = post("/v1/servicing/complaints", """
                {"customer_id":"%s","category":"LENDING","channel":"LETTER","summary":"Loan arrears handling"}""".formatted(ALICE), 201)
                .get("id").asString();
        String risky = post("/v1/servicing/complaints", """
                {"customer_id":"%s","category":"CARDS","channel":"EMAIL","summary":"Card fee"}""".formatted(ALICE), 201)
                .get("id").asString();
        asTenant(() -> {
            jdbc.sql("""
                    update complaint set received_at = now() - interval '60 days', final_response_due_at = now() - interval '4 days'
                    where id = ?::uuid""").param(overdue).update();
            return jdbc.sql("""
                    update complaint set received_at = now() - interval '50 days', final_response_due_at = now() + interval '6 days'
                    where id = ?::uuid""").param(risky).update();
        });
        JsonNode deadline = post("/v1/servicing/complaints/deadline-check", null, 200);
        assertThat(deadline.get("breached").toString()).contains(overdue);
        assertThat(deadline.get("at_risk").toString()).contains(risky);
        JsonNode deadline2 = post("/v1/servicing/complaints/deadline-check", null, 200);
        assertThat(deadline2.get("breached").toString()).doesNotContain(overdue);
        assertThat(deadline2.get("at_risk").toString()).doesNotContain(risky);
        assertThat(get("/v1/servicing/complaints/" + overdue).hasNonNull("deadline_breached_at")).isTrue();
        // After the deadline the complainant may go to the ombudsman before a final response.
        assertThat(post("/v1/servicing/complaints/" + overdue + "/escalate",
                "{\"escalated_to\":\"Financial Ombudsman Service\",\"ombudsman_reference\":\"FOS-123\"}", 200)
                .get("status").asString()).isEqualTo("ESCALATED_TO_OMBUDSMAN");

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        JsonNode report = get("/v1/servicing/complaints/reports/regulatory?from=" + today.minusDays(90) + "&to=" + today
                + "&country=GB");
        assertThat(report.get("received").asInt()).isGreaterThanOrEqualTo(5);
        assertThat(report.get("by_category").get("FEES_AND_CHARGES").asInt()).isEqualTo(1);
        assertThat(report.get("redress_paid").get("GBP").asString()).isEqualTo("50.00");
        assertThat(new java.math.BigDecimal(report.get("upheld_rate_percent").asString())).isEqualByComparingTo("100");
        assertThat(report.get("escalated_to_ombudsman").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ interactions, notes, timeline

    @Test
    @Order(5)
    void interactionsNotesAndTimeline() throws Exception {
        assertThat(code(post("/v1/servicing/interactions", """
                {"customer_id":"%s","interaction_type":"CALL_INBOUND","direction":"OUTBOUND","channel":"PHONE",
                 "started_at":"2026-10-01T10:00:00Z"}""".formatted(ALICE), 422))).isEqualTo("invalid_interaction");
        JsonNode call = post("/v1/servicing/interactions", """
                {"customer_id":"%s","interaction_type":"CALL_INBOUND","direction":"INBOUND","channel":"PHONE",
                 "subject":"Card problem","summary":"I am furious, this is unacceptable, my card was declined again",
                 "started_at":"2026-10-01T10:00:00Z","ended_at":"2026-10-01T10:07:30Z"}""".formatted(ALICE), 201);
        assertThat(call.get("sentiment").asString()).isEqualTo("VERY_NEGATIVE");
        assertThat(call.get("intent").asString()).isEqualTo("COMPLAINT");
        assertThat(call.get("duration_seconds").asInt()).isEqualTo(450);

        String fresh = post("/v1/servicing/cases", """
                {"customer_id":"%s","case_type":"INQUIRY","subject":"Branch hours","channel":"WEB"}""".formatted(ALICE), 201)
                .get("id").asString();
        post("/v1/servicing/interactions", """
                {"customer_id":"%s","interaction_type":"CALL_OUTBOUND","direction":"OUTBOUND","channel":"PHONE",
                 "service_case_id":"%s","summary":"Explained the opening hours, thanks","started_at":"%s"}"""
                .formatted(ALICE, fresh, OffsetDateTime.now(ZoneOffset.UTC)), 201);
        JsonNode responded = get("/v1/servicing/cases/" + fresh);
        assertThat(responded.hasNonNull("first_responded_at")).isTrue();
        assertThat(responded.get("status").asString()).isEqualTo("OPEN");
        assertThat(code(post("/v1/servicing/interactions", """
                {"customer_id":"%s","interaction_type":"CHAT","direction":"INBOUND","channel":"CHAT",
                 "service_case_id":"%s","started_at":"2026-10-01T10:00:00Z"}""".formatted(BOB, fresh), 422)))
                .isEqualTo("invalid_interaction");
        assertThat(code(post("/v1/servicing/customer-notes", """
                {"customer_id":"%s","body":"x","category":"GOSSIP"}""".formatted(ALICE), 422))).isEqualTo("invalid_note");

        JsonNode timeline = get("/v1/servicing/customers/" + ALICE + "/timeline?limit=200").get("data");
        assertThat(timeline.toString()).contains("servicing.interaction.logged", "servicing.service_case.created",
                "servicing.complaint.received");
        assertThat(get("/v1/servicing/customers/" + ALICE + "/timeline?event_type=servicing.interaction.logged").get("data"))
                .hasSize(2);
        boolean projected = TenantContext.callAs(UUID.fromString(TENANT), "test", () -> timelineService.project("ce-1",
                "payments.payment.failed", json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"customer_id\":\"" + ALICE
                        + "\",\"status\":\"FAILED\",\"amount\":\"10.00\",\"currency\":\"GBP\"}")));
        assertThat(projected).isTrue();
        boolean duplicate = TenantContext.callAs(UUID.fromString(TENANT), "test", () -> timelineService.project("ce-1",
                "payments.payment.failed", json.readTree("{\"customer_id\":\"" + ALICE + "\"}")));
        assertThat(duplicate).isFalse();
        JsonNode summary = get("/v1/servicing/customers/" + ALICE + "/timeline/summary?from=2026-01-01T00:00:00Z");
        assertThat(summary.get("by_domain").get("servicing").asInt()).isGreaterThan(3);
        assertThat(summary.get("by_domain").get("payments").asInt()).isEqualTo(1);
        assertThat(summary.get("open_items").get("service_cases").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(summary.hasNonNull("last_interaction")).isTrue();
        assertThat(summary.get("highlights")).isNotEmpty();
    }

    // ------------------------------------------------------------------ knowledge, canned responses, copilot

    @Test
    @Order(6)
    void knowledgeCannedResponsesAndCopilot() throws Exception {
        assertThat(code(post("/v1/servicing/knowledge-articles", """
                {"slug":"Bad Slug","title":"t","body":"b"}""", 422))).isEqualTo("invalid_article");
        JsonNode a = post("/v1/servicing/knowledge-articles", """
                {"slug":"card-declined-abroad","title":"Card declined abroad",
                 "body":"If your card is declined abroad, check that travel use is enabled in the app and that the merchant category is not blocked.",
                 "tags":["Cards","Travel"],"case_types":["card_issue"]}""", 201);
        article = a.get("id").asString();
        assertThat(a.get("status").asString()).isEqualTo("DRAFT");
        assertThat(a.get("case_types").get(0).asString()).isEqualTo("CARD_ISSUE");
        post("/v1/servicing/knowledge-articles", """
                {"slug":"loan-holidays","title":"Loan payment holidays","body":"Payment holidays on loans are available...",
                 "case_types":["LOAN_SERVICING"]}""", 201);
        JsonNode submitted = post("/v1/servicing/knowledge-articles/" + article + "/submit-review", null, 200);
        assertThat(submitted.get("submitted_by").asString()).isEqualTo("maker");
        assertThat(code(patch("/v1/servicing/knowledge-articles/" + article, "{\"title\":\"x\"}",
                submitted.get("row_version").asInt(), 409))).isEqualTo("article_not_editable");
        assertThat(code(post("/v1/servicing/knowledge-articles/" + article + "/feedback", "{\"helpful\":true}", 409)))
                .isEqualTo("invalid_transition");
        assertThat(code(post("/v1/servicing/knowledge-articles/" + article + "/publish", null, 422))).isEqualTo("four_eyes_violation");
        // review dates and the {{today}} placeholder use the tenant's business date
        when(serviceClient.get(eq("business-day-service"), startsWith("/v1/operations/business-days")))
                .thenReturn(json.readTree("{\"data\":[{\"business_date\":\"2031-05-06\"}]}"));
        businessDates.invalidate();
        JsonNode published = json.readTree(send("POST", "/v1/servicing/knowledge-articles/" + article + "/publish", null, key(),
                TENANT, "checker").body());
        assertThat(published.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(published.get("reviewed_by").asString()).isEqualTo("checker");
        assertThat(published.get("review_due").asString()).isEqualTo("2032-05-06");
        assertThat(post("/v1/servicing/knowledge-articles/" + article + "/feedback", "{\"helpful\":true}", 200)
                .get("helpful_count").asInt()).isEqualTo(1);
        JsonNode hits = get("/v1/servicing/knowledge-articles/search?q=declined%20card").get("data");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).get("id").asString()).isEqualTo(article);
        assertThat(get("/v1/servicing/knowledge-articles/search?q=card&case_type=LOAN_SERVICING").get("data")).isEmpty();
        assertThat(send("GET", "/v1/servicing/knowledge-articles/search?q=x", null, null, TENANT, "r").statusCode()).isEqualTo(400);

        // Canned response rendering.
        assertThat(code(post("/v1/servicing/canned-responses", """
                {"code":"BAD","title":"t","body":"Hello {{ name"}""", 422))).isEqualTo("invalid_template");
        String canned = post("/v1/servicing/canned-responses", """
                {"code":"CASE_UPDATE","title":"Case update",
                 "body":"Dear {{customer_name}}, your case {{case.case_number}} ({{subject}}) is {{case.status}}. {{unknown}}"}""", 201)
                .get("id").asString();
        JsonNode rendered = post("/v1/servicing/canned-responses/" + canned + "/render", """
                {"service_case_id":"%s","values":{"customer_name":"Alice"}}""".formatted(cardCase), 200);
        String caseNumber = get("/v1/servicing/cases/" + cardCase).get("case_number").asString();
        assertThat(rendered.get("rendered").asString()).startsWith("Dear Alice, your case " + caseNumber + " (Card declined abroad) is REOPENED.");
        assertThat(rendered.get("unresolved_placeholders").get(0).asString()).isEqualTo("unknown");
        assertThat(rendered.get("complete").asBoolean()).isFalse();
        String dated = post("/v1/servicing/canned-responses", """
                {"code":"DATED","title":"Dated","body":"As of {{today}}"}""", 201).get("id").asString();
        assertThat(post("/v1/servicing/canned-responses/" + dated + "/render", "{}", 200).get("rendered").asString())
                .isEqualTo("As of 2031-05-06");
        businessDates.invalidate();
        assertThat(code(post("/v1/servicing/canned-responses/" + canned + "/render", """
                {"service_case_id":"%s","customer_id":"%s"}""".formatted(cardCase, BOB), 422))).isEqualTo("customer_mismatch");

        // Copilot.
        String staff = UUID.randomUUID().toString();
        assertThat(code(post("/v1/servicing/copilot", """
                {"staff_user_id":"%s","task":"SUMMARISE_CUSTOMER"}""".formatted(staff), 422))).isEqualTo("invalid_copilot_request");
        JsonNode summary = post("/v1/servicing/copilot", """
                {"staff_user_id":"%s","task":"SUMMARISE_CUSTOMER","customer_id":"%s"}""".formatted(staff, ALICE), 201);
        assertThat(summary.get("output").asString()).startsWith("Customer overview:");
        assertThat(summary.get("output_hash").asString()).isEqualTo(Redactor.sha256(summary.get("output").asString()));
        assertThat(summary.get("model").asString()).isEqualTo("meridian-copilot-stub-1");
        assertThat(summary.get("context_refs")).isNotEmpty();
        assertThat(summary.get("citations")).isNotEmpty();
        JsonNode answer = post("/v1/servicing/copilot", """
                {"staff_user_id":"%s","task":"ANSWER_QUESTION","customer_id":"%s",
                 "prompt":"Why was my card declined abroad? Reply to alice@example.com or +447700900123"}""".formatted(staff, ALICE), 201);
        assertThat(answer.get("prompt").asString()).contains("[EMAIL]", "[PHONE]").doesNotContain("alice@example.com");
        assertThat(answer.get("prompt_hash").asString()).isEqualTo(Redactor.sha256(answer.get("prompt").asString()));
        assertThat(answer.get("guardrail_flags").toString()).contains("PII_REDACTED");
        assertThat(answer.get("output").asString()).contains("Card declined abroad");
        assertThat(answer.get("citations").get(0).get("id").asString()).isEqualTo(article);
        JsonNode s1 = post("/v1/servicing/copilot", """
                {"staff_user_id":"%s","task":"SUMMARISE_CASE","service_case_id":"%s"}""".formatted(staff, cardCase), 201);
        JsonNode s2 = post("/v1/servicing/copilot", """
                {"staff_user_id":"%s","task":"SUMMARISE_CASE","service_case_id":"%s"}""".formatted(staff, cardCase), 201);
        assertThat(s1.get("output_hash").asString()).isEqualTo(s2.get("output_hash").asString());
        assertThat(s1.get("customer_id").asString()).isEqualTo(ALICE);
        JsonNode draft = post("/v1/servicing/copilot", """
                {"staff_user_id":"%s","task":"DRAFT_REPLY","service_case_id":"%s"}""".formatted(staff, cardCase), 201);
        assertThat(draft.get("output").asString()).startsWith("Dear customer").contains(caseNumber);
        JsonNode next = post("/v1/servicing/copilot", """
                {"staff_user_id":"%s","task":"SUGGEST_NEXT_ACTION","service_case_id":"%s"}""".formatted(staff, cardCase), 201);
        assertThat(next.get("output").asString()).startsWith("1. ");

        String sid = draft.get("id").asString();
        assertThat(code(post("/v1/servicing/copilot/" + sid + "/feedback", "{\"feedback\":\"EDITED\"}", 422))).isEqualTo("invalid_feedback");
        JsonNode fb = post("/v1/servicing/copilot/" + sid + "/feedback", """
                {"feedback":"EDITED","edited_output":"Dear Alice, we have fixed it.","comment":"Too formal"}""", 200);
        assertThat(fb.get("edited_output_hash").asString()).isEqualTo(Redactor.sha256("Dear Alice, we have fixed it."));
        assertThat(fb.get("staff_user").asString()).isEqualTo("maker");
        assertThat(get("/v1/servicing/copilot/" + sid + "/feedback").get("data")).hasSize(1);
        JsonNode comment = post("/v1/servicing/cases/" + cardCase + "/comments", """
                {"body":"Dear Alice, we have fixed it.","visibility":"CUSTOMER","author_type":"COPILOT","copilot_session_id":"%s"}"""
                .formatted(sid), 200);
        assertThat(comment.get("copilot_session_id").asString()).isEqualTo(sid);
        assertThat(outbox("servicing.copilot_session.created", null)).isGreaterThanOrEqualTo(5);

        JsonNode v2 = post("/v1/servicing/knowledge-articles/" + article + "/new-version", null, 200);
        assertThat(v2.get("status").asString()).isEqualTo("DRAFT");
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(post("/v1/servicing/knowledge-articles/" + article + "/archive", null, 200).get("status").asString())
                .isEqualTo("ARCHIVED");
        assertThat(get("/v1/servicing/knowledge-articles/search?q=declined%20card").get("data")).isEmpty();
    }

    // ------------------------------------------------------------------ isolation and protocol

    @Test
    @Order(7)
    void tenantIsolationAndProtocolErrors() throws Exception {
        assertThat(send("GET", "/v1/servicing/cases/" + cardCase, null, null, OTHER_TENANT, "x").statusCode()).isEqualTo(404);
        assertThat(send("POST", "/v1/servicing/cases/" + cardCase + "/start", null, key(), OTHER_TENANT, "x").statusCode())
                .isEqualTo(404);
        assertThat(json.readTree(send("GET", "/v1/servicing/cases", null, null, OTHER_TENANT, "x").body()).get("data")).isEmpty();
        assertThat(json.readTree(send("POST", "/v1/servicing/cases/sla-check", null, key(), OTHER_TENANT, "x").body())
                .get("checked").asInt()).isZero();
        assertThat(send("POST", "/v1/servicing/cases/" + cardCase + "/start", null, null, TENANT, "x").statusCode()).isEqualTo(400);
        JsonNode c = get("/v1/servicing/cases/" + cardCase);
        HttpRequest noIfMatch = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/servicing/cases/" + cardCase))
                .header("X-Tenant-Id", TENANT).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"subtype\":\"x\"}")).build();
        assertThat(http.send(noIfMatch, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        assertThat(code(patch("/v1/servicing/cases/" + cardCase, "{\"subtype\":\"x\"}", c.get("row_version").asInt() + 5, 409)))
                .isEqualTo("version_conflict");
        JsonNode patched = patch("/v1/servicing/cases/" + cardCase, "{\"subtype\":\"ABROAD\",\"priority\":\"HIGH\"}",
                c.get("row_version").asInt(), 200);
        assertThat(patched.get("subtype").asString()).isEqualTo("ABROAD");
        assertThat(get("/v1/servicing/work-items?subject_id=" + cardCase + "&status=QUEUED").get("data").get(0)
                .get("priority").asString()).isEqualTo("HIGH");
    }

    // ------------------------------------------------------------------ helpers

    private interface Work {
        Object run() throws Exception;
    }

    private void asTenant(Work w) throws Exception {
        TenantContext.callAs(UUID.fromString(TENANT), "test", w::run);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static OffsetDateTime ts(JsonNode n, String f) {
        return OffsetDateTime.parse(n.get(f).asString());
    }

    private String code(JsonNode problem) {
        return problem.path("code").asString();
    }

    private String code(HttpResponse<String> r) throws Exception {
        return json.readTree(r.body()).path("code").asString();
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, key(), TENANT, "maker");
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
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
