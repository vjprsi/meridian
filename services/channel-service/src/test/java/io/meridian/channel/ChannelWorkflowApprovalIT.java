package io.meridian.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Central maker-checker (G11) for USSD menu publication: submitting a draft opens an approval request carrying a
 * checksum of the tree; the decision publishes (four-eyes still applies) or rejects the submission (the menu stays
 * DRAFT), and a decision on content edited after submission is ignored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.channel.gateway-secret=wf-secret",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class ChannelWorkflowApprovalIT {
    static final String TENANT = "0199c000-0000-7000-8000-00000000c0f7";

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
    void stubWorkflow() {
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            lastRequest.set((Map<String, Object>) inv.getArgument(2));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        });
        when(serviceClient.get(eq("workflow-service"), startsWith("/v1/approval-requests?")))
                .thenReturn(json.readTree("{\"data\":[]}"));
    }

    @Test
    void submittedMenuIsRejectedThenReworkedAndPublishedFromWorkflow() throws Exception {
        String menu = post("/v1/ussd-menus", """
                {"code":"WF","name":"WF menu","short_code":"*777#","language":"en","root_node_key":"main"}""",
                201, "maker").get("id").asString();
        post("/v1/ussd-menu-nodes", """
                {"ussd_menu_id":"%s","node_key":"main","node_type":"MENU","label":"Welcome"}""".formatted(menu), 201, "maker");
        String bye = post("/v1/ussd-menu-nodes", """
                {"ussd_menu_id":"%s","node_key":"bye","parent_node_key":"main","option_number":1,"node_type":"END",
                 "label":"Goodbye"}""".formatted(menu), 201, "maker").get("id").asString();

        JsonNode submitted = post("/v1/ussd-menus/" + menu + "/submit", "{\"comment\":\"ready\"}", 200, "maker");
        assertThat(submitted.get("status").asString()).isEqualTo("DRAFT");
        assertThat(submitted.get("submitted_by").asString()).isEqualTo("maker");
        assertThat(lastRequest.get()).containsEntry("resource_type", "ussd_menu").containsEntry("resource_id", menu)
                .containsEntry("action", "publish");
        Map<?, ?> first = (Map<?, ?>) lastRequest.get().get("payload");
        assertThat(first.get("checksum")).isNotNull();

        dispatcher.dispatch(UUID.fromString(TENANT), decision(menu, first, "checker"), Outcome.REJECTED);
        JsonNode rejected = get("/v1/ussd-menus/" + menu);
        assertThat(rejected.get("status").asString()).isEqualTo("DRAFT");
        assertThat(rejected.get("rejected_by").asString()).isEqualTo("checker");
        assertThat(rejected.hasNonNull("submitted_at")).isFalse();

        // rework a node and resubmit; a decision on the first content is ignored
        send("PATCH", "/v1/ussd-menu-nodes/" + bye, "{\"label\":\"Thank you\"}", "maker",
                get("/v1/ussd-menu-nodes/" + bye).get("row_version").asString());
        post("/v1/ussd-menus/" + menu + "/submit", "{}", 200, "maker");
        Map<?, ?> second = (Map<?, ?>) lastRequest.get().get("payload");
        assertThat(second.get("checksum")).isNotEqualTo(first.get("checksum"));
        dispatcher.dispatch(UUID.fromString(TENANT), decision(menu, first, "checker"), Outcome.APPROVED);
        dispatcher.dispatch(UUID.fromString(TENANT), decision(menu, second, "maker"), Outcome.APPROVED);
        assertThat(get("/v1/ussd-menus/" + menu).get("status").asString()).isEqualTo("DRAFT");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(menu, second, "checker"), Outcome.APPROVED);
        JsonNode published = get("/v1/ussd-menus/" + menu);
        assertThat(published.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(published.get("published_by").asString()).isEqualTo("checker");
        assertThat(post("/v1/ussd-menus/" + menu + "/reject", "{\"reason\":\"x\"}", 409, "checker").get("code").asString())
                .isEqualTo("no_pending_submission");
    }

    @Test
    void anInvalidTreeCannotBeSubmittedAndTheSubmitterCannotReject() throws Exception {
        String menu = post("/v1/ussd-menus", """
                {"code":"WF2","name":"WF menu 2","short_code":"*778#","language":"en","root_node_key":"main"}""",
                201, "maker").get("id").asString();
        assertThat(post("/v1/ussd-menus/" + menu + "/submit", "{}", 422, "maker").get("code").asString())
                .isEqualTo("invalid_menu_tree");
        post("/v1/ussd-menu-nodes", """
                {"ussd_menu_id":"%s","node_key":"main","node_type":"END","label":"Closed"}""".formatted(menu), 201, "maker");
        post("/v1/ussd-menus/" + menu + "/submit", "{}", 200, "maker");
        assertThat(post("/v1/ussd-menus/" + menu + "/reject", "{\"reason\":\"mine\"}", 422, "maker").get("code").asString())
                .isEqualTo("four_eyes_violation");
        assertThat(post("/v1/ussd-menus/" + menu + "/reject", "{\"reason\":\"wording\"}", 200, "checker")
                .get("rejection_reason").asString()).isEqualTo("wording");
    }

    private ObjectNode decision(String menu, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", "ussd_menu");
        row.put("resource_id", menu);
        row.put("action", "publish");
        row.put("maker", "maker");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = send("POST", path, body, user, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "viewer").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String user, String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (ifMatch != null) {
            b.header("If-Match", "\"" + ifMatch + "\"");
        }
        HttpResponse<String> r = http.send(b.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if ("PATCH".equals(method)) {
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        }
        return r;
    }
}
