package io.meridian.archive;

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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, submitting a purge request opens an
 * approval request in workflow-service, and the decision coming back is applied with the approver as principal,
 * still under the purge's four-eyes rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class ArchiveWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000a7";
    static final String CUSTOMER = "0199a000-0000-7000-8000-0000000000e1";

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
    static String customerClass;
    static String customerPolicy;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubWorkflow() throws Exception {
        when(serviceClient.post(anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            String path = inv.getArgument(1);
            return path.equals("/v1/internal/data-lifecycle/count") ? json.readTree("{\"count\":3}") : null;
        });
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            lastRequest.set((Map<String, Object>) inv.getArgument(2));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        });
        when(serviceClient.get(eq("workflow-service"), startsWith("/v1/approval-requests?")))
                .thenReturn(json.readTree("{\"data\":[]}"));
        if (customerClass == null) {
            customerClass = post("/v1/archive/classifications", """
                    {"data_class":"CUSTOMER","service_name":"customer-service","database_name":"customer_db",
                     "table_name":"customer","sensitivity":"PII","pii_columns":["email"]}""", 201, "dpo").get("id").asString();
            customerPolicy = post("/v1/archive/retention-policies", """
                    {"data_class":"CUSTOMER","retention_years":5,"online_days":90,"valid_from":"2020-01-01",
                     "regulation_reference":"GDPR"}""", 201, "dpo").get("id").asString();
        }
    }

    @Test
    void submitOpensWorkflowRequestAndApprovalFromWorkflowApproves() throws Exception {
        String id = submittedPurge();
        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "purge_request").containsEntry("resource_id", id)
                .containsEntry("action", "approve");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("row_version")).isNotNull();
        assertThat(payload.get("action")).isEqualTo("PURGE");

        // the requester approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "ops"), Outcome.APPROVED);
        assertThat(status(id)).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "dpo"), Outcome.APPROVED);
        JsonNode p = get("/v1/archive/purge-requests/" + id);
        assertThat(p.get("status").asString()).isEqualTo("APPROVED");
        assertThat(p.get("approved_by").asString()).isEqualTo("dpo");

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "dpo"), Outcome.APPROVED);
        assertThat(status(id)).isEqualTo("APPROVED");
    }

    @Test
    void rejectionFromWorkflowRejects() throws Exception {
        String id = submittedPurge();
        Map<?, ?> payload = (Map<?, ?>) lastRequest.get().get("payload");
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "dpo"), Outcome.EXPIRED);
        assertThat(status(id)).isEqualTo("PENDING_APPROVAL");
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "dpo"), Outcome.REJECTED);
        JsonNode p = get("/v1/archive/purge-requests/" + id);
        assertThat(p.get("status").asString()).isEqualTo("REJECTED");
        assertThat(p.get("decision_reason").asString()).contains("workflow request");
        // a late approval for the rejected purge does nothing
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "dpo"), Outcome.APPROVED);
        assertThat(status(id)).isEqualTo("REJECTED");
    }

    @Test
    void staleDecisionIsIgnored() throws Exception {
        String id = submittedPurge();
        ObjectNode stale = decision(id, (Map<?, ?>) lastRequest.get().get("payload"), "dpo");
        ((ObjectNode) stale.get("payload")).put("row_version", 0);
        dispatcher.dispatch(UUID.fromString(TENANT), stale, Outcome.APPROVED);
        assertThat(status(id)).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void directDecisionClosesTheInboxItemAfterCommit() throws Exception {
        String id = submittedPurge();
        post("/v1/archive/purge-requests/" + id + "/approve", null, 200, "dpo");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_id=" + id).contains("status=PENDING");
    }

    private String submittedPurge() throws Exception {
        String id = post("/v1/archive/purge-requests", """
                {"retention_policy_id":"%s","data_classification_id":"%s","scope_description":"Customer records",
                 "record_selector":{"customer_ids":["%s"]},"action":"PURGE"}""".formatted(customerPolicy, customerClass, CUSTOMER),
                201, "ops").get("id").asString();
        assertThat(post("/v1/archive/purge-requests/" + id + "/submit", null, 200, "ops").get("status").asString())
                .isEqualTo("PENDING_APPROVAL");
        return id;
    }

    private ObjectNode decision(String id, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", "purge_request");
        row.put("resource_id", id);
        row.put("action", "approve");
        row.put("maker", "ops");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String status(String id) throws Exception {
        return get("/v1/archive/purge-requests/" + id).get("status").asString();
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
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
