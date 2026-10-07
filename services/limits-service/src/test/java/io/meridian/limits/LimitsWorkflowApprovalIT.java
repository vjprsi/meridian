package io.meridian.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.meridian.approvals.ApprovalDecision.Outcome;
import io.meridian.approvals.ApprovalEventsDispatcher;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, requesting a limit override opens an
 * approval request in workflow-service, and the decision arriving from workflow-service is applied with the approver
 * as principal, still under the override's four-eyes rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class LimitsWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000c9";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @Autowired
    io.meridian.web.time.BusinessDates businessDates;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final AtomicReference<Map<String, Object>> lastRequest = new AtomicReference<>();
    final AtomicReference<String> lastRequestId = new AtomicReference<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubWorkflow() {
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            lastRequest.set((Map<String, Object>) inv.getArgument(2));
            lastRequestId.set(UUID.randomUUID().toString());
            return json.readTree("{\"id\":\"" + lastRequestId.get() + "\",\"status\":\"PENDING\"}");
        });
        when(serviceClient.get(eq("workflow-service"), startsWith("/v1/approval-requests?")))
                .thenReturn(json.readTree("{\"data\":[]}"));
    }

    @Test
    void overrideOpensWorkflowRequestAndApprovalFromWorkflowActivatesIt() throws Exception {
        String override = override(rule("WF_DAILY_A"), "4000");
        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "limit_override").containsEntry("resource_id", override)
                .containsEntry("action", "approve").containsEntry("currency", "USD");
        assertThat(new BigDecimal((String) req.get("amount"))).isEqualByComparingTo("4000");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("limit_code")).isEqualTo("WF_DAILY_A");
        assertThat(payload.get("max_amount")).isNotNull();
        assertThat(get("/v1/risk/limits/overrides/" + override).get("approval_request_id").asString())
                .isEqualTo(lastRequestId.get());
        String requestId = lastRequestId.get();

        // the maker approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision(requestId, override, payload, "alice"), Outcome.APPROVED);
        assertThat(get("/v1/risk/limits/overrides/" + override).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(requestId, override, payload, "bob"), Outcome.APPROVED);
        JsonNode o = get("/v1/risk/limits/overrides/" + override);
        assertThat(o.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(o.get("approved_by").asString()).isEqualTo("bob");

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision(requestId, override, payload, "bob"), Outcome.APPROVED);
        assertThat(get("/v1/risk/limits/overrides/" + override).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void rejectionFromWorkflowRejectsAndStaleDecisionsAreIgnored() throws Exception {
        String rule = rule("WF_DAILY_B");
        String override = override(rule, "2500");
        Map<?, ?> payload = (Map<?, ?>) lastRequest.get().get("payload");
        dispatcher.dispatch(UUID.fromString(TENANT), decision(lastRequestId.get(), override, payload, "bob"), Outcome.REJECTED);
        JsonNode rejected = get("/v1/risk/limits/overrides/" + override);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("decision_reason").asString()).contains("workflow");

        // a decision from another approval request than the one recorded on the override does nothing
        String second = override(rule, "2600");
        Map<?, ?> secondPayload = (Map<?, ?>) lastRequest.get().get("payload");
        dispatcher.dispatch(UUID.fromString(TENANT), decision(UUID.randomUUID().toString(), second, secondPayload, "bob"),
                Outcome.APPROVED);
        assertThat(get("/v1/risk/limits/overrides/" + second).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        // an expired request leaves the override pending; a late approval after a direct rejection is ignored
        String secondRequest = lastRequestId.get();
        dispatcher.dispatch(UUID.fromString(TENANT), decision(secondRequest, second, secondPayload, "bob"), Outcome.EXPIRED);
        assertThat(get("/v1/risk/limits/overrides/" + second).get("status").asString()).isEqualTo("PENDING_APPROVAL");
        post("/v1/risk/limits/overrides/" + second + "/reject", "{\"reason\":\"not justified\"}", 200, "carol");
        dispatcher.dispatch(UUID.fromString(TENANT), decision(secondRequest, second, secondPayload, "bob"), Outcome.APPROVED);
        assertThat(get("/v1/risk/limits/overrides/" + second).get("status").asString()).isEqualTo("REJECTED");
    }

    @Test
    void directDecisionKeepsFourEyesAndClosesTheInboxItemAfterCommit() throws Exception {
        String override = override(rule("WF_DAILY_C"), "3000");
        assertThat(post("/v1/risk/limits/overrides/" + override + "/approve", "{}", 422, "alice").get("code").asString())
                .isEqualTo("four_eyes_violation");
        verify(serviceClient, never()).get(eq("workflow-service"), anyString());
        post("/v1/risk/limits/overrides/" + override + "/approve", "{\"comment\":\"ok\"}", 200, "bob");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_type=limit_override").contains("resource_id=" + override)
                .contains("status=PENDING");
        verify(serviceClient, never()).post(eq("workflow-service"), startsWith("/v1/approval-requests/"), any(), anyString());
    }

    @Test
    void ruleSubmissionIsActivatedOrRejectedFromWorkflow() throws Exception {
        String rule = rule("WF_RULE_A");
        JsonNode submitted = post("/v1/risk/limits/rules/" + rule + "/submit", "{\"comment\":\"new cap\"}", 200, "alice");
        assertThat(submitted.get("status").asString()).isEqualTo("DRAFT");
        assertThat(submitted.get("submitted_by").asString()).isEqualTo("alice");
        assertThat(lastRequest.get()).containsEntry("resource_type", "limit_rule").containsEntry("resource_id", rule)
                .containsEntry("action", "activate").containsEntry("currency", "USD");
        String first = lastRequestId.get();
        Map<?, ?> payload = (Map<?, ?>) lastRequest.get().get("payload");

        // rejection keeps the rule DRAFT and records who rejected it
        dispatcher.dispatch(UUID.fromString(TENANT), ruleDecision(first, rule, payload, "bob"), Outcome.REJECTED);
        JsonNode rejected = get("/v1/risk/limits/rules/" + rule);
        assertThat(rejected.get("status").asString()).isEqualTo("DRAFT");
        assertThat(rejected.get("rejected_by").asString()).isEqualTo("bob");
        assertThat(rejected.hasNonNull("submitted_at")).isFalse();

        // resubmitted: the old request is stale, the new one activates (submitter still cannot approve)
        post("/v1/risk/limits/rules/" + rule + "/submit", "{}", 200, "alice");
        String second = lastRequestId.get();
        dispatcher.dispatch(UUID.fromString(TENANT), ruleDecision(first, rule, payload, "bob"), Outcome.APPROVED);
        assertThat(get("/v1/risk/limits/rules/" + rule).get("status").asString()).isEqualTo("DRAFT");
        dispatcher.dispatch(UUID.fromString(TENANT), ruleDecision(second, rule, payload, "alice"), Outcome.APPROVED);
        assertThat(get("/v1/risk/limits/rules/" + rule).get("status").asString()).isEqualTo("DRAFT");
        dispatcher.dispatch(UUID.fromString(TENANT), ruleDecision(second, rule, payload, "bob"), Outcome.APPROVED);
        JsonNode active = get("/v1/risk/limits/rules/" + rule);
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(active.get("approved_by").asString()).isEqualTo("bob");

        // direct reject needs a pending submission
        assertThat(post("/v1/risk/limits/rules/" + rule + "/reject", "{\"reason\":\"x\"}", 409, "bob").get("code").asString())
                .isEqualTo("no_pending_submission");
    }

    @Test
    void dailyCountersFollowTheBusinessDate() throws Exception {
        when(serviceClient.get(eq("business-day-service"), startsWith("/v1/operations/business-days")))
                .thenReturn(json.readTree("{\"data\":[{\"business_date\":\"2026-01-15\"}]}"));
        businessDates.invalidate();
        try {
            String rule = rule("WF_BD_DAILY");
            post("/v1/risk/limits/rules/" + rule + "/activate", "{}", 200, "bob");
            String subject = UUID.randomUUID().toString();
            post("/v1/risk/limits/checks", """
                    {"subject_type":"ACCOUNT","subject_id":"%s","transaction_type":"P2P","amount":"10","currency":"USD"}"""
                    .formatted(subject), 201, "svc");
            JsonNode usage = get("/v1/risk/limits/usages?limit_rule_id=" + rule).get("data").get(0);
            assertThat(OffsetDateTime.parse(usage.get("period_start").asString()).toInstant())
                    .isEqualTo(java.time.Instant.parse("2026-01-15T00:00:00Z"));
        } finally {
            businessDates.invalidate();
        }
    }

    private ObjectNode ruleDecision(String requestId, String rule, Map<?, ?> payload, String approver) {
        ObjectNode row = decision(requestId, rule, payload, approver);
        row.put("resource_type", "limit_rule");
        row.put("action", "activate");
        return row;
    }

    private ObjectNode decision(String requestId, String override, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", requestId);
        row.put("resource_type", "limit_override");
        row.put("resource_id", override);
        row.put("action", "approve");
        row.put("maker", "alice");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String rule(String code) throws Exception {
        return post("/v1/risk/limits/rules", """
                {"code":"%s","name":"%s","scope":"GLOBAL","period":"DAILY","measure":"AMOUNT",
                 "currency":"USD","max_amount":"1000","breach_action":"REJECT"}""".formatted(code, code), 201, "alice")
                .get("id").asString();
    }

    private String override(String rule, String amount) throws Exception {
        String from = OffsetDateTime.now().minusMinutes(1).toString();
        String to = OffsetDateTime.now().plusDays(2).toString();
        JsonNode o = post("/v1/risk/limits/overrides", """
                {"limit_rule_id":"%s","subject_type":"CUSTOMER","subject_id":"%s","max_amount":"%s",
                 "reason":"Seasonal business peak","valid_from":"%s","valid_to":"%s"}"""
                .formatted(rule, UUID.randomUUID(), amount, from, to), 201, "alice");
        assertThat(o.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        return o.get("id").asString();
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "viewer").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
}
