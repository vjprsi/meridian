package io.meridian.notification;

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
import java.util.UUID;
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
 * Central maker-checker (G11) for notification templates: publish opens an approval request in workflow-service
 * through libs/meridian-approvals, and the decision coming back activates the template (or returns it to DRAFT)
 * with the approver as principal, under a four-eyes check.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.notifications.scheduler-enabled=false", "meridian.notifications.consumer-enabled=false",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class NotificationWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000c7";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @MockitoBean
    ServiceClient services;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void stubWorkflow() {
        when(services.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            ObjectNode row = json.valueToTree(inv.getArgument(2));
            row.put("id", UUID.randomUUID().toString());
            row.put("status", "PENDING");
            row.put("maker", "maker.anna");
            row.put("required_approvals", 1);
            row.put("approvals_received", 0);
            row.put("current_level", 1);
            row.put("row_version", 1);
            row.put("created_at", "2026-10-06T10:00:00Z");
            row.put("updated_at", "2026-10-06T10:00:00Z");
            return row;
        });
    }

    @Test
    void approvalFromWorkflowActivatesAndFourEyesStillApplies() throws Exception {
        String id = template("WF_WELCOME");
        JsonNode created = post("/v1/notification-templates/" + id + "/publish", null, 200, "maker.anna");
        assertThat(created.get("resource_type").asString()).isEqualTo("notification_template");
        assertThat(created.get("resource_id").asString()).isEqualTo(id);
        assertThat(created.get("payload").get("checksum").asString()).isNotBlank();

        // approved by the maker: refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision(created, "maker.anna"), Outcome.APPROVED);
        assertThat(status(id)).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(created, "checker.ben"), Outcome.APPROVED);
        assertThat(status(id)).isEqualTo("ACTIVE");

        // redelivery is a no-op (and a late rejection does not undo the activation)
        dispatcher.dispatch(UUID.fromString(TENANT), decision(created, "checker.ben"), Outcome.APPROVED);
        dispatcher.dispatch(UUID.fromString(TENANT), decision(created, "checker.ben"), Outcome.REJECTED);
        assertThat(status(id)).isEqualTo("ACTIVE");
    }

    @Test
    void rejectionFromWorkflowReturnsToDraftAndStaleDecisionIsIgnored() throws Exception {
        String id = template("WF_NOTICE");
        JsonNode created = post("/v1/notification-templates/" + id + "/publish", null, 200, "maker.anna");
        dispatcher.dispatch(UUID.fromString(TENANT), decision(created, "checker.ben"), Outcome.EXPIRED);
        assertThat(status(id)).isEqualTo("DRAFT");

        // reworked and republished: an approval for the old content (checksum) does nothing
        JsonNode t = get("/v1/notification-templates/" + id);
        HttpResponse<String> patch = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/v1/notification-templates/" + id))
                .header("X-Tenant-Id", TENANT).header("X-User", "maker.anna").header("Content-Type", "application/json")
                .header("If-Match", "\"" + t.get("row_version").asInt() + "\"")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"body\":\"Notice v2 {{name}}\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(patch.statusCode()).as(patch.body()).isEqualTo(200);
        post("/v1/notification-templates/" + id + "/publish", null, 200, "maker.anna");
        dispatcher.dispatch(UUID.fromString(TENANT), decision(created, "checker.ben"), Outcome.APPROVED);
        assertThat(status(id)).isEqualTo("PENDING_APPROVAL");
    }

    private String template(String code) throws Exception {
        return post("/v1/notification-templates", """
                {"code":"%s","channel":"SMS","category":"SERVICE","body":"Hello {{name}}"}""".formatted(code),
                201, "maker.anna").get("id").asString();
    }

    private ObjectNode decision(JsonNode created, String approver) {
        ObjectNode row = (ObjectNode) created.deepCopy();
        row.put("status", "APPROVED");
        row.put("updated_by", approver);
        return row;
    }

    private String status(String id) throws Exception {
        return get("/v1/notification-templates/" + id).get("status").asString();
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
