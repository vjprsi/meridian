package io.meridian.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.meridian.approvals.ApprovalDecision.Outcome;
import io.meridian.approvals.ApprovalEventsDispatcher;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
 * Central maker-checker (G11) for platform modules: enable/disable opens an approval request in workflow-service
 * through libs/meridian-approvals, and the decision coming back is executed with the approver as principal, still
 * under the four-eyes rule. Module changes have no local decision path, so they are routed centrally in any mode.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class TenantWorkflowApprovalIT {
    static final String TENANT = "0199b000-0000-7000-8000-0000000000c7";

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
    static boolean seeded;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubWorkflow() throws Exception {
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            lastRequest.set((Map<String, Object>) inv.getArgument(2));
            ObjectNode row = json.valueToTree(inv.getArgument(2));
            row.put("id", UUID.randomUUID().toString());
            row.put("status", "PENDING");
            row.put("maker", "maker-1");
            return row;
        });
        if (!seeded) {
            // modules are normally seeded by template provisioning; seed two directly for an unplaced tenant
            try (Connection c = DriverManager.getConnection(MeridianTestContainers.adminJdbcUrl(), "meridian", "meridian");
                 PreparedStatement ps = c.prepareStatement(
                         "insert into tenant.tenant_module (tenant_id, module, is_enabled) values (?::uuid, ?, false)")) {
                for (String m : new String[] {"RISK", "REPORTING"}) {
                    ps.setString(1, TENANT);
                    ps.setString(2, m);
                    ps.executeUpdate();
                }
            }
            seeded = true;
        }
    }

    @Test
    void enableApprovedInWorkflowIsExecutedOnceAndFourEyesStillApplies() throws Exception {
        String risk = module("RISK");
        JsonNode created = post("/v1/modules/" + risk + "/enable", "{\"config\":{\"limit\":5}}", 200, "maker-1");
        assertThat(created.get("status").asString()).isEqualTo("PENDING");
        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "tenant_module").containsEntry("resource_id", risk)
                .containsEntry("action", "enable");

        // approved by the maker: refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision(created, "maker-1"), Outcome.APPROVED);
        assertThat(get("/v1/modules/" + risk).get("is_enabled").asBoolean()).isFalse();

        dispatcher.dispatch(UUID.fromString(TENANT), decision(created, "checker-1"), Outcome.APPROVED);
        JsonNode m = get("/v1/modules/" + risk);
        assertThat(m.get("is_enabled").asBoolean()).isTrue();
        assertThat(m.get("config").get("limit").asInt()).isEqualTo(5);
        assertThat(m.get("approval_request_id").asString()).isEqualTo(created.get("id").asString());
        int version = m.get("row_version").asInt();

        // redelivery is a no-op
        dispatcher.dispatch(UUID.fromString(TENANT), decision(created, "checker-1"), Outcome.APPROVED);
        assertThat(get("/v1/modules/" + risk).get("row_version").asInt()).isEqualTo(version);
    }

    @Test
    void rejectedOrStaleDecisionsChangeNothing() throws Exception {
        String reporting = module("REPORTING");
        JsonNode created = post("/v1/modules/" + reporting + "/enable", "{}", 200, "maker-1");
        int version = get("/v1/modules/" + reporting).get("row_version").asInt();
        dispatcher.dispatch(UUID.fromString(TENANT), decision(created, "checker-1"), Outcome.REJECTED);
        assertThat(get("/v1/modules/" + reporting).get("is_enabled").asBoolean()).isFalse();

        // a disable approval for a module that is already disabled (stale) leaves it untouched
        ObjectNode stale = decision(created, "checker-1");
        stale.put("action", "disable");
        ((ObjectNode) stale.get("payload")).put("is_enabled", false);
        dispatcher.dispatch(UUID.fromString(TENANT), stale, Outcome.APPROVED);
        assertThat(get("/v1/modules/" + reporting).get("row_version").asInt()).isEqualTo(version);
    }

    private ObjectNode decision(JsonNode created, String approver) {
        ObjectNode row = (ObjectNode) created.deepCopy();
        row.put("status", "APPROVED");
        row.put("updated_by", approver);
        return row;
    }

    private String module(String name) throws Exception {
        for (JsonNode m : get("/v1/modules?limit=50").get("data")) {
            if (name.equals(m.get("module").asString())) {
                return m.get("id").asString();
            }
        }
        throw new AssertionError("no module " + name);
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
