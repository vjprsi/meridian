package io.meridian.transactionmonitoring;

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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, requesting a customer risk rating
 * override opens an approval request in workflow-service, and the decision arriving from workflow-service is applied
 * with the approver as principal, still under the override's four-eyes rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class TransactionMonitoringWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000d9";
    static final String RATINGS = "/v1/risk/monitoring/customer-risk-ratings/";

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
    void stubDownstream() {
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            lastRequest.set((Map<String, Object>) inv.getArgument(2));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        });
        when(serviceClient.get(eq("workflow-service"), startsWith("/v1/approval-requests?")))
                .thenReturn(json.readTree("{\"data\":[]}"));
        // case-management-service, as in TransactionMonitoringFlowIT (alert escalation)
        when(serviceClient.post(eq("case-management-service"), eq("/v1/risk/cases"), any(), anyString()))
                .thenReturn(json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"OPEN\"}"));
        when(serviceClient.post(eq("case-management-service"), startsWith("/v1/risk/cases/"), any(), anyString()))
                .thenReturn(json.readTree("{\"id\":\"" + UUID.randomUUID() + "\"}"));
    }

    @Test
    void overrideOpensWorkflowRequestAndApprovalFromWorkflowAppliesIt() throws Exception {
        String rating = rating();
        post(RATINGS + rating + "/override",
                "{\"override_rating\":\"HIGH\",\"override_reason\":\"Adverse media confirmed by EDD\"}", 200, "alice");
        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "customer_risk_rating").containsEntry("resource_id", rating)
                .containsEntry("action", "override");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("override_rating")).isEqualTo("HIGH");
        assertThat(payload.get("fingerprint")).isNotNull();

        // the maker approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision(rating, payload, "alice"), Outcome.APPROVED);
        assertThat(get(RATINGS + rating).get("override_status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(rating, payload, "bob"), Outcome.APPROVED);
        JsonNode r = get(RATINGS + rating);
        assertThat(r.get("override_status").asString()).isEqualTo("APPROVED");
        assertThat(r.get("override_approved_by").asString()).isEqualTo("bob");
        assertThat(r.get("effective_rating").asString()).isEqualTo("HIGH");

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision(rating, payload, "bob"), Outcome.APPROVED);
        assertThat(get(RATINGS + rating).get("override_status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void rejectionFromWorkflowRejectsAndStaleDecisionIsIgnored() throws Exception {
        String rating = rating();
        post(RATINGS + rating + "/override",
                "{\"override_rating\":\"HIGH\",\"override_reason\":\"Unusual cash activity\"}", 200, "alice");
        Map<?, ?> first = (Map<?, ?>) lastRequest.get().get("payload");
        dispatcher.dispatch(UUID.fromString(TENANT), decision(rating, first, "bob"), Outcome.REJECTED);
        JsonNode rejected = get(RATINGS + rating);
        assertThat(rejected.get("override_status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("override_reason").asString()).contains("workflow");

        // a decision on the earlier submission does not apply to a new, different override
        post(RATINGS + rating + "/override",
                "{\"override_rating\":\"PROHIBITED\",\"override_reason\":\"Sanctions nexus established\"}", 200, "alice");
        Map<?, ?> second = (Map<?, ?>) lastRequest.get().get("payload");
        assertThat(second.get("fingerprint")).isNotEqualTo(first.get("fingerprint"));
        dispatcher.dispatch(UUID.fromString(TENANT), decision(rating, first, "bob"), Outcome.APPROVED);
        assertThat(get(RATINGS + rating).get("override_status").asString()).isEqualTo("PENDING_APPROVAL");

        // expiry leaves it pending; the current submission is still approvable
        dispatcher.dispatch(UUID.fromString(TENANT), decision(rating, second, "bob"), Outcome.EXPIRED);
        assertThat(get(RATINGS + rating).get("override_status").asString()).isEqualTo("PENDING_APPROVAL");
        dispatcher.dispatch(UUID.fromString(TENANT), decision(rating, second, "bob"), Outcome.APPROVED);
        assertThat(get(RATINGS + rating).get("effective_rating").asString()).isEqualTo("PROHIBITED");
    }

    @Test
    void directDecisionKeepsFourEyesAndClosesTheInboxItemAfterCommit() throws Exception {
        String rating = rating();
        post(RATINGS + rating + "/override",
                "{\"override_rating\":\"HIGH\",\"override_reason\":\"Customer under investigation\"}", 200, "alice");
        assertThat(post(RATINGS + rating + "/approve-override", "{}", 422, "alice").get("code").asString())
                .isEqualTo("four_eyes_violation");
        verify(serviceClient, never()).get(eq("workflow-service"), anyString());
        post(RATINGS + rating + "/approve-override", "{}", 200, "bob");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_type=customer_risk_rating").contains("resource_id=" + rating)
                .contains("status=PENDING");
        verify(serviceClient, never()).post(eq("workflow-service"), startsWith("/v1/approval-requests/"), any(), anyString());
    }

    private ObjectNode decision(String rating, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", "customer_risk_rating");
        row.put("resource_id", rating);
        row.put("action", "override");
        row.put("maker", "alice");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String rating() throws Exception {
        return post("/v1/risk/monitoring/customer-risk-ratings", """
                {"customer_id":"%s","factors":{"country_risk":"LOW","product_risk":"LOW","pep":false}}"""
                .formatted(UUID.randomUUID()), 201, "kyc-service").get("id").asString();
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
