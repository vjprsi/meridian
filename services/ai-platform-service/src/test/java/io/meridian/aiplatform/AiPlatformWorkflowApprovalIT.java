package io.meridian.aiplatform;

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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, submitting a model version and a copilot
 * proposing a write action open approval requests in workflow-service; decisions from workflow-service are applied
 * with the approver as principal, still under the four-eyes rules, and stale or redelivered decisions are ignored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class AiPlatformWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000a7";
    static final String DEV = "maker.anna";
    static final String RISK = "checker.ben";
    static final String SHA = "b".repeat(64);

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final List<Map<String, Object>> requests = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubWorkflow() {
        requests.clear();
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            requests.add((Map<String, Object>) inv.getArgument(2));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        });
        when(serviceClient.get(eq("workflow-service"), startsWith("/v1/approval-requests?")))
                .thenReturn(json.readTree("{\"data\":[]}"));
    }

    @Test
    void modelVersionApprovedFromWorkflowUnderFourEyes() throws Exception {
        String version = version("WF-A", "1.0.0");
        post("/v1/ai/model-versions/" + version + "/submit-for-approval", "{\"comment\":\"ready\"}", 200, DEV);

        Map<String, Object> req = last("ml_model_version");
        assertThat(req).containsEntry("resource_id", version).containsEntry("action", "approve");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("submitted_at")).isNotNull();
        assertThat(payload.get("artifact_sha256")).isEqualTo(SHA);

        // the developer approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision("ml_model_version", version, payload, DEV),
                Outcome.APPROVED);
        assertThat(get("/v1/ai/model-versions/" + version).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("ml_model_version", version, payload, RISK), Outcome.APPROVED);
        JsonNode v = get("/v1/ai/model-versions/" + version);
        assertThat(v.get("status").asString()).isEqualTo("APPROVED");
        assertThat(v.get("approved_by").asString()).isEqualTo(RISK);

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision("ml_model_version", version, payload, RISK), Outcome.APPROVED);
        dispatcher.dispatch(UUID.fromString(TENANT), decision("ml_model_version", version, payload, RISK), Outcome.REJECTED);
        assertThat(get("/v1/ai/model-versions/" + version).get("status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void modelVersionRejectedFromWorkflowAndStaleDecisionIgnored() throws Exception {
        String version = version("WF-B", "1.0.0");
        post("/v1/ai/model-versions/" + version + "/submit-for-approval", "{}", 200, DEV);
        Map<?, ?> payload = (Map<?, ?>) last("ml_model_version").get("payload");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("ml_model_version", version, payload, RISK), Outcome.REJECTED);
        JsonNode rejected = get("/v1/ai/model-versions/" + version);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejection_reason").asString()).startsWith("rejected in workflow request");

        // resubmitted: an approval for the old submission does nothing
        post("/v1/ai/model-versions/" + version + "/submit-for-approval", "{}", 200, DEV);
        dispatcher.dispatch(UUID.fromString(TENANT), decision("ml_model_version", version, payload, RISK), Outcome.APPROVED);
        assertThat(get("/v1/ai/model-versions/" + version).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        // expiry leaves it pending; the current submission can still be approved from workflow
        Map<?, ?> current = (Map<?, ?>) last("ml_model_version").get("payload");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("ml_model_version", version, current, RISK), Outcome.EXPIRED);
        assertThat(get("/v1/ai/model-versions/" + version).get("status").asString()).isEqualTo("PENDING_APPROVAL");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("ml_model_version", version, current, RISK), Outcome.APPROVED);
        assertThat(get("/v1/ai/model-versions/" + version).get("status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void directDecisionClosesTheInboxItemAfterCommit() throws Exception {
        String version = version("WF-C", "1.0.0");
        post("/v1/ai/model-versions/" + version + "/submit-for-approval", "{}", 200, DEV);
        post("/v1/ai/model-versions/" + version + "/approve", "{}", 200, RISK);
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_type=ml_model_version").contains("resource_id=" + version)
                .contains("status=PENDING");
        verify(serviceClient, never()).post(eq("workflow-service"), startsWith("/v1/approval-requests/"), any(), anyString());
    }

    @Test
    void copilotActionDecidedFromWorkflow() throws Exception {
        String copilot = activeCopilot("WF-COP-1");
        String proposal = post("/v1/ai/copilots/" + copilot + "/ask", askBody(), 201, DEV).get("id").asString();
        Map<String, Object> req = last("copilot_action");
        assertThat(req).containsEntry("resource_id", proposal);
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("tool")).isEqualTo("file_sar");
        assertThat(payload.get("response_sha256")).isNotNull();

        // the requester cannot decide, even from the inbox
        dispatcher.dispatch(UUID.fromString(TENANT), decision("copilot_action", proposal, payload, DEV), Outcome.APPROVED);
        assertThat(get("/v1/ai/copilot-interactions?parent_interaction_id=" + proposal).get("data")).isEmpty();

        dispatcher.dispatch(UUID.fromString(TENANT), decision("copilot_action", proposal, payload, RISK), Outcome.APPROVED);
        JsonNode decisions = get("/v1/ai/copilot-interactions?parent_interaction_id=" + proposal).get("data");
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).get("outcome").asString()).isEqualTo("ACTION_APPROVED");
        assertThat(decisions.get(0).get("approved_by").asString()).isEqualTo(RISK);

        // redelivered or contradictory late decisions are ignored
        dispatcher.dispatch(UUID.fromString(TENANT), decision("copilot_action", proposal, payload, RISK), Outcome.REJECTED);
        assertThat(get("/v1/ai/copilot-interactions?parent_interaction_id=" + proposal).get("data")).hasSize(1);

        // rejection from workflow on a second proposal
        String second = post("/v1/ai/copilots/" + copilot + "/ask", askBody(), 201, DEV).get("id").asString();
        Map<?, ?> p2 = (Map<?, ?>) last("copilot_action").get("payload");
        ObjectNode stale = decision("copilot_action", second, p2, RISK);
        ((ObjectNode) stale.get("payload")).put("response_sha256", "stale");
        dispatcher.dispatch(UUID.fromString(TENANT), stale, Outcome.APPROVED);
        assertThat(get("/v1/ai/copilot-interactions?parent_interaction_id=" + second).get("data")).isEmpty();
        dispatcher.dispatch(UUID.fromString(TENANT), decision("copilot_action", second, p2, RISK), Outcome.REJECTED);
        JsonNode rejected = get("/v1/ai/copilot-interactions?parent_interaction_id=" + second).get("data");
        assertThat(rejected).hasSize(1);
        assertThat(rejected.get(0).get("outcome").asString()).isEqualTo("ACTION_REJECTED");
    }

    private Map<String, Object> last(String resourceType) {
        for (int i = requests.size() - 1; i >= 0; i--) {
            if (resourceType.equals(requests.get(i).get("resource_type"))) {
                return requests.get(i);
            }
        }
        throw new AssertionError("no approval request for " + resourceType + " in " + requests);
    }

    private ObjectNode decision(String type, String resource, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", type);
        row.put("resource_id", resource);
        row.put("action", "approve");
        row.put("maker", DEV);
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String version(String code, String label) throws Exception {
        String model = post("/v1/ai/models", """
                {"code":"%s","name":"Fraud","model_type":"FRAUD","risk_tier":"LOW","owner_team":"fraud-ds",
                 "next_review_date":"2027-01-01"}""".formatted(code), 201, DEV).get("id").asString();
        return post("/v1/ai/model-versions", """
                {"ml_model_id":"%s","model_version":"%s","framework":"XGBOOST","artifact_uri":"s3://mlflow/fraud/%s",
                 "artifact_sha256":"%s","features":["txn_amount"],"metrics":{"AUC":0.91},
                 "bias_test_results":[{"attribute":"gender","metric":"DISPARATE_IMPACT","value":0.93,"threshold":0.8,"passed":true}]}"""
                .formatted(model, label, label, SHA), 201, DEV).get("id").asString();
    }

    private String activeCopilot(String code) throws Exception {
        String policy = post("/v1/ai/llm-policies", """
                {"name":"WF policy","tenant_opt_in":true,"allowed_models":["internal-llm"],"self_hosted_only":true,
                 "pii_redaction":true,"redaction_patterns":[],"max_tokens_per_request":2000}""", 201, DEV).get("id").asString();
        String copilot = post("/v1/ai/copilots", """
                {"code":"%s","name":"AML investigator","copilot_type":"AML_INVESTIGATION","system_prompt_ref":"prompts/aml/v3",
                 "model_alias":"internal-llm","allowed_tools":[{"name":"file_sar","mode":"WRITE","keywords":["file a sar"]}],
                 "data_scopes":["AML_CASE"],"llm_gateway_policy_id":"%s"}""".formatted(code, policy), 201, DEV).get("id").asString();
        post("/v1/ai/copilots/" + copilot + "/activate", "{}", 200, RISK);
        return copilot;
    }

    private String askBody() {
        return """
                {"prompt":"Many cash deposits below the threshold. Is this structuring, and should we file a SAR?",
                 "session_id":"s-wf","subject_type":"AML_CASE","subject_id":"%s",
                 "context":[{"id":"case-9","type":"AML_CASE","title":"Alert 9",
                   "text":"Customer made 14 cash deposits just below the reporting threshold. The pattern is consistent with structuring."}]}"""
                .formatted(UUID.randomUUID());
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
