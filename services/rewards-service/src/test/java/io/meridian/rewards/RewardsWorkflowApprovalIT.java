package io.meridian.rewards;

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
import io.meridian.common.tenancy.TenantContext;
import io.meridian.rewards.infrastructure.Rows;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, requesting a manual points adjustment
 * opens an approval request in workflow-service, and the decision arriving from workflow-service is applied with
 * the approver as principal, still under the adjustment's four-eyes rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class RewardsWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000e7";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @Autowired
    Rows rows;

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
    void adjustmentOpensWorkflowRequestAndApprovalFromWorkflowApplies() throws Exception {
        String account = account("WF-RW-A");
        String adj = post("/v1/rewards/points-accounts/" + account + "/adjust", "{\"points\":500,\"reason\":\"goodwill\"}",
                200, "maker.anna").get("id").asString();

        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "points_adjustment").containsEntry("resource_id", adj)
                .containsEntry("action", "approve").containsEntry("amount", "5").containsEntry("currency", "GBP");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(((Number) payload.get("points")).longValue()).isEqualTo(500);

        // the maker approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision(adj, payload, "maker.anna"), Outcome.APPROVED);
        assertThat(status(adj)).isEqualTo("PENDING_APPROVAL");
        assertThat(get("/v1/rewards/points-accounts/" + account).get("available_points").asLong()).isZero();

        dispatcher.dispatch(UUID.fromString(TENANT), decision(adj, payload, "checker.ben"), Outcome.APPROVED);
        assertThat(status(adj)).isEqualTo("APPLIED");
        assertThat(adjustment(adj).get("decided_by").asString()).isEqualTo("checker.ben");
        assertThat(get("/v1/rewards/points-accounts/" + account).get("available_points").asLong()).isEqualTo(500);

        // redelivery of the same decision is harmless (points are not credited twice)
        dispatcher.dispatch(UUID.fromString(TENANT), decision(adj, payload, "checker.ben"), Outcome.APPROVED);
        assertThat(get("/v1/rewards/points-accounts/" + account).get("available_points").asLong()).isEqualTo(500);
    }

    @Test
    void rejectionFromWorkflowRejectsAndStaleDecisionIsIgnored() throws Exception {
        String account = account("WF-RW-B");
        String adj = post("/v1/rewards/points-accounts/" + account + "/adjust", "{\"points\":300,\"reason\":\"promo\"}",
                200, "maker.anna").get("id").asString();
        Map<?, ?> payload = (Map<?, ?>) lastRequest.get().get("payload");

        // a decision whose fingerprint (points) does not match the pending adjustment is ignored
        ObjectNode stale = decision(adj, payload, "checker.ben");
        ((ObjectNode) stale.get("payload")).put("points", 9999);
        dispatcher.dispatch(UUID.fromString(TENANT), stale, Outcome.APPROVED);
        assertThat(status(adj)).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(adj, payload, "checker.ben"), Outcome.REJECTED);
        assertThat(status(adj)).isEqualTo("REJECTED");

        // a late approval for the already rejected adjustment does nothing
        dispatcher.dispatch(UUID.fromString(TENANT), decision(adj, payload, "checker.cara"), Outcome.APPROVED);
        assertThat(status(adj)).isEqualTo("REJECTED");
        assertThat(get("/v1/rewards/points-accounts/" + account).get("available_points").asLong()).isZero();
    }

    @Test
    void directDecisionClosesTheInboxItemAfterCommit() throws Exception {
        String account = account("WF-RW-C");
        String adj = post("/v1/rewards/points-accounts/" + account + "/adjust", "{\"points\":100,\"reason\":\"x\"}",
                200, "maker.anna").get("id").asString();
        post("/v1/rewards/points-adjustments/" + adj + "/approve", "{}", 200, "checker.ben");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_id=" + adj).contains("status=PENDING");
        verify(serviceClient, never()).post(eq("workflow-service"), startsWith("/v1/approval-requests/"), any(), anyString());
    }

    private String status(String adj) throws Exception {
        return adjustment(adj).get("status").asString();
    }

    private JsonNode adjustment(String adj) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "viewer", () -> rows.get("points_adjustment", UUID.fromString(adj)));
    }

    private ObjectNode decision(String adj, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", "points_adjustment");
        row.put("resource_id", adj);
        row.put("action", "approve");
        row.put("maker", "maker.anna");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String account(String code) throws Exception {
        String program = post("/v1/rewards/programs", """
                {"code":"%s","name":"%s","program_type":"POINTS","point_value":"0.01","value_currency":"GBP",
                 "ledger_id":"%s","funding_ledger_account_id":"%s","starts_on":"%s"}"""
                .formatted(code, code, UUID.randomUUID(), UUID.randomUUID(), LocalDate.now().minusDays(1)), 201, "maker.anna")
                .get("id").asString();
        post("/v1/rewards/programs/" + program + "/activate", "{}", 200, "checker.ben");
        return post("/v1/rewards/points-accounts", """
                {"rewards_program_id":"%s","customer_id":"%s"}""".formatted(program, UUID.randomUUID()), 201, "maker.anna")
                .get("id").asString();
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
