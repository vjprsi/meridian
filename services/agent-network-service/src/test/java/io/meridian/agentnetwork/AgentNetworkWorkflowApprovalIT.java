package io.meridian.agentnetwork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.approvals.ApprovalDecision.Outcome;
import io.meridian.approvals.ApprovalEventsDispatcher;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, float requests, cash-in-transit
 * requests and agent KYB documents open approval requests in workflow-service, and decisions arriving from
 * workflow-service are applied with the approver as principal, still under each resource's four-eyes rule.
 * workflow-service is an in-memory fake (open requests, idempotent per key, cancel); wallet-service is the
 * {@link FakeWalletService} used by the flow test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.agent-network.treasury-wallet-id=0199c000-0000-7000-8000-0000000000f2",
        "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentNetworkWorkflowApprovalIT {
    static final String TENANT = "0199c000-0000-7000-8000-00000000a0c7";
    static final UUID TREASURY = UUID.fromString("0199c000-0000-7000-8000-0000000000f2");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakeWalletService wallets;
    /** workflow-service approval requests by idempotency key. */
    final Map<String, ObjectNode> workflow = Collections.synchronizedMap(new LinkedHashMap<>());
    final List<String> workflowCalls = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    void setUp() {
        wallets = new FakeWalletService(json);
        wallets.wallet(TREASURY, "TREASURY", "KES", "1000000");
    }

    @BeforeEach
    void stub() {
        when(serviceClient.post(anyString(), anyString(), any(), any())).thenAnswer(i -> {
            if ("workflow-service".equals(i.getArgument(0))) {
                return workflowPost(i.getArgument(1), i.getArgument(2), i.getArgument(3));
            }
            return wallets.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3));
        });
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> {
            if ("workflow-service".equals(i.getArgument(0))) {
                return workflowGet(i.getArgument(1));
            }
            return wallets.get(i.getArgument(0), i.getArgument(1));
        });
    }

    // ------------------------------------------------------------------ float requests

    @Test
    void floatRequestApprovedFromWorkflowUnderFourEyesAndRedeliveryIsHarmless() throws Exception {
        String agent = activeSuperAgent("Float One Ltd");
        UUID floatWallet = UUID.fromString(get("/v1/agents/" + agent).get("float_wallet_id").asString());
        String req = post("/v1/agent-float-requests", topUp(agent, floatWallet, "25000"), "maker.anna", 201).get("id").asString();

        ObjectNode open = open("agent_float_request", req);
        assertThat(open.get("action").asString()).isEqualTo("approve");
        assertThat(new BigDecimal(open.get("amount").asString())).isEqualByComparingTo("25000");
        assertThat(open.get("currency").asString()).isEqualTo("KES");
        assertThat(open.get("payload").get("request_type").asString()).isEqualTo("TOP_UP");
        assertThat(open.get("payload").get("target_wallet_id").asString()).isEqualTo(floatWallet.toString());

        // the maker approving in the inbox is still refused by the four-eyes rule
        dispatch(open, "maker.anna", Outcome.APPROVED);
        assertThat(get("/v1/agent-float-requests/" + req).get("status").asString()).isEqualTo("REQUESTED");
        assertThat(wallets.balance(floatWallet)).isEqualByComparingTo("0");

        dispatch(open, "checker.ben", Outcome.APPROVED);
        JsonNode done = get("/v1/agent-float-requests/" + req);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("approved_by").asString()).isEqualTo("checker.ben");
        assertThat(wallets.balance(floatWallet)).isEqualByComparingTo("25000");

        // redelivery of the same decision does not move money twice
        dispatch(open, "checker.ben", Outcome.APPROVED);
        assertThat(get("/v1/agent-float-requests/" + req).get("status").asString()).isEqualTo("COMPLETED");
        assertThat(wallets.balance(floatWallet)).isEqualByComparingTo("25000");
    }

    @Test
    void floatRequestRejectedFromWorkflowAndDirectDecisionClosesInboxItem() throws Exception {
        String agent = activeSuperAgent("Float Two Ltd");
        UUID floatWallet = UUID.fromString(get("/v1/agents/" + agent).get("float_wallet_id").asString());
        String rejected = post("/v1/agent-float-requests", topUp(agent, floatWallet, "1000"), "maker.anna", 201).get("id").asString();
        dispatch(open("agent_float_request", rejected), "checker.ben", Outcome.REJECTED);
        JsonNode r = get("/v1/agent-float-requests/" + rejected);
        assertThat(r.get("status").asString()).isEqualTo("REJECTED");
        assertThat(r.get("rejection_reason").asString()).contains("workflow request");

        // decided directly on the service's endpoint: the open workflow request is cancelled
        String direct = post("/v1/agent-float-requests", topUp(agent, floatWallet, "2000"), "maker.anna", 201).get("id").asString();
        ObjectNode open = open("agent_float_request", direct);
        post("/v1/agent-float-requests/" + direct + "/approve", "{}", "checker.ben", 200);
        assertThat(open.get("status").asString()).isEqualTo("CANCELLED");
        // a late approval from the inbox is ignored (already COMPLETED, no second transfer)
        dispatch(open, "checker.cleo", Outcome.APPROVED);
        assertThat(get("/v1/agent-float-requests/" + direct).get("approved_by").asString()).isEqualTo("checker.ben");
        assertThat(wallets.balance(floatWallet)).isEqualByComparingTo("2000");
    }

    // ------------------------------------------------------------------ cash management

    @Test
    void cashRequestApprovalFollowsChangesAndStaleDecisionIsIgnored() throws Exception {
        String agent = post("/v1/agents", agentBody("Cash Co"), "maker.anna", 201).get("id").asString();
        String outlet = post("/v1/agent-outlets", """
                {"agent_id":"%s","name":"Cash Co Main","country":"KE","latitude":"-1.30","longitude":"36.80","services":["CASH_IN"]}"""
                .formatted(agent), "maker.anna", 201).get("id").asString();
        String cmr = post("/v1/cash-management-requests", """
                {"agent_outlet_id":"%s","request_type":"CASH_DELIVERY","amount":"50000","currency":"KES"}""".formatted(outlet),
                "maker.anna", 201).get("id").asString();
        ObjectNode first = open("cash_management_request", cmr);
        assertThat(new BigDecimal(first.get("amount").asString())).isEqualByComparingTo("50000");

        // the maker changes the amount: the old inbox item is withdrawn and a new one opened
        patch("/v1/cash-management-requests/" + cmr, "{\"amount\":\"60000\"}", "maker.anna", 200);
        assertThat(first.get("status").asString()).isEqualTo("CANCELLED");
        ObjectNode second = open("cash_management_request", cmr);
        assertThat(second.get("id").asString()).isNotEqualTo(first.get("id").asString());
        assertThat(new BigDecimal(second.get("amount").asString())).isEqualByComparingTo("60000");

        // an approval for the superseded submission does nothing
        dispatch(first, "checker.ben", Outcome.APPROVED);
        assertThat(get("/v1/cash-management-requests/" + cmr).get("status").asString()).isEqualTo("REQUESTED");
        // four-eyes still applies to the current one
        dispatch(second, "maker.anna", Outcome.APPROVED);
        assertThat(get("/v1/cash-management-requests/" + cmr).get("status").asString()).isEqualTo("REQUESTED");
        dispatch(second, "checker.ben", Outcome.APPROVED);
        JsonNode approved = get("/v1/cash-management-requests/" + cmr);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("approved_by").asString()).isEqualTo("checker.ben");

        String other = post("/v1/cash-management-requests", """
                {"agent_outlet_id":"%s","request_type":"CASH_PICKUP","amount":"7000","currency":"KES"}""".formatted(outlet),
                "maker.anna", 201).get("id").asString();
        dispatch(open("cash_management_request", other), "checker.ben", Outcome.REJECTED);
        JsonNode cancelled = get("/v1/cash-management-requests/" + other);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancel_reason").asString()).contains("workflow request");
    }

    // ------------------------------------------------------------------ KYB documents

    @Test
    void kybDocumentVerifiedFromWorkflowAndRejectionLeavesItUnverified() throws Exception {
        String agent = post("/v1/agents", agentBody("Docs Ltd"), "maker.anna", 201).get("id").asString();
        String doc = document(agent);
        ObjectNode open = open("agent_kyc_document", doc);
        assertThat(open.get("action").asString()).isEqualTo("verify");
        assertThat(open.get("payload").get("document_type").asString()).isEqualTo("BUSINESS_LICENSE");

        dispatch(open, "maker.anna", Outcome.APPROVED);
        assertThat(get("/v1/agent-kyc-documents/" + doc).get("verified").asBoolean()).isFalse();
        dispatch(open, "checker.ben", Outcome.APPROVED);
        JsonNode verified = get("/v1/agent-kyc-documents/" + doc);
        assertThat(verified.get("verified").asBoolean()).isTrue();
        assertThat(verified.get("verified_by").asString()).isEqualTo("checker.ben");
        dispatch(open, "checker.cleo", Outcome.APPROVED);
        assertThat(get("/v1/agent-kyc-documents/" + doc).get("verified_by").asString()).isEqualTo("checker.ben");

        String rejected = document(agent);
        dispatch(open("agent_kyc_document", rejected), "checker.ben", Outcome.REJECTED);
        assertThat(get("/v1/agent-kyc-documents/" + rejected).get("verified").asBoolean()).isFalse();
        // deleting a document withdraws its inbox item
        String other = document(agent);
        ObjectNode pending = open("agent_kyc_document", other);
        assertThat(raw("DELETE", "/v1/agent-kyc-documents/" + other, null, "maker.anna").statusCode()).isEqualTo(204);
        assertThat(pending.get("status").asString()).isEqualTo("CANCELLED");
    }

    // ------------------------------------------------------------------ helpers

    /** An ACTIVE super agent (KYB verified and activated directly by a checker), with float wallet opened. */
    private String activeSuperAgent(String name) throws Exception {
        String agent = post("/v1/agents", agentBody(name), "maker.anna", 201).get("id").asString();
        String doc = document(agent);
        post("/v1/agent-kyc-documents/" + doc + "/verify", "{}", "checker.ben", 200);
        post("/v1/agents/" + agent + "/activate", "{}", "checker.ben", 200);
        return agent;
    }

    private String document(String agent) throws Exception {
        return post("/v1/agent-kyc-documents", """
                {"agent_id":"%s","document_type":"BUSINESS_LICENSE","document_id":"%s","expires_at":"2035-01-01"}"""
                .formatted(agent, UUID.randomUUID()), "maker.anna", 201).get("id").asString();
    }

    private static String agentBody(String name) {
        return "{\"agent_level\":\"SUPER_AGENT\",\"legal_name\":\"" + name
                + "\",\"contact_msisdn\":\"+254711000001\",\"currency\":\"KES\"}";
    }

    private static String topUp(String agent, UUID target, String amount) {
        return "{\"request_type\":\"TOP_UP\",\"requesting_agent_id\":\"" + agent + "\",\"target_wallet_id\":\"" + target
                + "\",\"amount\":\"" + amount + "\",\"currency\":\"KES\",\"bank_reference\":\"BNK-" + amount + "\"}";
    }

    /** The PENDING workflow request for the resource (exactly one). */
    private ObjectNode open(String type, String resourceId) {
        synchronized (workflow) {
            List<ObjectNode> found = workflow.values().stream()
                    .filter(r -> type.equals(r.get("resource_type").asString())
                            && resourceId.equals(r.get("resource_id").asString())
                            && "PENDING".equals(r.get("status").asString()))
                    .toList();
            assertThat(found).as("open %s %s", type, resourceId).hasSize(1);
            return found.getFirst();
        }
    }

    /** Decides the request in the fake workflow-service (no longer PENDING there) and delivers the decision. */
    private void dispatch(ObjectNode request, String approver, Outcome outcome) throws Exception {
        request.put("status", outcome.name());
        ObjectNode row = request.deepCopy();
        row.put("maker", "maker.anna");
        row.put("updated_by", approver);
        dispatcher.dispatch(UUID.fromString(TENANT), row, outcome);
    }

    private JsonNode workflowPost(String path, Object body, String key) {
        workflowCalls.add("POST " + path);
        if (path.equals("/v1/approval-requests")) {
            return workflow.computeIfAbsent(key, k -> {
                ObjectNode r = json.valueToTree(body);
                r.put("id", UUID.randomUUID().toString());
                r.put("status", "PENDING");
                return r;
            });
        }
        if (path.startsWith("/v1/approval-requests/") && path.endsWith("/cancel")) {
            String id = path.split("/")[3];
            synchronized (workflow) {
                workflow.values().stream().filter(r -> id.equals(r.get("id").asString()))
                        .forEach(r -> r.put("status", "CANCELLED"));
            }
            return json.createObjectNode().put("id", id).put("status", "CANCELLED");
        }
        throw new IllegalArgumentException("unexpected workflow POST " + path);
    }

    private JsonNode workflowGet(String path) {
        workflowCalls.add("GET " + path);
        String query = path.substring(path.indexOf('?') + 1);
        Map<String, String> q = new LinkedHashMap<>();
        for (String p : query.split("&")) {
            String[] kv = p.split("=", 2);
            q.put(kv[0], kv.length > 1 ? java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8) : "");
        }
        ObjectNode page = json.createObjectNode();
        var data = page.putArray("data");
        synchronized (workflow) {
            workflow.values().stream()
                    .filter(r -> r.get("status").asString().equals(q.getOrDefault("status", "PENDING")))
                    .filter(r -> r.get("resource_type").asString().equals(q.get("resource_type")))
                    .filter(r -> r.path("resource_id").asString("").equals(q.get("resource_id")))
                    .forEach(r -> data.add(r.deepCopy()));
        }
        return page;
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = raw("POST", path, body, user);
        assertThat(r.statusCode()).as("POST " + path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private void patch(String path, String body, String user, int expected) throws Exception {
        JsonNode current = get(path);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("If-Match", "\"" + current.get("row_version").asInt() + "\"")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as("PATCH " + path + " -> " + r.body()).isEqualTo(expected);
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = raw("GET", path, null, "viewer");
        assertThat(r.statusCode()).as("GET " + path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> raw(String method, String path, String body, String user) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json");
        if ("POST".equals(method)) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
