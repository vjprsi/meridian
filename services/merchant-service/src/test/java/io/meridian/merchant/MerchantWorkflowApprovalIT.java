package io.meridian.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, merchant onboarding, refunds and
 * settlements open approval requests in workflow-service, and the decision arriving from workflow-service is applied
 * with the approver as principal, still under the four-eyes rules. wallet-service is the {@link FakeWalletService}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true",
        "meridian.merchant.fee-income-account-id=0199c000-0000-7000-8000-0000000000f1",
        "meridian.merchant.payment-link-base-url=https://pay.test/l"})
@Import(MeridianTestContainers.class)
class MerchantWorkflowApprovalIT {
    static final String TENANT = "0199c000-0000-7000-8000-00000000c0c7";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final List<Map<String, Object>> approvalRequests = new CopyOnWriteArrayList<>();
    FakeWalletService wallets;
    UUID customer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stub() {
        wallets = new FakeWalletService(json);
        customer = wallets.customer("Jane Doe", "KES", "50000", "4826");
        when(serviceClient.post(anyString(), anyString(), any(), any())).thenAnswer(i -> {
            if ("workflow-service".equals(i.getArgument(0))) {
                if ("/v1/approval-requests".equals(i.getArgument(1))) {
                    approvalRequests.add((Map<String, Object>) i.getArgument(2));
                }
                return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
            }
            return wallets.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3));
        });
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> "workflow-service".equals(i.getArgument(0))
                ? json.readTree("{\"data\":[]}") : wallets.get(i.getArgument(0), i.getArgument(1)));
    }

    @Test
    void onboardingApprovedOrRejectedFromWorkflow() throws Exception {
        String plan = plan();
        String merchant = post("/v1/merchants", merchantBody(plan), 201, "maker").get("id").asString();
        Map<String, Object> req = last("merchant");
        assertThat(req).containsEntry("resource_id", merchant).containsEntry("action", "activate");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("merchant_code")).isNotNull();

        // the maker approving in the inbox is still refused by the four-eyes rule
        dispatch("merchant", merchant, "activate", payload, "maker", Outcome.APPROVED);
        assertThat(get("/v1/merchants/" + merchant).get("status").asString()).isEqualTo("ONBOARDING");

        dispatch("merchant", merchant, "activate", payload, "checker", Outcome.APPROVED);
        assertThat(get("/v1/merchants/" + merchant).get("status").asString()).isEqualTo("ACTIVE");
        long walletsOpened = wallets.calls.stream().filter(c -> c.path().equals("/v1/wallets")).count();

        // redelivery (or a late rejection) of the same request is harmless
        dispatch("merchant", merchant, "activate", payload, "checker", Outcome.APPROVED);
        dispatch("merchant", merchant, "activate", payload, "checker", Outcome.REJECTED);
        assertThat(get("/v1/merchants/" + merchant).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(wallets.calls.stream().filter(c -> c.path().equals("/v1/wallets")).count()).isEqualTo(walletsOpened);

        String rejected = post("/v1/merchants", merchantBody(plan), 201, "maker").get("id").asString();
        Map<?, ?> p2 = (Map<?, ?>) last("merchant").get("payload");
        dispatch("merchant", rejected, "activate", p2, "checker", Outcome.CHANGES_REQUESTED);
        assertThat(get("/v1/merchants/" + rejected).get("status").asString()).isEqualTo("ONBOARDING");
        dispatch("merchant", rejected, "activate", p2, "checker", Outcome.REJECTED);
        assertThat(get("/v1/merchants/" + rejected).get("status").asString()).isEqualTo("TERMINATED");
    }

    @Test
    void refundsDecidedFromWorkflowWithFourEyesStaleAndRedelivery() throws Exception {
        String merchant = activeMerchant();
        String payment = pay(merchant, "1000");

        String refund = post("/v1/merchant-refunds", refundBody(merchant, payment, "300"), 201, "maker").get("id").asString();
        Map<String, Object> req = last("merchant_refund");
        assertThat(req).containsEntry("resource_id", refund).containsEntry("action", "approve")
                .containsEntry("amount", "300.00").containsEntry("currency", "KES");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");

        dispatch("merchant_refund", refund, "approve", payload, "maker", Outcome.APPROVED);
        assertThat(get("/v1/merchant-refunds/" + refund).get("status").asString()).isEqualTo("REQUESTED");
        dispatch("merchant_refund", refund, "approve", payload, "checker", Outcome.APPROVED);
        JsonNode done = get("/v1/merchant-refunds/" + refund);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("approved_by").asString()).isEqualTo("checker");
        dispatch("merchant_refund", refund, "approve", payload, "checker", Outcome.APPROVED);
        assertThat(wallets.transactions().stream().filter(c -> "REFUND".equals(c.body().get("txn_type").asString())))
                .hasSize(1);

        String rejected = post("/v1/merchant-refunds", refundBody(merchant, payment, "100"), 201, "maker").get("id").asString();
        dispatch("merchant_refund", rejected, "approve", (Map<?, ?>) last("merchant_refund").get("payload"), "checker",
                Outcome.REJECTED);
        assertThat(get("/v1/merchant-refunds/" + rejected).get("status").asString()).isEqualTo("REJECTED");

        // a decision whose fingerprint (amount) does not match the refund is ignored
        String other = post("/v1/merchant-refunds", refundBody(merchant, payment, "50"), 201, "maker").get("id").asString();
        ObjectNode stale = json.valueToTree(last("merchant_refund").get("payload"));
        stale.put("amount", "999.00");
        dispatch("merchant_refund", other, "approve", json.treeToValue(stale, Map.class), "checker", Outcome.APPROVED);
        assertThat(get("/v1/merchant-refunds/" + other).get("status").asString()).isEqualTo("REQUESTED");

        // a direct decision closes the inbox item after commit
        post("/v1/merchant-refunds/" + other + "/reject", "{\"reason\":\"no\"}", 200, "checker");
        verify(serviceClient, atLeastOnce()).get(eq("workflow-service"), contains("resource_id=" + other));
    }

    @Test
    void settlementApprovedFromWorkflowAndRejectionLeavesItCalculated() throws Exception {
        String merchant = activeMerchant();
        pay(merchant, "2000");
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        String id = post("/v1/merchant-settlements", "{\"merchant_id\":\"" + merchant + "\",\"period_start\":\"" + today
                + "\",\"period_end\":\"" + today + "\",\"currency\":\"KES\"}", 201, "maker").get("id").asString();
        Map<String, Object> req = last("merchant_settlement");
        assertThat(req).containsEntry("resource_id", id).containsEntry("action", "approve").containsEntry("currency", "KES");
        assertThat(req.get("amount")).isNotNull();
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");

        dispatch("merchant_settlement", id, "approve", payload, "checker", Outcome.REJECTED);
        assertThat(get("/v1/merchant-settlements/" + id).get("status").asString()).isEqualTo("CALCULATED");
        ObjectNode stale = json.valueToTree(payload);
        stale.put("net_amount", "1.00");
        dispatch("merchant_settlement", id, "approve", json.treeToValue(stale, Map.class), "checker", Outcome.APPROVED);
        assertThat(get("/v1/merchant-settlements/" + id).get("status").asString()).isEqualTo("CALCULATED");
        dispatch("merchant_settlement", id, "approve", payload, "maker", Outcome.APPROVED);
        assertThat(get("/v1/merchant-settlements/" + id).get("status").asString()).isEqualTo("CALCULATED");

        dispatch("merchant_settlement", id, "approve", payload, "checker", Outcome.APPROVED);
        JsonNode st = get("/v1/merchant-settlements/" + id);
        assertThat(st.get("status").asString()).isEqualTo("APPROVED");
        assertThat(st.get("approved_by").asString()).isEqualTo("checker");
        dispatch("merchant_settlement", id, "approve", payload, "other", Outcome.APPROVED);
        assertThat(get("/v1/merchant-settlements/" + id).get("approved_by").asString()).isEqualTo("checker");
    }

    // ------------------------------------------------------------------ helpers

    private String plan() throws Exception {
        return post("/v1/merchant-fee-plans", """
                {"code":"WF%s","name":"Workflow","currency":"KES","mdr_percentage":"0.01","fixed_fee":"0","min_fee":"1",
                 "max_fee":"50","refund_fee":"2","settlement_fee":"5","valid_from":"2020-01-01"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8)), 201, "maker").get("id").asString();
    }

    private String activeMerchant() throws Exception {
        String merchant = post("/v1/merchants", merchantBody(plan()), 201, "maker").get("id").asString();
        post("/v1/merchants/" + merchant + "/activate", "{}", 200, "checker");
        return merchant;
    }

    private String pay(String merchant, String amount) throws Exception {
        String qr = post("/v1/merchant-qr-codes", """
                {"merchant_id":"%s","qr_type":"STATIC","currency":"KES"}""".formatted(merchant), 201, "maker")
                .get("payload").asString();
        return post("/v1/merchant-qr-codes/pay", "{\"payload\":\"" + qr + "\",\"payer_wallet_id\":\"" + customer
                + "\",\"pin\":\"4826\",\"amount\":\"" + amount + "\"}", 200, "jane").get("id").asString();
    }

    private String merchantBody(String feePlan) {
        return "{\"legal_name\":\"Duka Ltd\",\"trading_name\":\"Duka\",\"merchant_type\":\"COMPANY\",\"mcc\":\"5411\","
                + "\"country\":\"KE\",\"city\":\"Nairobi\",\"currency\":\"KES\",\"merchant_fee_plan_id\":\"" + feePlan + "\"}";
    }

    private String refundBody(String merchant, String original, String amount) {
        return "{\"merchant_id\":\"" + merchant + "\",\"original_transaction_id\":\"" + original + "\",\"amount\":\"" + amount
                + "\",\"currency\":\"KES\",\"reason\":\"Customer returned goods\"}";
    }

    private Map<String, Object> last(String resourceType) {
        return approvalRequests.reversed().stream().filter(r -> resourceType.equals(r.get("resource_type")))
                .findFirst().orElseThrow(() -> new AssertionError("no approval request for " + resourceType));
    }

    private void dispatch(String type, String id, String action, Map<?, ?> payload, String approver, Outcome outcome)
            throws Exception {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", type);
        row.put("resource_id", id);
        row.put("action", action);
        row.put("maker", "maker");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        assertThat(dispatcher.dispatch(UUID.fromString(TENANT), row, outcome)).isTrue();
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "viewer").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
}
