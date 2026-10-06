package io.meridian.collateral;

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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, a PROPOSED collateral that receives a
 * valuation is submitted for verification to workflow-service, and the decision arriving from workflow-service is
 * applied with the approver as principal, still under the four-eyes rule (registering user may not verify).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class CollateralWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000c1c7";
    static final String OWNER = "0199a000-0000-7000-8000-00000000c2c7";
    static final LocalDate YESTERDAY = LocalDate.now(ZoneOffset.UTC).minusDays(1);

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final List<Map<String, Object>> requests = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubWorkflow() {
        FakeLoanService loans = new FakeLoanService(json);
        when(serviceClient.get(anyString(), anyString()))
                .thenAnswer(i -> loans.get(i.getArgument(0), i.getArgument(1)));
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> loans.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            requests.add((Map<String, Object>) inv.getArgument(2));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        });
        when(serviceClient.get(eq("workflow-service"), startsWith("/v1/approval-requests?")))
                .thenReturn(json.readTree("{\"data\":[]}"));
    }

    @Test
    void valuationSubmitsForVerificationAndApprovalFromWorkflowVerifies() throws Exception {
        String id = register("TN-WF-1");
        assertThat(requests).as("no valuation yet: not ready for verification").isEmpty();
        value(id, "320000.00");

        Map<String, Object> req = last();
        assertThat(req).containsEntry("resource_type", "collateral").containsEntry("resource_id", id)
                .containsEntry("action", "verify").containsEntry("currency", "EUR");
        assertThat(new BigDecimal((String) req.get("amount"))).isEqualByComparingTo("320000");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("row_version")).isNotNull();
        assertThat(payload.get("registered_by")).isEqualTo("maker.anna");

        // the registering user approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "maker.anna"), Outcome.APPROVED);
        assertThat(status(id)).isEqualTo("PROPOSED");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "checker.ben"), Outcome.APPROVED);
        JsonNode c = get("/v1/collaterals/" + id);
        assertThat(c.get("status").asString()).isEqualTo("VERIFIED");
        assertThat(c.get("verified_by").asString()).isEqualTo("checker.ben");

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "checker.ben"), Outcome.APPROVED);
        assertThat(get("/v1/collaterals/" + id).get("row_version").asInt()).isEqualTo(c.get("row_version").asInt());
    }

    @Test
    void rejectionFromWorkflowRejectsAndStaleDecisionIsIgnored() throws Exception {
        String rejected = register("TN-WF-2");
        value(rejected, "310000.00");
        Map<?, ?> p = (Map<?, ?>) last().get("payload");
        dispatcher.dispatch(UUID.fromString(TENANT), decision(rejected, p, "checker.ben"), Outcome.REJECTED);
        JsonNode r = get("/v1/collaterals/" + rejected);
        assertThat(r.get("status").asString()).isEqualTo("REJECTED");
        assertThat(r.get("status_reason").asString()).startsWith("rejected in workflow request");

        String id = register("TN-WF-3");
        value(id, "300000.00");
        Map<?, ?> first = (Map<?, ?>) last().get("payload");
        // changes requested leaves it PROPOSED; a revaluation resubmits a new version
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, first, "checker.ben"), Outcome.CHANGES_REQUESTED);
        assertThat(status(id)).isEqualTo("PROPOSED");
        value(id, "290000.00");
        Map<?, ?> second = (Map<?, ?>) last().get("payload");
        assertThat(second.get("row_version")).isNotEqualTo(first.get("row_version"));

        // an approval of the earlier submission (older row_version) does nothing
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, first, "checker.ben"), Outcome.APPROVED);
        assertThat(status(id)).isEqualTo("PROPOSED");
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, second, "checker.ben"), Outcome.APPROVED);
        assertThat(status(id)).isEqualTo("VERIFIED");
    }

    @Test
    void directDecisionClosesTheInboxItemAfterCommit() throws Exception {
        String id = register("TN-WF-4");
        value(id, "305000.00");
        post("/v1/collaterals/" + id + "/verify", null, 200, "checker.ben");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_type=collateral").contains("resource_id=" + id)
                .contains("status=PENDING");
        verify(serviceClient, never()).post(eq("workflow-service"), startsWith("/v1/approval-requests/"), any(), anyString());
    }

    private Map<String, Object> last() {
        assertThat(requests).isNotEmpty();
        return requests.get(requests.size() - 1);
    }

    private ObjectNode decision(String id, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", "collateral");
        row.put("resource_id", id);
        row.put("action", "verify");
        row.put("maker", "valuer.carl");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String register(String titleNumber) throws Exception {
        return post("/v1/collaterals", """
                {"owner_customer_id":"%s","collateral_type":"PROPERTY","description":"Flat 4, 12 High St","currency":"EUR",
                 "market_value":"300000.00","asset_details":{"title_number":"%s"},"location":{"city":"Dublin"}}"""
                .formatted(OWNER, titleNumber), 201, "maker.anna").get("id").asString();
    }

    private void value(String id, String market) throws Exception {
        post("/v1/collaterals/" + id + "/valuations", """
                {"collateral_id":"%s","valuation_date":"%s","market_value":"%s","currency":"EUR",
                 "valuer":"Acme Surveyors","method":"FULL_SURVEY"}""".formatted(id, YESTERDAY, market), 201, "valuer.carl");
    }

    private String status(String id) throws Exception {
        return get("/v1/collaterals/" + id).get("status").asString();
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
