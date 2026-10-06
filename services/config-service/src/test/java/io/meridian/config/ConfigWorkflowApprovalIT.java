package io.meridian.config;

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
 * Central maker-checker (G11) for config versions: submit opens an approval request in workflow-service through
 * libs/meridian-approvals, and an approval coming back activates the version with the approver as principal,
 * still under the four-eyes rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class ConfigWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000c0f07";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final AtomicReference<ObjectNode> lastRequest = new AtomicReference<>();

    @BeforeEach
    void stubWorkflow() {
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            ObjectNode row = json.valueToTree(inv.getArgument(2));
            row.put("id", UUID.randomUUID().toString());
            row.put("status", "PENDING");
            row.put("maker", "alice");
            lastRequest.set(row);
            return row;
        });
    }

    @Test
    void approvalFromWorkflowActivatesOnceAndFourEyesStillApplies() throws Exception {
        String id = draft("wf_limits");
        JsonNode created = post("/v1/config/entries/" + id + "/submit", "{\"change_reason\":\"raise\"}", "alice", 200);
        assertThat(created.get("resource_type").asString()).isEqualTo("config_entry");
        assertThat(created.get("action").asString()).isEqualTo("activate");
        assertThat(created.get("payload").get("value").get("daily_limit").asInt()).isEqualTo(100);
        assertThat(created.get("payload").get("diff").get("format").asString()).isEqualTo("json-patch");

        // approved by the maker: refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision(created, "alice"), Outcome.APPROVED);
        assertThat(status(id)).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(created, "bob"), Outcome.APPROVED);
        assertThat(status(id)).isEqualTo("ACTIVE");
        JsonNode history = get("/v1/config/entries/" + id + "/history").get("data");
        assertThat(history.toString()).contains("ACTIVATED").contains("\"approved_by\":\"bob\"");

        // redelivery is a no-op
        dispatcher.dispatch(UUID.fromString(TENANT), decision(created, "bob"), Outcome.APPROVED);
        assertThat(get("/v1/config/entries/" + id + "/history").get("data")).hasSize(history.size());
    }

    @Test
    void rejectedOrStaleDecisionsChangeNothing() throws Exception {
        String id = draft("wf_rejected");
        JsonNode created = post("/v1/config/entries/" + id + "/submit", "{\"change_reason\":\"try\"}", "alice", 200);
        dispatcher.dispatch(UUID.fromString(TENANT), decision(created, "bob"), Outcome.REJECTED);
        assertThat(status(id)).isEqualTo("PENDING_APPROVAL");

        // an approval of some other (older) request for this version is ignored
        ObjectNode stale = decision(created, "bob");
        stale.put("id", UUID.randomUUID().toString());
        dispatcher.dispatch(UUID.fromString(TENANT), stale, Outcome.APPROVED);
        assertThat(status(id)).isEqualTo("PENDING_APPROVAL");
    }

    private String draft(String key) throws Exception {
        return post("/v1/config/entries", """
                {"namespace":"payments","config_key":"%s","value":{"daily_limit":100},
                 "value_schema":{"type":"object","properties":{"daily_limit":{"type":"number","minimum":0}}}}"""
                .formatted(key), "alice", 201).get("id").asString();
    }

    private ObjectNode decision(JsonNode created, String approver) {
        ObjectNode row = (ObjectNode) created.deepCopy();
        row.put("status", "APPROVED");
        row.put("updated_by", approver);
        return row;
    }

    private String status(String id) throws Exception {
        return get("/v1/config/entries/" + id).get("status").asString();
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
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
