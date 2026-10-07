package io.meridian.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;

import io.meridian.approvals.ApprovalDecision.Outcome;
import io.meridian.approvals.ApprovalEventsDispatcher;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Central maker-checker (G11) for fraud rule activation: submitting a rule opens an approval request in
 * workflow-service; the decision is applied with the approver as principal (four-eyes still applies), a rejection
 * leaves the rule in its status with the rejection recorded, and decisions on a replaced submission are ignored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class FraudWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000f7";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

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
    void submitOpensRequestAndCheckerApprovalActivatesInTheSubmittedMode() throws Exception {
        String rule = rule("WF_RULE_A");
        JsonNode submitted = post("/v1/risk/fraud/rules/" + rule + "/submit", "{\"mode\":\"SHADOW\",\"comment\":\"try\"}", 200, "maker.anna");
        assertThat(submitted.get("status").asString()).isEqualTo("DRAFT");
        assertThat(submitted.get("submitted_mode").asString()).isEqualTo("SHADOW");
        assertThat(submitted.get("approval_request_id").asString()).isEqualTo(lastRequestId.get());
        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "fraud_rule").containsEntry("resource_id", rule)
                .containsEntry("action", "activate");
        String requestId = lastRequestId.get();

        // the maker approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision(requestId, rule, "maker.anna"), Outcome.APPROVED);
        assertThat(get("/v1/risk/fraud/rules/" + rule).get("status").asString()).isEqualTo("DRAFT");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(requestId, rule, "checker.ben"), Outcome.APPROVED);
        JsonNode active = get("/v1/risk/fraud/rules/" + rule);
        assertThat(active.get("status").asString()).isEqualTo("SHADOW");
        assertThat(active.get("approved_by").asString()).isEqualTo("checker.ben");
        assertThat(active.hasNonNull("submitted_at")).isFalse();

        // redelivery is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision(requestId, rule, "checker.ben"), Outcome.APPROVED);
        assertThat(get("/v1/risk/fraud/rules/" + rule).get("status").asString()).isEqualTo("SHADOW");
    }

    @Test
    void rejectionIsRecordedAndStaleDecisionIgnored() throws Exception {
        String rule = rule("WF_RULE_B");
        post("/v1/risk/fraud/rules/" + rule + "/submit", "{\"mode\":\"ACTIVE\"}", 200, "maker.anna");
        String first = lastRequestId.get();
        dispatcher.dispatch(UUID.fromString(TENANT), decision(first, rule, "checker.ben"), Outcome.REJECTED);
        JsonNode rejected = get("/v1/risk/fraud/rules/" + rule);
        assertThat(rejected.get("status").asString()).isEqualTo("DRAFT");
        assertThat(rejected.get("rejected_by").asString()).isEqualTo("checker.ben");
        assertThat(rejected.hasNonNull("submitted_at")).isFalse();

        // resubmitted: an approval of the first request is ignored, the new one applies
        post("/v1/risk/fraud/rules/" + rule + "/submit", "{\"mode\":\"ACTIVE\"}", 200, "maker.anna");
        assertThat(get("/v1/risk/fraud/rules/" + rule).hasNonNull("rejected_by")).isFalse();
        dispatcher.dispatch(UUID.fromString(TENANT), decision(first, rule, "checker.ben"), Outcome.APPROVED);
        assertThat(get("/v1/risk/fraud/rules/" + rule).get("status").asString()).isEqualTo("DRAFT");
        dispatcher.dispatch(UUID.fromString(TENANT), decision(lastRequestId.get(), rule, "checker.ben"), Outcome.APPROVED);
        assertThat(get("/v1/risk/fraud/rules/" + rule).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void directRejectNeedsASecondUserAndAPendingSubmission() throws Exception {
        String rule = rule("WF_RULE_C");
        assertThat(post("/v1/risk/fraud/rules/" + rule + "/reject", "{\"reason\":\"no\"}", 409, "checker.ben")
                .get("code").asString()).isEqualTo("no_pending_submission");
        post("/v1/risk/fraud/rules/" + rule + "/submit", "{\"mode\":\"ACTIVE\"}", 200, "maker.anna");
        assertThat(post("/v1/risk/fraud/rules/" + rule + "/reject", "{\"reason\":\"mine\"}", 422, "maker.anna")
                .get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode r = post("/v1/risk/fraud/rules/" + rule + "/reject", "{\"reason\":\"too broad\"}", 200, "checker.ben");
        assertThat(r.get("rejection_reason").asString()).isEqualTo("too broad");
        assertThat(r.get("status").asString()).isEqualTo("DRAFT");
    }

    private ObjectNode decision(String requestId, String rule, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", requestId);
        row.put("resource_type", "fraud_rule");
        row.put("resource_id", rule);
        row.put("action", "activate");
        row.put("maker", "maker.anna");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(lastRequest.get().get("payload")));
        return row;
    }

    private String rule(String code) throws Exception {
        return post("/v1/risk/fraud/rules", """
                {"code":"%s","name":"Large payment","subject_type":"PAYMENT","expression":"amount >= 5000",
                 "action":"REVIEW","score_adjustment":100}""".formatted(code), 201, "maker.anna").get("id").asString();
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
