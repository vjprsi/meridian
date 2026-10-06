package io.meridian.audit;

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
 * Central maker-checker (G11) for data exports: creating an export opens an approval request in workflow-service
 * through libs/meridian-approvals, and the decision coming back runs (or cancels) the export with the approver as
 * principal, still under the four-eyes rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.audit.listener-enabled=false",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class AuditWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000a7";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @MockitoBean
    ServiceClient services;

    final HttpClient http = HttpClient.newHttpClient();
    final AtomicReference<ObjectNode> lastRequest = new AtomicReference<>();

    @BeforeEach
    void stubWorkflow() {
        when(services.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            ObjectNode row = json.valueToTree(inv.getArgument(2));
            row.put("id", UUID.randomUUID().toString());
            row.put("status", "PENDING");
            row.put("maker", "maker");
            lastRequest.set(row);
            return row;
        });
    }

    @Test
    void approvalFromWorkflowRunsTheExportOnceAndFourEyesStillApplies() throws Exception {
        JsonNode export = create("Inspection A");
        String id = export.get("id").asString();
        JsonNode request = lastRequest.get();
        assertThat(request.get("resource_type").asString()).isEqualTo("data_export_request");
        assertThat(request.get("resource_id").asString()).isEqualTo(id);
        assertThat(request.get("action").asString()).isEqualTo("execute");
        assertThat(export.get("approval_request_id").asString()).isEqualTo(request.get("id").asString());

        // approved by the requester: refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision(request, "maker"), Outcome.APPROVED);
        assertThat(status(id)).isEqualTo("QUEUED");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(request, "checker"), Outcome.APPROVED);
        JsonNode done = get("/v1/data-exports/" + id);
        assertThat(done.get("status").asString()).isEqualTo("SUCCEEDED");

        // redelivery is a no-op
        dispatcher.dispatch(UUID.fromString(TENANT), decision(request, "checker"), Outcome.APPROVED);
        assertThat(get("/v1/data-exports/" + id).get("document_id").asString()).isEqualTo(done.get("document_id").asString());
    }

    @Test
    void rejectionFromWorkflowCancelsAndStaleDecisionIsIgnored() throws Exception {
        String stale = create("Inspection B").get("id").asString();
        ObjectNode foreign = decision(lastRequest.get(), "checker");
        foreign.put("id", UUID.randomUUID().toString());
        dispatcher.dispatch(UUID.fromString(TENANT), foreign, Outcome.APPROVED);
        dispatcher.dispatch(UUID.fromString(TENANT), foreign, Outcome.REJECTED);
        assertThat(status(stale)).isEqualTo("QUEUED");
        dispatcher.dispatch(UUID.fromString(TENANT), decision(lastRequest.get(), "checker"), Outcome.EXPIRED);
        assertThat(status(stale)).isEqualTo("QUEUED");

        String rejected = create("Inspection C").get("id").asString();
        JsonNode request = lastRequest.get();
        dispatcher.dispatch(UUID.fromString(TENANT), decision(request, "checker"), Outcome.REJECTED);
        assertThat(status(rejected)).isEqualTo("CANCELLED");
        // a late approval of the rejected request cannot run the cancelled export
        dispatcher.dispatch(UUID.fromString(TENANT), decision(request, "checker"), Outcome.APPROVED);
        assertThat(status(rejected)).isEqualTo("CANCELLED");
    }

    private JsonNode create(String purpose) throws Exception {
        return post("/v1/data-exports", """
                {"export_type":"REGULATOR_EXTRACT","requested_for":"%s"}""".formatted(purpose), 201, "maker");
    }

    private ObjectNode decision(JsonNode request, String approver) {
        ObjectNode row = (ObjectNode) request.deepCopy();
        row.put("status", "APPROVED");
        row.put("updated_by", approver);
        return row;
    }

    private String status(String id) throws Exception {
        return get("/v1/data-exports/" + id).get("status").asString();
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
