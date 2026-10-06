package io.meridian.productcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, submitting a product version or a
 * product script opens an approval request in workflow-service, and the decision arriving from workflow-service is
 * applied with the approver as principal, still under the four-eyes rule and only for the submission it was opened for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class ProductCatalogWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000d7";
    static final String RUST = "#[no_mangle]\\npub extern \\\"C\\\" fn run(balance: i64, days: i64) -> i64 {\\n    balance * days / 36500\\n}";

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
    void versionSubmitOpensWorkflowRequestAndApprovalFromWorkflowApproves() throws Exception {
        String version = draftVersion("WF-A");
        post("/v1/product-versions/" + version + "/submit", "{\"comment\":\"launch\"}", 200, "maker.anna");

        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "product_version").containsEntry("resource_id", version)
                .containsEntry("action", "approve");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("checksum")).isNotNull();
        assertThat(payload.get("submitted_at")).isNotNull();

        // the maker approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision("product_version", version, payload, "maker.anna"), Outcome.APPROVED);
        assertThat(get("/v1/product-versions/" + version).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("product_version", version, payload, "checker.ben"), Outcome.APPROVED);
        JsonNode v = get("/v1/product-versions/" + version);
        assertThat(v.get("status").asString()).isEqualTo("APPROVED");
        assertThat(v.get("approved_by").asString()).isEqualTo("checker.ben");

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision("product_version", version, payload, "checker.ben"), Outcome.APPROVED);
        assertThat(get("/v1/product-versions/" + version).get("status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void versionRejectionFromWorkflowAndStaleDecisionIgnored() throws Exception {
        String version = draftVersion("WF-B");
        post("/v1/product-versions/" + version + "/submit", "{}", 200, "maker.anna");
        Map<?, ?> payload = (Map<?, ?>) lastRequest.get().get("payload");

        // a decision for different content (checksum) is ignored
        ObjectNode stale = decision("product_version", version, payload, "checker.ben");
        ((ObjectNode) stale.get("payload")).put("checksum", "stale");
        dispatcher.dispatch(UUID.fromString(TENANT), stale, Outcome.APPROVED);
        assertThat(get("/v1/product-versions/" + version).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("product_version", version, payload, "checker.ben"), Outcome.REJECTED);
        JsonNode v = get("/v1/product-versions/" + version);
        assertThat(v.get("status").asString()).isEqualTo("REJECTED");
        assertThat(v.get("rejected_by").asString()).isEqualTo("checker.ben");

        // a late approval after the rejection does nothing
        dispatcher.dispatch(UUID.fromString(TENANT), decision("product_version", version, payload, "checker.cy"), Outcome.APPROVED);
        assertThat(get("/v1/product-versions/" + version).get("status").asString()).isEqualTo("REJECTED");
    }

    @Test
    void directVersionDecisionClosesTheInboxItemAfterCommit() throws Exception {
        String version = draftVersion("WF-C");
        post("/v1/product-versions/" + version + "/submit", "{}", 200, "maker.anna");
        post("/v1/product-versions/" + version + "/approve", "{}", 200, "checker.ben");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_type=product_version").contains("resource_id=" + version);
    }

    @Test
    void scriptApprovalFromWorkflowAndResubmissionMakesOldDecisionStale() throws Exception {
        String version = draftVersion("WF-D");
        String script = post("/v1/product-scripts", """
                {"product_version_id":"%s","name":"wf-boost","hook":"ACCRUAL","language":"RUST","source_code":"%s"}"""
                .formatted(version, RUST), 201, "maker.anna").get("id").asString();
        post("/v1/product-scripts/" + script + "/compile", null, 200, "maker.anna");
        post("/v1/product-scripts/" + script + "/submit", "{}", 200, "maker.anna");
        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "product_script").containsEntry("resource_id", script)
                .containsEntry("action", "activate");
        Map<?, ?> first = (Map<?, ?>) req.get("payload");
        assertThat(first.get("wasm_sha256")).isNotNull();

        dispatcher.dispatch(UUID.fromString(TENANT), decision("product_script", script, first, "maker.anna"), Outcome.APPROVED);
        assertThat(get("/v1/product-scripts/" + script).get("status").asString()).isEqualTo("PENDING_APPROVAL");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("product_script", script, first, "checker.ben"), Outcome.REJECTED);
        assertThat(get("/v1/product-scripts/" + script).get("status").asString()).isEqualTo("REJECTED");

        // same source resubmitted: the decision on the first submission no longer applies
        post("/v1/product-scripts/" + script + "/compile", null, 200, "maker.anna");
        post("/v1/product-scripts/" + script + "/submit", "{}", 200, "maker.anna");
        Map<?, ?> second = (Map<?, ?>) lastRequest.get().get("payload");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("product_script", script, first, "checker.ben"), Outcome.APPROVED);
        assertThat(get("/v1/product-scripts/" + script).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("product_script", script, second, "checker.ben"), Outcome.APPROVED);
        JsonNode s = get("/v1/product-scripts/" + script);
        assertThat(s.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(s.get("approved_by").asString()).isEqualTo("checker.ben");
    }

    private ObjectNode decision(String type, String id, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", type);
        row.put("resource_id", id);
        row.put("action", "product_script".equals(type) ? "activate" : "approve");
        row.put("maker", "maker.anna");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    /** A product created from a fresh template; returns its DRAFT version. */
    private String draftVersion(String code) throws Exception {
        String template = post("/v1/product-templates", """
                {"code":"TPL_%s","name":"Workflow savings","family":"SAVINGS",
                 "editable_fields":["interest_rates","fees"],
                 "default_definition":{
                   "parameters":{"dormancy.inactive_days":365,"statement.frequency":"MONTHLY"},
                   "interest_rates":[{"rate_kind":"CREDIT_INTEREST","rate_type":"TIERED","effective_from":"2020-01-01",
                     "day_count":"ACT_365","capitalisation_frequency":"MONTHLY",
                     "tiers":[{"from_amount":0,"to_amount":1000,"rate":"0.01"},{"from_amount":1000,"rate":"0.02"}]}],
                   "limits":[{"limit_code":"ATM_DAILY","period":"DAILY","metric":"AMOUNT","max_value":"1000"}]}}"""
                .formatted(code.replace('-', '_')), 201, "maker.anna").get("id").asString();
        String product = post("/v1/products/from-template", """
                {"template_id":"%s","code":"SAV-%s","name":"Saver %s","currency":"USD","legal_entity_id":"%s"}"""
                .formatted(template, code, code, UUID.randomUUID()), 200, "maker.anna").get("id").asString();
        return get("/v1/products/" + product + "/versions").get("data").get(0).get("id").asString();
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
