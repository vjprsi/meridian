package io.meridian.crm;

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
import java.math.BigDecimal;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, redress proposals and knowledge
 * article reviews open approval requests in workflow-service, and decisions arriving from workflow-service are
 * applied with the checker as principal, still under the four-eyes rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true",
                "meridian.crm.timeline.consumer-enabled=false",
                "meridian.crm.redress.ledger-id=0199a000-0000-7000-8000-00000000ad01",
                "meridian.crm.redress.funding-account-id=0199a000-0000-7000-8000-00000000ad02"})
@Import(MeridianTestContainers.class)
class CrmWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000d8";
    static final String CUSTOMER = "0199a000-0000-7000-8000-00000000a002";
    static final String CUSTOMER_ACCOUNT = "0199a000-0000-7000-8000-00000000ac01";

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
    void stubOtherServices() {
        when(serviceClient.get(eq("reference-data-service"), startsWith("/v1/reference/holidays"))).thenAnswer(inv ->
                json.readTree("{\"data\":[{\"holiday_date\":\"2026-12-25\",\"is_half_day\":false}],\"has_more\":false}"));
        when(serviceClient.post(eq("ledger-service"), eq("/v1/journal-entries"), any(), anyString()))
                .thenAnswer(inv -> json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"entry_count\":2}"));
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            lastRequest.set((Map<String, Object>) inv.getArgument(2));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        });
        when(serviceClient.get(eq("workflow-service"), startsWith("/v1/approval-requests?")))
                .thenReturn(json.readTree("{\"data\":[]}"));
    }

    @Test
    void redressProposalRoutedByAmountAndApprovedFromWorkflowUnderFourEyes() throws Exception {
        String redress = proposeRedress("40.00");
        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "complaint_redress").containsEntry("resource_id", redress)
                .containsEntry("action", "approve").containsEntry("currency", "GBP");
        assertThat(new BigDecimal((String) req.get("amount"))).isEqualByComparingTo("40");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("complaint_redress", redress, payload, "maker.anna"), Outcome.APPROVED);
        assertThat(get("/v1/servicing/complaint-redress/" + redress).get("status").asString()).isEqualTo("PROPOSED");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("complaint_redress", redress, payload, "checker.ben"), Outcome.APPROVED);
        JsonNode r = get("/v1/servicing/complaint-redress/" + redress);
        assertThat(r.get("status").asString()).isEqualTo("APPROVED");
        assertThat(r.get("approved_by").asString()).isEqualTo("checker.ben");

        // redelivery is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision("complaint_redress", redress, payload, "checker.ben"), Outcome.APPROVED);
        assertThat(get("/v1/servicing/complaint-redress/" + redress).get("status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void redressRejectedFromWorkflowAndLateApprovalIgnored() throws Exception {
        String redress = proposeRedress("15.00");
        Map<?, ?> payload = (Map<?, ?>) lastRequest.get().get("payload");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("complaint_redress", redress, payload, "checker.ben"), Outcome.REJECTED);
        assertThat(get("/v1/servicing/complaint-redress/" + redress).get("status").asString()).isEqualTo("REJECTED");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("complaint_redress", redress, payload, "checker.cy"), Outcome.APPROVED);
        assertThat(get("/v1/servicing/complaint-redress/" + redress).get("status").asString()).isEqualTo("REJECTED");
    }

    @Test
    void articleReviewPublishedFromWorkflowAndOldVersionDecisionIgnored() throws Exception {
        String article = post("/v1/servicing/knowledge-articles", """
                {"slug":"wf-card-pin","title":"Resetting your card PIN","body":"Open the app, choose the card and reset the PIN."}""",
                201, "maker.anna").get("id").asString();
        post("/v1/servicing/knowledge-articles/" + article + "/submit-review", null, 200, "maker.anna");
        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "knowledge_article").containsEntry("resource_id", article)
                .containsEntry("action", "publish");
        Map<?, ?> v1 = (Map<?, ?>) req.get("payload");

        // the author approving in the inbox is still refused
        dispatcher.dispatch(UUID.fromString(TENANT), decision("knowledge_article", article, v1, "maker.anna"), Outcome.APPROVED);
        assertThat(get("/v1/servicing/knowledge-articles/" + article).get("status").asString()).isEqualTo("IN_REVIEW");

        // rejected in the inbox: archived, reworked as version 2 and resubmitted
        dispatcher.dispatch(UUID.fromString(TENANT), decision("knowledge_article", article, v1, "checker.ben"), Outcome.REJECTED);
        assertThat(get("/v1/servicing/knowledge-articles/" + article).get("status").asString()).isEqualTo("ARCHIVED");
        post("/v1/servicing/knowledge-articles/" + article + "/new-version", null, 200, "maker.anna");
        post("/v1/servicing/knowledge-articles/" + article + "/submit-review", null, 200, "maker.anna");
        Map<?, ?> v2 = (Map<?, ?>) lastRequest.get().get("payload");
        assertThat(v2.get("version")).isEqualTo(2);

        // a late approval of version 1 does nothing; approval of version 2 publishes
        dispatcher.dispatch(UUID.fromString(TENANT), decision("knowledge_article", article, v1, "checker.ben"), Outcome.APPROVED);
        assertThat(get("/v1/servicing/knowledge-articles/" + article).get("status").asString()).isEqualTo("IN_REVIEW");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("knowledge_article", article, v2, "checker.ben"), Outcome.APPROVED);
        JsonNode a = get("/v1/servicing/knowledge-articles/" + article);
        assertThat(a.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(a.get("reviewed_by").asString()).isEqualTo("checker.ben");
    }

    private ObjectNode decision(String type, String id, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", type);
        row.put("resource_id", id);
        row.put("action", "knowledge_article".equals(type) ? "publish" : "approve");
        row.put("maker", "maker.anna");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    /** An acknowledged complaint with a monetary redress proposal; returns the redress id. */
    private String proposeRedress(String amount) throws Exception {
        String complaint = post("/v1/servicing/complaints", """
                {"customer_id":"%s","category":"FEES_AND_CHARGES","channel":"PHONE","summary":"Unfair fees"}"""
                .formatted(CUSTOMER), 201, "maker.anna").get("id").asString();
        post("/v1/servicing/complaints/" + complaint + "/acknowledge", "{\"message\":\"We are on it\"}", 200, "maker.anna");
        return post("/v1/servicing/complaint-redress", """
                {"complaint_id":"%s","redress_type":"FEE_REVERSAL","amount":"%s","currency":"GBP","target_account_id":"%s"}"""
                .formatted(complaint, amount, CUSTOMER_ACCOUNT), 201, "maker.anna").get("id").asString();
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
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
