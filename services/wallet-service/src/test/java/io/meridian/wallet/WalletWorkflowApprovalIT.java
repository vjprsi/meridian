package io.meridian.wallet;

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
import java.time.Clock;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, a reversal request, a checker-verified
 * bank link and a staff-verified PIN reset each open an approval request in workflow-service, and the decision
 * arriving from workflow-service is applied with the approver as principal, still under the four-eyes rules.
 * ledger-service is the {@link FakeLedger}; limits / fraud are stubbed as allowing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true",
        "meridian.wallet.ledger-id=0199b000-0000-7000-8000-0000000000aa",
        "meridian.wallet.gl.fee-income=0199b000-0000-7000-8000-0000000000e1",
        "meridian.wallet.gl.tax-payable=0199b000-0000-7000-8000-0000000000e2",
        "meridian.wallet.gl.bank-clearing=0199b000-0000-7000-8000-0000000000e3",
        "meridian.wallet.pin.iterations=1000"})
@Import(MeridianTestContainers.class)
class WalletWorkflowApprovalIT {
    static final String TENANT = "0199b000-0000-7000-8000-00000000a0c7";

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
    FakeLedger ledger;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubServiceClient() {
        ledger = new FakeLedger(json, Clock.systemUTC());
        when(serviceClient.post(anyString(), anyString(), any(), any())).thenAnswer(i -> {
            String service = i.getArgument(0);
            String path = i.getArgument(1);
            return switch (service) {
                case "workflow-service" -> {
                    if (path.equals("/v1/approval-requests")) {
                        approvalRequests.add((Map<String, Object>) i.getArgument(2));
                    }
                    yield json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
                }
                case "ledger-service" -> ledger.post(service, path, i.getArgument(2), i.getArgument(3));
                case "limits-service" -> json.createObjectNode().put("id", UUID.randomUUID().toString())
                        .put("allowed", true).put("status", "RESERVED");
                case "fraud-service" -> json.createObjectNode().put("id", UUID.randomUUID().toString())
                        .put("decision", "ALLOW").put("score", 10);
                case "notification-service" -> json.createObjectNode().put("id", UUID.randomUUID().toString())
                        .put("status", "QUEUED");
                default -> throw new IllegalArgumentException("unexpected POST " + service + path);
            };
        });
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> {
            String service = i.getArgument(0);
            if ("workflow-service".equals(service)) {
                return json.readTree("{\"data\":[]}");
            }
            return ledger.get(service, i.getArgument(1));
        });
    }

    // ------------------------------------------------------------------ reversal

    @Test
    void reversalApprovedFromWorkflowWithFourEyesAndRedeliveryIgnored() throws Exception {
        String txn = p2p("+254711000001", "+254711000002", "300");
        post("/v1/wallet-transactions/" + txn + "/request-reversal", "{\"reason\":\"wrong number\"}", 200, "ops.maker");

        Map<String, Object> req = last("wallet_transaction");
        assertThat(req).containsEntry("resource_id", txn).containsEntry("action", "reverse")
                .containsEntry("amount", "300.00").containsEntry("currency", "KES");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("requested_at")).isNotNull();
        assertThat(payload.get("reversal_amount")).isEqualTo("300.00");

        // the maker approving in the inbox is still refused by the four-eyes rule
        dispatch("wallet_transaction", txn, "reverse", payload, "ops.maker", Outcome.APPROVED);
        assertThat(get("/v1/wallet-transactions/" + txn).get("status").asString()).isEqualTo("COMPLETED");

        dispatch("wallet_transaction", txn, "reverse", payload, "ops.checker", Outcome.APPROVED);
        JsonNode t = get("/v1/wallet-transactions/" + txn);
        assertThat(t.get("status").asString()).isEqualTo("REVERSED");
        long reversals = ledger.calls("POST", "/v1/journal-entries").size();

        // redelivery of the same decision is harmless: no second compensating entry
        dispatch("wallet_transaction", txn, "reverse", payload, "ops.checker", Outcome.APPROVED);
        assertThat(ledger.calls("POST", "/v1/journal-entries")).hasSize((int) reversals);
        assertThat(get("/v1/wallet-transactions/" + txn).get("status").asString()).isEqualTo("REVERSED");
    }

    @Test
    void reversalRejectedFromWorkflowAndStaleDecisionIgnored() throws Exception {
        String txn = p2p("+254711000003", "+254711000004", "150");
        post("/v1/wallet-transactions/" + txn + "/request-reversal", "{\"reason\":\"dispute\"}", 200, "ops.maker");
        Map<?, ?> first = (Map<?, ?>) last("wallet_transaction").get("payload");

        dispatch("wallet_transaction", txn, "reverse", first, "ops.checker", Outcome.REJECTED);
        JsonNode t = get("/v1/wallet-transactions/" + txn);
        assertThat(t.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(t.path("reversal_requested_by").asString(null)).isNull();

        // a new request; an approval of the old (declined) submission does nothing
        post("/v1/wallet-transactions/" + txn + "/request-reversal", "{\"reason\":\"again\",\"amount\":\"50\"}", 200,
                "ops.maker");
        dispatch("wallet_transaction", txn, "reverse", first, "ops.checker", Outcome.APPROVED);
        t = get("/v1/wallet-transactions/" + txn);
        assertThat(t.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(t.get("reversal_requested_by").asString()).isEqualTo("ops.maker");
        assertThat(new java.math.BigDecimal(t.get("reversed_amount").asString())).isZero();

        // a direct decision closes the inbox item after commit
        post("/v1/wallet-transactions/" + txn + "/reverse", "{\"reason\":\"ok\"}", 200, "ops.checker");
        verify(serviceClient, atLeastOnce()).get(eq("workflow-service"), contains("resource_id=" + txn));
    }

    // ------------------------------------------------------------------ bank links

    @Test
    void manualBankLinkVerifiedOrRejectedFromWorkflow() throws Exception {
        String wallet = wallet("+254711000005", "Dan Mwangi");
        String link = post("/v1/wallet-bank-links", """
                {"wallet_id":"%s","bank_code":"KCBLKENX","account_number":"2200110011","currency":"KES",
                 "verification_method":"MANUAL"}""".formatted(wallet), 201, "ops.maker").get("id").asString();
        Map<String, Object> req = last("wallet_bank_link");
        assertThat(req).containsEntry("resource_id", link).containsEntry("action", "verify");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("account_number")).isEqualTo("2200110011");

        dispatch("wallet_bank_link", link, "verify", payload, "ops.maker", Outcome.APPROVED);
        assertThat(get("/v1/wallet-bank-links/" + link).get("status").asString()).isEqualTo("PENDING_VERIFICATION");
        dispatch("wallet_bank_link", link, "verify", payload, "ops.checker", Outcome.APPROVED);
        JsonNode l = get("/v1/wallet-bank-links/" + link);
        assertThat(l.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(l.get("verified_by").asString()).isEqualTo("ops.checker");
        // a late rejection of the same request is ignored
        dispatch("wallet_bank_link", link, "verify", payload, "ops.other", Outcome.REJECTED);
        assertThat(get("/v1/wallet-bank-links/" + link).get("status").asString()).isEqualTo("ACTIVE");

        String other = post("/v1/wallet-bank-links", """
                {"wallet_id":"%s","bank_code":"EQBLKENA","account_number":"3300220022","currency":"KES",
                 "verification_method":"OPEN_BANKING"}""".formatted(wallet), 201, "ops.maker").get("id").asString();
        Map<?, ?> p2 = (Map<?, ?>) last("wallet_bank_link").get("payload");
        dispatch("wallet_bank_link", other, "verify", p2, "ops.checker", Outcome.REJECTED);
        assertThat(get("/v1/wallet-bank-links/" + other).get("status").asString()).isEqualTo("REMOVED");

        // name enquiry links never go to workflow-service
        int before = approvalRequests.size();
        post("/v1/wallet-bank-links", """
                {"wallet_id":"%s","bank_code":"MERI","account_number":"0099","currency":"KES","verification_method":"MICRO_DEPOSIT"}"""
                .formatted(wallet), 201, "ops.maker");
        assertThat(approvalRequests).hasSize(before);
    }

    // ------------------------------------------------------------------ PIN resets

    @Test
    void staffVerifiedPinResetDecidedFromWorkflowWithoutSecrets() throws Exception {
        String wallet = wallet("+254711000006", "Eve Akinyi");
        String reset = post("/v1/wallet-pin-resets", """
                {"wallet_id":"%s","channel":"USSD","verification_method":"SECURITY_QUESTIONS"}""".formatted(wallet),
                201, "ops.maker").get("id").asString();
        Map<String, Object> req = last("wallet_pin_reset");
        assertThat(req).containsEntry("resource_id", reset).containsEntry("action", "verify");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.keySet()).noneMatch(k -> k.toString().contains("pin") || k.toString().contains("otp")
                || k.toString().contains("hash"));

        dispatch("wallet_pin_reset", reset, "verify", payload, "ops.maker", Outcome.APPROVED);
        assertThat(get("/v1/wallet-pin-resets/" + reset).get("status").asString()).isEqualTo("REQUESTED");
        dispatch("wallet_pin_reset", reset, "verify", payload, "ops.checker", Outcome.APPROVED);
        assertThat(get("/v1/wallet-pin-resets/" + reset).get("status").asString()).isEqualTo("VERIFIED");
        dispatch("wallet_pin_reset", reset, "verify", payload, "ops.checker", Outcome.REJECTED);
        assertThat(get("/v1/wallet-pin-resets/" + reset).get("status").asString()).isEqualTo("VERIFIED");

        String second = post("/v1/wallet-pin-resets", """
                {"wallet_id":"%s","channel":"USSD","verification_method":"SECURITY_QUESTIONS"}""".formatted(wallet),
                201, "ops.maker").get("id").asString();
        dispatch("wallet_pin_reset", second, "verify", (Map<?, ?>) last("wallet_pin_reset").get("payload"),
                "ops.checker", Outcome.REJECTED);
        assertThat(get("/v1/wallet-pin-resets/" + second).get("status").asString()).isEqualTo("REJECTED");
    }

    // ------------------------------------------------------------------ helpers

    private String p2p(String payerMsisdn, String payeeMsisdn, String amount) throws Exception {
        String payer = wallet(payerMsisdn, "Payer " + payerMsisdn);
        wallet(payeeMsisdn, "Payee " + payeeMsisdn);
        ledger.credit(UUID.fromString(get("/v1/wallets/" + payer).get("ledger_account_id").asString()), "10000", null);
        return post("/v1/wallet-transactions/p2p", """
                {"payer_wallet_id":"%s","payee_msisdn":"%s","amount":"%s","currency":"KES"}"""
                .formatted(payer, payeeMsisdn, amount), 200, "customer").get("id").asString();
    }

    private String wallet(String msisdn, String name) throws Exception {
        String id = post("/v1/wallets", """
                {"wallet_type":"CUSTOMER","display_name":"%s","currency":"KES","msisdn":"%s"}""".formatted(name, msisdn),
                201, "ops").get("id").asString();
        post("/v1/wallets/" + id + "/activate", "{}", 200, "ops");
        return id;
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
        row.put("maker", "ops.maker");
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
