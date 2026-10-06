package io.meridian.campaign;

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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, submitting a campaign opens an approval
 * request in workflow-service, and the decision arriving from workflow-service is applied with the approver as
 * principal, still under the campaign's four-eyes rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class CampaignWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000e7";
    static final UUID SEGMENT = UUID.fromString("0199a000-0000-7000-8000-00000000a0b1");

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

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubWorkflow() {
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            lastRequest.set((Map<String, Object>) inv.getArgument(2));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        });
        when(serviceClient.get(eq("workflow-service"), startsWith("/v1/approval-requests?")))
                .thenReturn(json.readTree("{\"data\":[]}"));
    }

    @Test
    void submitOpensWorkflowRequestAndApprovalFromWorkflowSchedules() throws Exception {
        String campaign = draftCampaign("WF-A");
        post("/v1/campaigns/" + campaign + "/submit", null, 200, "maker.anna");

        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "campaign").containsEntry("resource_id", campaign)
                .containsEntry("action", "approve").containsEntry("currency", "GBP");
        assertThat(new java.math.BigDecimal((String) req.get("amount"))).isEqualByComparingTo("100");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("submitted_at")).isNotNull();

        // the maker approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision(campaign, payload, "maker.anna"), Outcome.APPROVED);
        assertThat(get("/v1/campaigns/" + campaign).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(campaign, payload, "checker.ben"), Outcome.APPROVED);
        JsonNode c = get("/v1/campaigns/" + campaign);
        assertThat(c.get("status").asString()).isEqualTo("SCHEDULED");
        assertThat(c.get("approved_by").asString()).isEqualTo("checker.ben");

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision(campaign, payload, "checker.ben"), Outcome.APPROVED);
        assertThat(get("/v1/campaigns/" + campaign).get("status").asString()).isEqualTo("SCHEDULED");
    }

    @Test
    void rejectionFromWorkflowRejectsAndStaleDecisionIsIgnored() throws Exception {
        String campaign = draftCampaign("WF-B");
        post("/v1/campaigns/" + campaign + "/submit", null, 200, "maker.anna");
        Map<?, ?> payload = (Map<?, ?>) lastRequest.get().get("payload");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(campaign, payload, "checker.ben"), Outcome.REJECTED);
        JsonNode rejected = get("/v1/campaigns/" + campaign);
        assertThat(rejected.get("status").asString()).isEqualTo("DRAFT");
        assertThat(rejected.get("status_reason").asString()).contains("workflow");

        // an approval for the old submission after resubmit does nothing
        post("/v1/campaigns/" + campaign + "/submit", null, 200, "maker.anna");
        assertThat(((Map<?, ?>) lastRequest.get().get("payload")).get("submitted_at"))
                .isNotEqualTo(payload.get("submitted_at"));
        dispatcher.dispatch(UUID.fromString(TENANT), decision(campaign, payload, "checker.ben"), Outcome.APPROVED);
        assertThat(get("/v1/campaigns/" + campaign).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        // expiry in the workflow leaves the campaign waiting
        dispatcher.dispatch(UUID.fromString(TENANT), decision(campaign, (Map<?, ?>) lastRequest.get().get("payload"),
                "checker.ben"), Outcome.EXPIRED);
        assertThat(get("/v1/campaigns/" + campaign).get("status").asString()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void directDecisionClosesTheInboxItemAfterCommit() throws Exception {
        String campaign = draftCampaign("WF-C");
        post("/v1/campaigns/" + campaign + "/submit", null, 200, "maker.anna");
        post("/v1/campaigns/" + campaign + "/approve", "{}", 200, "checker.ben");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_id=" + campaign).contains("status=PENDING");
        verify(serviceClient, never()).post(eq("workflow-service"), startsWith("/v1/approval-requests/"), any(), anyString());
    }

    private ObjectNode decision(String campaign, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", "campaign");
        row.put("resource_id", campaign);
        row.put("action", "approve");
        row.put("maker", "maker.anna");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String draftCampaign(String code) throws Exception {
        String start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString();
        String campaign = post("/v1/campaigns", """
                {"code":"%s","name":"Workflow %s","segment_ids":["%s"],"channels":["PUSH"],"start_at":"%s",
                 "budget_amount":"100.00","currency":"GBP","cost_per_message":"0.01"}""".formatted(code, code, SEGMENT, start),
                201, "maker.anna").get("id").asString();
        post("/v1/campaign-variants", """
                {"campaign_id":"%s","code":"A","traffic_share":"1","channel":"PUSH","subject":"Hi","body":"Hello"}"""
                .formatted(campaign), 201, "maker.anna");
        return campaign;
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
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
