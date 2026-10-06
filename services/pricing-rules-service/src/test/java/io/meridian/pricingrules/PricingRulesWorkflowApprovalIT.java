package io.meridian.pricingrules;

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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, submitting a version opens an approval
 * request in workflow-service, and the decision arriving from workflow-service is applied with the approver as
 * principal, still under the version's four-eyes rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class PricingRulesWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000c7";

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
    void submitOpensWorkflowRequestAndApprovalFromWorkflowPublishes() throws Exception {
        String version = draftVersion("WF_GRID_A");
        post("/v1/decision-table-versions/" + version + "/submit", "{\"comment\":\"ready\"}", 200, "maker.anna");

        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "decision_table_version").containsEntry("resource_id", version)
                .containsEntry("action", "approve");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("checksum")).isNotNull();

        // the maker approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision(version, payload, "maker.anna"), Outcome.APPROVED);
        assertThat(get("/v1/decision-table-versions/" + version).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(version, payload, "checker.ben"), Outcome.APPROVED);
        JsonNode v = get("/v1/decision-table-versions/" + version);
        assertThat(v.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(v.get("approved_by").asString()).isEqualTo("checker.ben");

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision(version, payload, "checker.ben"), Outcome.APPROVED);
        assertThat(get("/v1/decision-table-versions/" + version).get("status").asString()).isEqualTo("PUBLISHED");
    }

    @Test
    void rejectionFromWorkflowRejectsAndStaleDecisionIsIgnored() throws Exception {
        String version = draftVersion("WF_GRID_B");
        post("/v1/decision-table-versions/" + version + "/submit", "{}", 200, "maker.anna");
        Map<?, ?> payload = (Map<?, ?>) lastRequest.get().get("payload");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(version, payload, "checker.ben"), Outcome.REJECTED);
        assertThat(get("/v1/decision-table-versions/" + version).get("status").asString()).isEqualTo("REJECTED");

        // an approval for the old submission (different checksum) after rework + resubmit does nothing
        post("/v1/decision-table-versions/" + version + "/submit", "{}", 200, "maker.anna");
        ObjectNode stale = decision(version, payload, "checker.ben");
        ((ObjectNode) stale.get("payload")).put("checksum", "stale");
        dispatcher.dispatch(UUID.fromString(TENANT), stale, Outcome.APPROVED);
        assertThat(get("/v1/decision-table-versions/" + version).get("status").asString()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void directDecisionClosesTheInboxItemAfterCommit() throws Exception {
        String version = draftVersion("WF_GRID_C");
        post("/v1/decision-table-versions/" + version + "/submit", "{}", 200, "maker.anna");
        post("/v1/decision-table-versions/" + version + "/approve", "{}", 200, "checker.ben");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_id=" + version).contains("status=PENDING");
        verify(serviceClient, never()).post(eq("workflow-service"), startsWith("/v1/approval-requests/"), any(), anyString());
    }

    private ObjectNode decision(String version, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", "decision_table_version");
        row.put("resource_id", version);
        row.put("action", "approve");
        row.put("maker", "maker.anna");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String draftVersion(String code) throws Exception {
        String table = post("/v1/decision-tables", "{\"code\":\"" + code + "\",\"name\":\"" + code + "\",\"purpose\":\"PRICING\"}",
                201, "maker.anna").get("id").asString();
        return post("/v1/decision-table-versions", """
                {"decision_table_id":"%s","hit_policy":"FIRST",
                 "inputs":[{"name":"score","type":"number"}],
                 "outputs":[{"name":"rate","type":"number"}],
                 "rules":[{"inputs":["< 600"],"outputs":["0.18"]},{"inputs":[">= 600"],"outputs":["0.09"]}]}"""
                .formatted(table), 201, "maker.anna").get("id").asString();
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
