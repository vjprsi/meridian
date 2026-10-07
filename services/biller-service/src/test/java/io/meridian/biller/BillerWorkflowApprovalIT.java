package io.meridian.biller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, a calculated biller settlement opens an
 * approval request in workflow-service, and the decision arriving from workflow-service is applied with the approver as
 * principal, still under the settlement's four-eyes rule. wallet-service is the stateful {@link FakeWalletService}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.biller.settlement-clearing-account-id=0199c000-0000-7000-8000-0000000000c2",
        "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BillerWorkflowApprovalIT {
    static final String TENANT = "0199c000-0000-7000-8000-00000000b0f1";

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
    FakeWalletService wallets;
    UUID payer;

    @BeforeAll
    void setUp() {
        wallets = new FakeWalletService(json);
        payer = wallets.wallet("KES", "10000");
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
            return wallets.post(service, path, i.getArgument(2), i.getArgument(3));
        });
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> "workflow-service".equals(i.getArgument(0))
                ? json.readTree("{\"data\":[]}") : wallets.get(i.getArgument(0), i.getArgument(1)));
    }

    @Test
    void settlementOpensRequestAndApprovalFromWorkflowApproves() throws Exception {
        String id = settlement("WFB1");
        Map<String, Object> req = requests.get(id);
        assertThat(req).containsEntry("resource_type", "biller_settlement").containsEntry("action", "approve")
                .containsEntry("currency", "KES");
        assertThat(new BigDecimal((String) req.get("amount"))).isEqualByComparingTo("100");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(new BigDecimal((String) payload.get("net_amount"))).isEqualByComparingTo("100");

        // the maker approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "maker"), Outcome.APPROVED);
        assertThat(get("/v1/biller-settlements/" + id).get("status").asString()).isEqualTo("CALCULATED");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "checker"), Outcome.APPROVED);
        JsonNode s = get("/v1/biller-settlements/" + id);
        assertThat(s.get("status").asString()).isEqualTo("APPROVED");
        assertThat(s.get("approved_by").asString()).isEqualTo("checker");

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "checker"), Outcome.APPROVED);
        assertThat(get("/v1/biller-settlements/" + id).get("status").asString()).isEqualTo("APPROVED");
        assertThat(post("/v1/biller-settlements/" + id + "/mark-paid", "{\"payment_reference\":\"EFT-WF1\"}", "checker", 200)
                .get("status").asString()).isEqualTo("PAID");
    }

    @Test
    void rejectionFromWorkflowReleasesThePaymentsAndStaleOrLateDecisionsAreIgnored() throws Exception {
        String rejectedId = settlement("WFB2");
        Map<?, ?> rejectedPayload = (Map<?, ?>) requests.get(rejectedId).get("payload");

        // an approval whose amount no longer matches the settlement does nothing
        ObjectNode stale = decision(rejectedId, rejectedPayload, "checker");
        ((ObjectNode) stale.get("payload")).put("net_amount", "999");
        dispatcher.dispatch(UUID.fromString(TENANT), stale, Outcome.APPROVED);
        assertThat(get("/v1/biller-settlements/" + rejectedId).get("status").asString()).isEqualTo("CALCULATED");

        // rejected in the inbox: REJECTED, and its payment is released for a new settlement of the period
        dispatcher.dispatch(UUID.fromString(TENANT), decision(rejectedId, rejectedPayload, "checker"), Outcome.REJECTED);
        JsonNode rejected = get("/v1/biller-settlements/" + rejectedId);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejected_by").asString()).isEqualTo("checker");
        assertThat(get("/v1/biller-settlements/" + rejectedId + "/payments").get("data")).isEmpty();
        dispatcher.dispatch(UUID.fromString(TENANT), decision(rejectedId, rejectedPayload, "checker"), Outcome.APPROVED);
        assertThat(get("/v1/biller-settlements/" + rejectedId).get("status").asString()).isEqualTo("REJECTED");

        String today = LocalDate.now(ZoneOffset.UTC).toString();
        JsonNode again = post("/v1/biller-settlements", "{\"biller_id\":\"" + rejected.get("biller_id").asString()
                + "\",\"period_start\":\"" + today + "\",\"period_end\":\"" + today + "\",\"currency\":\"KES\"}", "maker", 201);
        assertThat(again.get("payment_count").asInt()).isEqualTo(1);
        String id = again.get("id").asString();
        Map<?, ?> payload = (Map<?, ?>) requests.get(id).get("payload");

        // direct approval closes the inbox item after commit; a late workflow decision is then ignored
        post("/v1/biller-settlements/" + id + "/approve", "{}", "maker", 422);
        post("/v1/biller-settlements/" + id + "/approve", "{}", "checker2", 200);
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient, atLeastOnce()).get(eq("workflow-service"), path.capture());
        assertThat(path.getAllValues()).anyMatch(p -> p.contains("resource_id=" + id) && p.contains("status=PENDING"));
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "checker"), Outcome.APPROVED);
        JsonNode s = get("/v1/biller-settlements/" + id);
        assertThat(s.get("status").asString()).isEqualTo("APPROVED");
        assertThat(s.get("approved_by").asString()).isEqualTo("checker2");
    }

    private ObjectNode decision(String id, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", "biller_settlement");
        row.put("resource_id", id);
        row.put("action", "approve");
        row.put("maker", "maker");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    /** A biller with one completed 100 KES payment today, settled by "maker". */
    private String settlement(String code) throws Exception {
        UUID collection = wallets.wallet("KES", "0");
        String biller = post("/v1/billers", "{\"biller_code\":\"" + code + "\",\"name\":\"" + code
                + " Ltd\",\"category\":\"WATER\",\"country\":\"KE\",\"currency\":\"KES\",\"integration_type\":\"API\","
                + "\"min_amount\":\"10\",\"max_amount\":\"100000\",\"account_reference_checksum\":\"NONE\","
                + "\"settlement_wallet_id\":\"" + collection + "\"}", "ops", 201).get("id").asString();
        JsonNode p = post("/v1/bill-payments", "{\"biller_id\":\"" + biller + "\",\"payer_wallet_id\":\"" + payer
                + "\",\"account_reference\":\"123456\",\"amount\":\"100\",\"currency\":\"KES\"}", "alice", 201);
        assertThat(p.get("status").asString()).isEqualTo("COMPLETED");
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        JsonNode s = post("/v1/biller-settlements", "{\"biller_id\":\"" + biller + "\",\"period_start\":\"" + today
                + "\",\"period_end\":\"" + today + "\",\"currency\":\"KES\"}", "maker", 201);
        assertThat(s.get("status").asString()).isEqualTo("CALCULATED");
        return s.get("id").asString();
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
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
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
}
