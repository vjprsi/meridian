package io.meridian.analytics;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, a new KPI (or a definition change)
 * opens a certification request in workflow-service, and the decision arriving from workflow-service is applied with
 * the approver as principal, still under the KPI's four-eyes rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class AnalyticsWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000f7";
    static final String METRIC = "{\"aggregate\":\"SUM\",\"value\":\"outstanding_principal\"}";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

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
    void newKpiOpensWorkflowRequestAndApprovalFromWorkflowCertifies() throws Exception {
        String kpi = createKpi("anna");
        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "analytics_kpi").containsEntry("resource_id", kpi)
                .containsEntry("action", "certify");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("definition_checksum")).isNotNull();
        assertThat(payload.get("dataset")).isEqualTo("loans.loans");

        // the author approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision(kpi, payload, "anna"), Outcome.APPROVED);
        assertThat(get("/v1/analytics/kpis/" + kpi).get("is_certified").asBoolean()).isFalse();

        dispatcher.dispatch(UUID.fromString(TENANT), decision(kpi, payload, "ben"), Outcome.APPROVED);
        JsonNode k = get("/v1/analytics/kpis/" + kpi);
        assertThat(k.get("is_certified").asBoolean()).isTrue();
        assertThat(k.get("certified_by").asString()).isEqualTo("ben");
        assertThat(certifiedEvents(kpi)).isEqualTo(1);

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision(kpi, payload, "ben"), Outcome.APPROVED);
        assertThat(get("/v1/analytics/kpis/" + kpi).get("is_certified").asBoolean()).isTrue();
        assertThat(certifiedEvents(kpi)).isEqualTo(1);
    }

    @Test
    void rejectionFromWorkflowLeavesUncertifiedAndStaleDecisionIsIgnored() throws Exception {
        String kpi = createKpi("anna");
        Map<?, ?> payload = (Map<?, ?>) lastRequest.get().get("payload");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(kpi, payload, "ben"), Outcome.REJECTED);
        assertThat(get("/v1/analytics/kpis/" + kpi).get("is_certified").asBoolean()).isFalse();
        assertThat(certifiedEvents(kpi)).isZero();

        // rework of the definition resubmits with a new checksum; an approval of the old definition does nothing
        JsonNode current = get("/v1/analytics/kpis/" + kpi);
        patch("/v1/analytics/kpis/" + kpi, "{\"metric\":{\"aggregate\":\"COUNT\"}}", current.get("row_version").asInt(), "anna");
        Map<?, ?> resubmitted = (Map<?, ?>) lastRequest.get().get("payload");
        assertThat(resubmitted.get("definition_checksum")).isNotEqualTo(payload.get("definition_checksum"));
        dispatcher.dispatch(UUID.fromString(TENANT), decision(kpi, payload, "ben"), Outcome.APPROVED);
        assertThat(get("/v1/analytics/kpis/" + kpi).get("is_certified").asBoolean()).isFalse();

        ObjectNode stale = decision(kpi, resubmitted, "ben");
        ((ObjectNode) stale.get("payload")).put("definition_checksum", "stale");
        dispatcher.dispatch(UUID.fromString(TENANT), stale, Outcome.APPROVED);
        assertThat(get("/v1/analytics/kpis/" + kpi).get("is_certified").asBoolean()).isFalse();

        // the current submission is applied
        dispatcher.dispatch(UUID.fromString(TENANT), decision(kpi, resubmitted, "ben"), Outcome.APPROVED);
        assertThat(get("/v1/analytics/kpis/" + kpi).get("is_certified").asBoolean()).isTrue();
    }

    @Test
    void directCertificationClosesTheInboxItemAfterCommit() throws Exception {
        String kpi = createKpi("anna");
        post("/v1/analytics/kpis/" + kpi + "/certify", "{}", 200, "ben");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_id=" + kpi).contains("status=PENDING");
        verify(serviceClient, never()).post(eq("workflow-service"), startsWith("/v1/approval-requests/"), any(), anyString());
    }

    private String createKpi(String user) throws Exception {
        String code = "WF_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return post("/v1/analytics/kpis", "{\"code\":\"%s\",\"name\":\"Outstanding\",\"dataset\":\"loans.loans\",\"unit\":\"AMOUNT\",\"metric\":%s}"
                .formatted(code, METRIC), 201, user).get("id").asString();
    }

    private long certifiedEvents(String kpi) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", () -> jdbc.sql(
                "select count(*) from outbox where event_type = 'reporting.analytics_kpi.certified' and aggregate_id = ?::uuid")
                .params(kpi).query(Long.class).single());
    }

    private ObjectNode decision(String kpi, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", "analytics_kpi");
        row.put("resource_id", kpi);
        row.put("action", "certify");
        row.put("maker", "anna");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode patch(String path, String body, int version, String user) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("If-Match", String.valueOf(version))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "viewer").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
}
