package io.meridian.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, submitting a commission plan and creating a
 * payout run open approval requests in workflow-service, and decisions arriving from workflow-service are applied with
 * the approver as principal, still under the four-eyes rule. wallet-service and agent-network-service are the stateful
 * {@link FakeNetwork}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.commission.listener-enabled=false",
        "meridian.commission.payout-source-wallet-id=0199c000-0000-7000-8000-0000000000ab",
        "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommissionWorkflowApprovalIT {
    static final String TENANT = "0199c000-0000-7000-8000-00000000c0f1";
    static final UUID POOL = UUID.fromString("0199c000-0000-7000-8000-0000000000ab");
    static final UUID AGENT = UUID.randomUUID();
    static final UUID AGENT_WALLET = UUID.randomUUID();

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final Map<String, Map<String, Object>> requests = new ConcurrentHashMap<>();
    FakeNetwork net;

    @BeforeAll
    void setUp() {
        net = new FakeNetwork(json);
        net.wallet(POOL, "KES", "1000");
        net.wallet(AGENT_WALLET, "KES", "0");
        net.agent(AGENT, "AGENT", null, null, AGENT_WALLET);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stub() {
        when(serviceClient.post(anyString(), anyString(), any(), any())).thenAnswer(i -> {
            String service = i.getArgument(0);
            String path = i.getArgument(1);
            if ("workflow-service".equals(service)) {
                if (path.equals("/v1/approval-requests")) {
                    Map<String, Object> body = (Map<String, Object>) i.getArgument(2);
                    requests.put((String) body.get("resource_id"), body);
                }
                return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
            }
            return net.post(service, path, i.getArgument(2), i.getArgument(3));
        });
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> "workflow-service".equals(i.getArgument(0))
                ? json.readTree("{\"data\":[]}") : net.get(i.getArgument(0), i.getArgument(1)));
    }

    @Test
    void planSubmitOpensRequestAndApprovalFromWorkflowActivates() throws Exception {
        String plan = draftPlan("WF-PLAN-A", "UGX");
        post("/v1/commission-plans/" + plan + "/submit", null, "maker", 200);

        Map<String, Object> req = requests.get(plan);
        assertThat(req).containsEntry("resource_type", "commission_plan").containsEntry("action", "approve");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("submitted_at")).isNotNull();
        assertThat(payload.get("code")).isEqualTo("WF-PLAN-A");

        // the maker approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision("commission_plan", plan, payload, "maker"), Outcome.APPROVED);
        assertThat(get("/v1/commission-plans/" + plan).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("commission_plan", plan, payload, "checker"), Outcome.APPROVED);
        JsonNode p = get("/v1/commission-plans/" + plan);
        assertThat(p.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(p.get("approved_by").asString()).isEqualTo("checker");

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision("commission_plan", plan, payload, "checker"), Outcome.APPROVED);
        assertThat(get("/v1/commission-plans/" + plan).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void planRejectionFromWorkflowAndStaleDecisionIgnored() throws Exception {
        String plan = draftPlan("WF-PLAN-B", "UGX");
        post("/v1/commission-plans/" + plan + "/submit", null, "maker", 200);
        Map<?, ?> first = (Map<?, ?>) requests.get(plan).get("payload");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("commission_plan", plan, first, "checker"), Outcome.REJECTED);
        JsonNode rejected = get("/v1/commission-plans/" + plan);
        assertThat(rejected.get("status").asString()).isEqualTo("DRAFT");
        assertThat(rejected.get("rejection_reason").asString()).contains("workflow");

        // resubmitted: an approval of the first submission does nothing, one of the new submission applies
        post("/v1/commission-plans/" + plan + "/submit", null, "maker", 200);
        Map<?, ?> second = (Map<?, ?>) requests.get(plan).get("payload");
        assertThat(second.get("submitted_at")).isNotEqualTo(first.get("submitted_at"));
        dispatcher.dispatch(UUID.fromString(TENANT), decision("commission_plan", plan, first, "checker"), Outcome.APPROVED);
        assertThat(get("/v1/commission-plans/" + plan).get("status").asString()).isEqualTo("PENDING_APPROVAL");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("commission_plan", plan, second, "checker"), Outcome.APPROVED);
        assertThat(get("/v1/commission-plans/" + plan).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void directPlanDecisionClosesTheInboxItem() throws Exception {
        String plan = draftPlan("WF-PLAN-C", "TZS");
        post("/v1/commission-plans/" + plan + "/submit", null, "maker", 200);
        post("/v1/commission-plans/" + plan + "/reject", "{\"reason\":\"no\"}", "checker", 200);
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient, atLeastOnce()).get(eq("workflow-service"), path.capture());
        assertThat(path.getAllValues()).anyMatch(p -> p.contains("resource_id=" + plan) && p.contains("status=PENDING"));
        verify(serviceClient, never()).post(eq("workflow-service"), startsWith("/v1/approval-requests/"), any(), anyString());
    }

    @Test
    void payoutRunRejectedAndApprovedFromWorkflow() throws Exception {
        String plan = draftPlan("WF-PAY", "KES");
        post("/v1/commission-plans/" + plan + "/submit", null, "maker", 200);
        post("/v1/commission-plans/" + plan + "/approve", "{}", "checker", 200);
        post("/v1/commission-earnings/accrue", accrue(UUID.randomUUID().toString()), "wallet", 200);

        String run1 = post("/v1/commission-payout-runs", runBody(), "maker", 201).get("id").asString();
        Map<String, Object> req = requests.get(run1);
        assertThat(req).containsEntry("resource_type", "commission_payout_run").containsEntry("currency", "KES");
        assertThat(new BigDecimal((String) req.get("amount"))).isEqualByComparingTo("10");
        Map<?, ?> p1 = (Map<?, ?>) req.get("payload");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("commission_payout_run", run1, p1, "checker"), Outcome.REJECTED);
        JsonNode cancelled = get("/v1/commission-payout-runs/" + run1);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancel_reason").asString()).contains("workflow");
        // a late approval of the cancelled run is ignored
        dispatcher.dispatch(UUID.fromString(TENANT), decision("commission_payout_run", run1, p1, "checker"), Outcome.APPROVED);
        assertThat(get("/v1/commission-payout-runs/" + run1).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(net.balance(AGENT_WALLET)).isEqualByComparingTo("0");

        String run2 = post("/v1/commission-payout-runs", runBody(), "maker", 201).get("id").asString();
        Map<?, ?> p2 = (Map<?, ?>) requests.get(run2).get("payload");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("commission_payout_run", run2, p2, "maker"), Outcome.APPROVED);
        assertThat(get("/v1/commission-payout-runs/" + run2).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("commission_payout_run", run2, p2, "checker"), Outcome.APPROVED);
        JsonNode done = get("/v1/commission-payout-runs/" + run2);
        assertThat(done.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.get("approved_by").asString()).isEqualTo("checker");
        assertThat(net.balance(AGENT_WALLET)).isEqualByComparingTo("10");

        // redelivery does not pay twice
        dispatcher.dispatch(UUID.fromString(TENANT), decision("commission_payout_run", run2, p2, "checker"), Outcome.APPROVED);
        assertThat(net.balance(AGENT_WALLET)).isEqualByComparingTo("10");
        assertThat(net.balance(POOL)).isEqualByComparingTo("990");
    }

    private ObjectNode decision(String type, String id, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", type);
        row.put("resource_id", id);
        row.put("action", "approve");
        row.put("maker", "maker");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String draftPlan(String code, String currency) throws Exception {
        String plan = post("/v1/commission-plans", """
                {"code":"%s","name":"%s","applies_to":"AGENT","currency":"%s","payout_frequency":"DAILY",
                 "valid_from":"2020-01-01"}""".formatted(code, code, currency), "maker", 201).get("id").asString();
        post("/v1/commission-rules", "{\"commission_plan_id\":\"" + plan + "\",\"name\":\"Cash-in\",\"txn_type\":\"CASH_IN\","
                + "\"beneficiary\":\"AGENT\",\"basis\":\"FLAT\",\"flat_amount\":\"10\",\"base\":\"AMOUNT\"}", "maker", 201);
        return plan;
    }

    private String accrue(String txn) {
        return "{\"wallet_transaction_id\":\"" + txn + "\",\"txn_type\":\"CASH_IN\",\"status\":\"COMPLETED\","
                + "\"amount\":\"1000\",\"fee_amount\":\"0\",\"currency\":\"KES\",\"channel\":\"AGENT_APP\","
                + "\"kyc_tier\":1,\"agent_id\":\"" + AGENT + "\",\"completed_at\":\"" + OffsetDateTime.now(ZoneOffset.UTC) + "\"}";
    }

    private String runBody() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return "{\"payout_frequency\":\"DAILY\",\"period_start\":\"" + today.minusDays(1) + "\",\"period_end\":\""
                + today.plusDays(1) + "\",\"currency\":\"KES\"}";
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "viewer").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
}
