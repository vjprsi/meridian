package io.meridian.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import java.util.concurrent.atomic.AtomicInteger;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, certifying a card program submits its
 * go-live to workflow-service, and the decision arriving from workflow-service is applied with the approver as
 * principal, still under the program's four-eyes rule (the creator cannot approve its go-live).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class CardWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000ca71";
    static final String MAKER = "maker@bank";
    static final String CHECKER = "checker@bank";
    static final AtomicInteger SEQ = new AtomicInteger();

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
    FakePeers peers;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stub() {
        peers = new FakePeers(json);
        doAnswer(inv -> {
            if ("workflow-service".equals(inv.getArgument(0))) {
                if ("/v1/approval-requests".equals(inv.getArgument(1))) {
                    lastRequest.set((Map<String, Object>) inv.getArgument(2));
                }
                return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
            }
            return peers.post(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
        }).when(serviceClient).post(anyString(), anyString(), any(), any());
        doAnswer(inv -> "workflow-service".equals(inv.getArgument(0)) ? json.readTree("{\"data\":[]}")
                : peers.get(inv.getArgument(0), inv.getArgument(1))).when(serviceClient).get(anyString(), anyString());
    }

    @Test
    void certifyOpensGoLiveRequestAndApprovalFromWorkflowActivates() throws Exception {
        String program = certifiedProgram();

        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "card_program").containsEntry("resource_id", program)
                .containsEntry("action", "go_live");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("row_version")).isNotNull();
        assertThat(payload.get("bin")).isEqualTo(binOf(program));
        assertThat(payload.containsKey("pan")).isFalse();

        // the creator approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision(program, payload, MAKER), Outcome.APPROVED);
        assertThat(status(program)).isEqualTo("CERTIFICATION");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(program, payload, CHECKER), Outcome.APPROVED);
        JsonNode p = get("/v1/card-programs/" + program);
        assertThat(p.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(p.get("activated_by").asString()).isEqualTo(CHECKER);
        int version = p.get("row_version").asInt();

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision(program, payload, CHECKER), Outcome.APPROVED);
        JsonNode again = get("/v1/card-programs/" + program);
        assertThat(again.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(again.get("row_version").asInt()).isEqualTo(version);
    }

    @Test
    void rejectionFromWorkflowReturnsToDraftAndStaleDecisionIsIgnored() throws Exception {
        String program = certifiedProgram();
        Map<?, ?> payload = (Map<?, ?>) lastRequest.get().get("payload");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(program, payload, CHECKER), Outcome.REJECTED);
        JsonNode p = get("/v1/card-programs/" + program);
        assertThat(p.get("status").asString()).isEqualTo("DRAFT");
        assertThat(p.get("status_reason").asString()).contains("rejected in workflow request");
        // redelivered rejection: nothing more happens
        dispatcher.dispatch(UUID.fromString(TENANT), decision(program, payload, CHECKER), Outcome.REJECTED);
        assertThat(get("/v1/card-programs/" + program).get("row_version").asInt()).isEqualTo(p.get("row_version").asInt());

        // re-certified: an approval for the old submission (old row_version) does nothing
        post("/v1/card-programs/" + program + "/certify", null, 200, MAKER);
        Map<?, ?> fresh = (Map<?, ?>) lastRequest.get().get("payload");
        assertThat(fresh.get("row_version")).isNotEqualTo(payload.get("row_version"));
        dispatcher.dispatch(UUID.fromString(TENANT), decision(program, payload, CHECKER), Outcome.APPROVED);
        assertThat(status(program)).isEqualTo("CERTIFICATION");

        // expiry / cancellation leave the program waiting; the current submission still applies afterwards
        dispatcher.dispatch(UUID.fromString(TENANT), decision(program, fresh, CHECKER), Outcome.EXPIRED);
        assertThat(status(program)).isEqualTo("CERTIFICATION");
        dispatcher.dispatch(UUID.fromString(TENANT), decision(program, fresh, CHECKER), Outcome.APPROVED);
        assertThat(status(program)).isEqualTo("ACTIVE");
    }

    @Test
    void directGoLiveClosesTheInboxItemAfterCommit() throws Exception {
        String program = certifiedProgram();
        post("/v1/card-programs/" + program + "/activate", "{\"reason\":\"go live\"}", 422, MAKER);
        verify(serviceClient, never()).get(eq("workflow-service"), anyString());
        post("/v1/card-programs/" + program + "/activate", "{\"reason\":\"go live\"}", 200, CHECKER);
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_type=card_program").contains("resource_id=" + program)
                .contains("status=PENDING");
        verify(serviceClient, never()).post(eq("workflow-service"), startsWith("/v1/approval-requests/"), any(), anyString());
    }

    private String certifiedProgram() throws Exception {
        int n = SEQ.incrementAndGet();
        String bin = "45397" + n;
        String program = post("/v1/card-programs", """
                {"code":"WF-%d","name":"WF %d program","legal_entity_id":"0199a000-0000-7000-8000-0000000000e1",
                 "scheme":"VISA","funding_model":"DEBIT","bin":"%s","bin_range_start":"%s0","bin_range_end":"%s9",
                 "pan_length":16,"currency":"EUR","country":"DE"}""".formatted(n, n, bin, bin, bin), 201, MAKER)
                .get("id").asString();
        assertThat(post("/v1/card-programs/" + program + "/certify", null, 200, MAKER).get("status").asString())
                .isEqualTo("CERTIFICATION");
        return program;
    }

    private String binOf(String program) throws Exception {
        return get("/v1/card-programs/" + program).get("bin").asString();
    }

    private String status(String program) throws Exception {
        return get("/v1/card-programs/" + program).get("status").asString();
    }

    private ObjectNode decision(String program, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", "card_program");
        row.put("resource_id", program);
        row.put("action", "go_live");
        row.put("maker", MAKER);
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build(),
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
