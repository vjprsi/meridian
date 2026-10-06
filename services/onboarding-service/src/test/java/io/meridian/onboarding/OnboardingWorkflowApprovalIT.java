package io.meridian.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, an application referred for manual
 * review opens a review request in workflow-service, and the decision arriving from workflow-service is applied with
 * the reviewer as principal, still under the checker rule (neither creator nor submitter).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnboardingWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000ca";
    static final String REVIEW_PROFILE = "0199a000-0000-7000-8000-00000000a002";
    static final String PROVIDER = "0199a000-0000-7000-8000-00000000b001";
    static final String CUSTOMER = "0199a000-0000-7000-8000-00000000c001";
    static final String LEGAL_ENTITY = "0199a000-0000-7000-8000-00000000d001";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @MockitoBean
    ServiceClient client;

    final HttpClient http = HttpClient.newHttpClient();
    final AtomicReference<Map<String, Object>> lastRequest = new AtomicReference<>();
    String journey;

    @BeforeAll
    void publishJourney() throws Exception {
        String draft = post("/v1/onboarding/journeys", """
                {"code":"WF_RETAIL","name":"Workflow retail","customer_type":"INDIVIDUAL","country":"KE",
                 "channels":["MOBILE_APP","WEB"],"target_tier":"TIER_1","expiry_days":30,
                 "definition":{"steps":[
                   {"code":"phone","type":"PHONE_VERIFICATION","fields":["phone_number"]},
                   {"code":"details","type":"PERSONAL_DETAILS","fields":["first_name","last_name","date_of_birth"]},
                   {"code":"id_check","type":"DOCUMENT_CAPTURE","fields":["kyc_profile_id"],
                    "kyc_check":{"check_type":"DOCUMENT_AUTHENTICITY","kyc_provider_id":"%s"}},
                   {"code":"terms","type":"TERMS_ACCEPTANCE","fields":["accepted"]}]},
                 "decision_rules":{}}""".formatted(PROVIDER), "maker.anna", 201).get("id").asString();
        journey = post("/v1/onboarding/journeys/" + draft + "/publish", null, "checker.ben", 200).get("id").asString();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubs() {
        when(client.post(eq("kyc-service"), eq("/v1/kyc/checks"), any(), anyString()))
                .thenAnswer(inv -> json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"INCONCLUSIVE\"}"));
        when(client.post(eq("customer-service"), eq("/v1/customers"), any(), anyString()))
                .thenAnswer(inv -> json.readTree("{\"id\":\"" + CUSTOMER + "\"}"));
        when(client.post(eq("notification-service"), eq("/v1/notifications"), any(), anyString()))
                .thenAnswer(inv -> json.readTree("{\"id\":\"" + UUID.randomUUID() + "\"}"));
        when(client.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            lastRequest.set((Map<String, Object>) inv.getArgument(2));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        });
        when(client.get(eq("workflow-service"), startsWith("/v1/approval-requests?")))
                .thenReturn(json.readTree("{\"data\":[]}"));
    }

    @Test
    void referralOpensWorkflowReviewAndApprovalFromWorkflowApproves() throws Exception {
        String app = referredApplication();
        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "onboarding_application").containsEntry("resource_id", app)
                .containsEntry("action", "review");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("submitted_at")).isNotNull();
        assertThat(payload.get("risk_level")).isNotNull();

        // the maker (creator and submitter) approving in the inbox is still refused
        dispatcher.dispatch(UUID.fromString(TENANT), decision(app, payload, "maker.anna"), Outcome.APPROVED);
        assertThat(get("/v1/onboarding/applications/" + app).get("status").asString()).isEqualTo("REFERRED");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(app, payload, "checker.ben"), Outcome.APPROVED);
        JsonNode a = get("/v1/onboarding/applications/" + app);
        assertThat(a.get("status").asString()).isEqualTo("APPROVED");
        assertThat(a.get("reviewed_by").asString()).isEqualTo("checker.ben");
        assertThat(a.get("customer_id").asString()).isEqualTo(CUSTOMER);

        // redelivery is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision(app, payload, "checker.ben"), Outcome.APPROVED);
        assertThat(get("/v1/onboarding/applications/" + app).get("status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void changesRequestedThenResubmissionMakesOldDecisionStaleAndRejectionApplies() throws Exception {
        String app = referredApplication();
        Map<?, ?> first = (Map<?, ?>) lastRequest.get().get("payload");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(app, first, "checker.ben"), Outcome.CHANGES_REQUESTED);
        JsonNode a = get("/v1/onboarding/applications/" + app);
        assertThat(a.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(a.get("info_requests")).hasSize(1);

        post("/v1/onboarding/applications/" + app + "/submit", null, "maker.anna", 200);
        Map<?, ?> second = (Map<?, ?>) lastRequest.get().get("payload");
        assertThat(second.get("submitted_at")).isNotEqualTo(first.get("submitted_at"));
        dispatcher.dispatch(UUID.fromString(TENANT), decision(app, first, "checker.ben"), Outcome.APPROVED);
        assertThat(get("/v1/onboarding/applications/" + app).get("status").asString()).isEqualTo("REFERRED");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(app, second, "checker.ben"), Outcome.REJECTED);
        a = get("/v1/onboarding/applications/" + app);
        assertThat(a.get("status").asString()).isEqualTo("REJECTED");
        assertThat(a.get("rejection_reason").asString()).startsWith("Rejected in workflow request");
    }

    @Test
    void directReviewClosesTheInboxItemAfterCommit() throws Exception {
        String app = referredApplication();
        post("/v1/onboarding/applications/" + app + "/approve", "{\"comment\":\"fine\"}", "checker.ben", 200);
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(client).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_type=onboarding_application").contains("resource_id=" + app);
    }

    private ObjectNode decision(String app, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", "onboarding_application");
        row.put("resource_id", app);
        row.put("action", "review");
        row.put("maker", "maker.anna");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    /** An application whose inconclusive KYC check refers it for manual review. */
    private String referredApplication() throws Exception {
        String phone = "+2547" + String.format("%08d", (int) (Math.random() * 1e8));
        String app = post("/v1/onboarding/applications", """
                {"application_number":"APP-%s","onboarding_journey_id":"%s","channel":"WEB","phone":"%s",
                 "email":"applicant@example.com","legal_entity_id":"%s"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 12), journey, phone, LEGAL_ENTITY), "maker.anna", 201)
                .get("id").asString();
        step(app, "phone", "{\"phone_number\":\"" + phone + "\"}");
        step(app, "details", "{\"first_name\":\"Amina\",\"last_name\":\"Otieno\",\"date_of_birth\":\"1991-04-12\"}");
        step(app, "id_check", "{\"kyc_profile_id\":\"" + REVIEW_PROFILE + "\"}");
        step(app, "terms", "{\"accepted\":true}");
        JsonNode referred = post("/v1/onboarding/applications/" + app + "/submit", null, "maker.anna", 200);
        assertThat(referred.get("status").asString()).isEqualTo("REFERRED");
        return app;
    }

    private void step(String app, String code, String input) throws Exception {
        post("/v1/onboarding/applications/" + app + "/steps/" + code, "{\"input\":" + input + "}", "maker.anna", 200);
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "viewer").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
}
