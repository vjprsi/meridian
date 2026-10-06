package io.meridian.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Central maker-checker (G11) for identity operations: a role permission grant opens an approval request in
 * workflow-service through libs/meridian-approvals, and the decision coming back is executed with the approver as
 * principal, still under the four-eyes rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class IdentityWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000d7";

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
    final AtomicReference<ObjectNode> lastApproval = new AtomicReference<>();

    @BeforeEach
    void stubWorkflow() {
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            ObjectNode row = json.valueToTree(inv.getArgument(2));
            row.put("id", UUID.randomUUID().toString());
            row.put("status", "PENDING");
            row.put("maker", TenantContext.principal());
            lastApproval.set(row);
            return row;
        });
    }

    @Test
    void grantApprovedInWorkflowIsExecutedOnceAndFourEyesStillApplies() throws Exception {
        String perm = seedPermission("wf.entry.read");
        String role = post("/v1/roles", "{\"code\":\"WF_READER\",\"name\":\"Reader\"}", 201, "admin").get("id").asString();
        JsonNode created = post("/v1/roles/" + role + "/permissions", "{\"permission_ids\":[\"" + perm + "\"]}", 200, "maker1");
        assertThat(created.get("resource_type").asString()).isEqualTo("role");
        assertThat(created.get("action").asString()).isEqualTo("add_permissions");
        assertThat(created.get("resource_id").asString()).isEqualTo(role);

        // approved by the maker: refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision(created, "maker1"), Outcome.APPROVED);
        assertThat(get("/v1/roles/" + role + "/permissions").get("data")).isEmpty();

        dispatcher.dispatch(UUID.fromString(TENANT), decision(created, "checker1"), Outcome.APPROVED);
        assertThat(get("/v1/roles/" + role + "/permissions").get("data")).hasSize(1);

        // redelivery is a no-op
        dispatcher.dispatch(UUID.fromString(TENANT), decision(created, "checker1"), Outcome.APPROVED);
        assertThat(get("/v1/roles/" + role + "/permissions").get("data")).hasSize(1);
    }

    @Test
    void rejectedOrStaleDecisionsChangeNothing() throws Exception {
        String perm = seedPermission("wf.entry.write");
        String role = post("/v1/roles", "{\"code\":\"WF_WRITER\",\"name\":\"Writer\"}", 201, "admin").get("id").asString();
        JsonNode created = post("/v1/roles/" + role + "/permissions", "{\"permission_ids\":[\"" + perm + "\"]}", 200, "maker1");
        dispatcher.dispatch(UUID.fromString(TENANT), decision(created, "checker1"), Outcome.REJECTED);
        dispatcher.dispatch(UUID.fromString(TENANT), decision(created, "checker1"), Outcome.CHANGES_REQUESTED);
        assertThat(get("/v1/roles/" + role + "/permissions").get("data")).isEmpty();

        // a decision whose request was never APPROVED (stale PENDING row) is not executed
        ObjectNode stale = decision(created, "checker1");
        stale.put("status", "PENDING");
        dispatcher.dispatch(UUID.fromString(TENANT), stale, Outcome.APPROVED);
        assertThat(get("/v1/roles/" + role + "/permissions").get("data")).isEmpty();
    }

    private ObjectNode decision(JsonNode created, String approver) {
        ObjectNode row = (ObjectNode) created.deepCopy();
        row.put("status", "APPROVED");
        row.put("updated_by", approver);
        return row;
    }

    private String seedPermission(String code) {
        String[] p = code.split("\\.");
        jdbc.sql("insert into permission (code, domain, resource, action) values (?, ?, ?, ?) on conflict (code) do nothing")
                .params(code, p[0], p[1], p[2]).update();
        return jdbc.sql("select id::text from permission where code = ?").param(code).query(String.class).single();
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
