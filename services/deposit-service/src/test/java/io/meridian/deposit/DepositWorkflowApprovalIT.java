package io.meridian.deposit;

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
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.ArrayList;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, account openings, overdraft facility
 * requests and account closures open approval requests in workflow-service, and decisions arriving from
 * workflow-service are applied with the approver as principal, still under the four-eyes rule. Ledger-service and
 * the product catalog are stubbed as in {@link DepositFlowIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true",
        "meridian.deposit.ledger-id=0199a000-0000-7000-8000-0000000000aa",
        "meridian.deposit.country=GB", "meridian.deposit.withholding-tax-rate=0.20", "meridian.deposit.bank-code=MERI", "meridian.deposit.branch-code=000001",
        "meridian.deposit.gl.interest-expense=0199a000-0000-7000-8000-0000000000e1",
        "meridian.deposit.gl.interest-income=0199a000-0000-7000-8000-0000000000e2",
        "meridian.deposit.gl.fee-income=0199a000-0000-7000-8000-0000000000e3",
        "meridian.deposit.gl.tax-payable=0199a000-0000-7000-8000-0000000000e4",
        "meridian.deposit.gl.clearing=0199a000-0000-7000-8000-0000000000e5"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DepositWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000d007";
    static final String CUSTOMER = "0199a000-0000-7000-8000-00000000c001";
    static final String PRODUCT = "0199a000-0000-7000-8000-00000000f001";
    static final String VERSION = "0199a000-0000-7000-8000-00000000f002";

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
    FakeLedger ledger;

    @BeforeAll
    void createLedger() {
        ledger = new FakeLedger(json, Clock.systemUTC());
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubServiceClient() {
        requests.clear();
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> ledger.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> {
            String service = i.getArgument(0);
            String path = i.getArgument(1);
            if ("tenant-service".equals(service) && path.startsWith("/v1/legal-entities/")) {
                return json.readTree("{\"id\":\"" + path.substring(19) + "\",\"code\":\"MAIN\",\"country\":\"GB\"}");
            }
            return "product-catalog-service".equals(service) ? catalog(path) : ledger.get(service, path);
        });
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            requests.add((Map<String, Object>) inv.getArgument(2));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        });
        when(serviceClient.get(eq("workflow-service"), startsWith("/v1/approval-requests?")))
                .thenReturn(json.readTree("{\"data\":[]}"));
    }

    private JsonNode catalog(String path) {
        if (path.equals("/v1/product-versions/" + VERSION)) {
            return json.readTree("{\"id\":\"" + VERSION + "\",\"product_id\":\"" + PRODUCT + "\",\"status\":\"PUBLISHED\"}");
        }
        if (path.startsWith("/v1/product-versions/" + VERSION + "/interest-rates")) {
            return json.readTree("""
                    {"data":[
                      {"rate_kind":"CREDIT_INTEREST","rate_type":"FIXED","fixed_rate":"0.0365","day_count":"ACT_365","capitalisation_frequency":"MONTHLY"},
                      {"rate_kind":"OVERDRAFT_INTEREST","rate_type":"FIXED","fixed_rate":"0.1825","day_count":"ACT_365"}],"has_more":false}""");
        }
        if (path.startsWith("/v1/product-versions/" + VERSION + "/fees")) {
            return json.readTree("""
                    {"data":[{"code":"ACCOUNT_CLOSURE","calculation_method":"FLAT","flat_amount":"10.00"}],"has_more":false}""");
        }
        throw new io.meridian.common.error.ApiException(404, "not_found", path);
    }

    @Test
    void accountOpeningApprovedFromWorkflowUnderFourEyes() throws Exception {
        JsonNode opened = post("/v1/deposit-accounts", accountBody(), "maker.anna", 201);
        String id = opened.get("id").asString();
        Map<String, Object> req = lastRequest("deposit_account");
        assertThat(req).containsEntry("resource_id", id).containsEntry("action", "approve");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("account_number")).isEqualTo(opened.get("account_number").asString());

        dispatcher.dispatch(UUID.fromString(TENANT), decision("deposit_account", id, payload, "maker.anna"), Outcome.APPROVED);
        assertThat(get("/v1/deposit-accounts/" + id).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("deposit_account", id, payload, "checker.ben"), Outcome.APPROVED);
        JsonNode acc = get("/v1/deposit-accounts/" + id);
        assertThat(acc.get("status").asString()).isEqualTo("PENDING_FUNDING");
        assertThat(acc.hasNonNull("ledger_account_id")).isTrue();

        // redelivery is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision("deposit_account", id, payload, "checker.ben"), Outcome.APPROVED);
        assertThat(get("/v1/deposit-accounts/" + id).get("status").asString()).isEqualTo("PENDING_FUNDING");
    }

    @Test
    void accountOpeningRejectedFromWorkflowAndLateApprovalIgnored() throws Exception {
        String id = post("/v1/deposit-accounts", accountBody(), "maker.anna", 201).get("id").asString();
        Map<?, ?> payload = (Map<?, ?>) lastRequest("deposit_account").get("payload");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("deposit_account", id, payload, "checker.ben"), Outcome.REJECTED);
        assertThat(get("/v1/deposit-accounts/" + id).get("status").asString()).isEqualTo("REJECTED");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("deposit_account", id, payload, "checker.cy"), Outcome.APPROVED);
        assertThat(get("/v1/deposit-accounts/" + id).get("status").asString()).isEqualTo("REJECTED");
    }

    @Test
    void overdraftRoutedByAmountAndStaleLimitDecisionIgnored() throws Exception {
        String account = openActive();
        String body = """
                {"deposit_account_id":"%s","overdraft_type":"ARRANGED","limit_amount":"500.00","currency":"EUR",
                 "interest_rate":"0.15","starts_on":"2026-11-15"}""".formatted(account);
        String id = post("/v1/overdraft-facilities", body, "maker.anna", 201).get("id").asString();
        Map<String, Object> first = lastRequest("overdraft_facility");
        assertThat(first).containsEntry("resource_id", id).containsEntry("currency", "EUR");
        assertThat(new BigDecimal((String) first.get("amount"))).isEqualByComparingTo("500");
        Map<?, ?> firstPayload = (Map<?, ?>) first.get("payload");

        // the limit changes while pending: a new request is opened and the decision on the old one is ignored
        post("/v1/overdraft-facilities/" + id + "/change-limit", "{\"limit_amount\":\"300.00\",\"reason\":\"lower\"}",
                "maker.anna", 200);
        Map<String, Object> second = lastRequest("overdraft_facility");
        assertThat(new BigDecimal((String) second.get("amount"))).isEqualByComparingTo("300");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("overdraft_facility", id, firstPayload, "officer.dee"),
                Outcome.APPROVED);
        assertThat(get("/v1/overdraft-facilities/" + id).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        Map<?, ?> secondPayload = (Map<?, ?>) second.get("payload");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("overdraft_facility", id, secondPayload, "maker.anna"),
                Outcome.APPROVED);
        assertThat(get("/v1/overdraft-facilities/" + id).get("status").asString()).isEqualTo("PENDING_APPROVAL");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("overdraft_facility", id, secondPayload, "officer.dee"),
                Outcome.APPROVED);
        JsonNode f = get("/v1/overdraft-facilities/" + id);
        assertThat(f.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(f.get("approved_by").asString()).isEqualTo("officer.dee");
        assertThat(new BigDecimal(get("/v1/deposit-accounts/" + account).get("overdraft_limit").asString()))
                .isEqualByComparingTo("300");
    }

    @Test
    void overdraftRejectionFromWorkflowCancelsTheRequest() throws Exception {
        String account = openActive();
        String id = post("/v1/overdraft-facilities", """
                {"deposit_account_id":"%s","overdraft_type":"ARRANGED","limit_amount":"200.00","currency":"EUR",
                 "interest_rate":"0.15","starts_on":"2026-11-15"}""".formatted(account), "maker.anna", 201)
                .get("id").asString();
        Map<?, ?> payload = (Map<?, ?>) lastRequest("overdraft_facility").get("payload");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("overdraft_facility", id, payload, "officer.dee"),
                Outcome.REJECTED);
        assertThat(get("/v1/overdraft-facilities/" + id).get("status").asString()).isEqualTo("CANCELLED");
    }

    @Test
    void closureRejectedThenApprovedFromWorkflow() throws Exception {
        String account = openActive();
        UUID ledgerAccount = UUID.fromString(get("/v1/deposit-accounts/" + account).get("ledger_account_id").asString());
        ledger.credit(ledgerAccount, "10.00", null); // covers the closure fee: nothing to pay out

        String first = post("/v1/account-closures", """
                {"deposit_account_id":"%s","reason_code":"CUSTOMER_REQUEST"}""".formatted(account), "maker.anna", 201)
                .get("id").asString();
        Map<String, Object> req = lastRequest("account_closure");
        assertThat(req).containsEntry("resource_id", first).containsEntry("currency", "EUR");
        Map<?, ?> firstPayload = (Map<?, ?>) req.get("payload");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("account_closure", first, firstPayload, "checker.ben"),
                Outcome.REJECTED);
        assertThat(get("/v1/account-closures/" + first).get("status").asString()).isEqualTo("REJECTED");
        assertThat(get("/v1/deposit-accounts/" + account).get("status").asString()).isEqualTo("ACTIVE");
        // a late approval of the rejected closure does nothing
        dispatcher.dispatch(UUID.fromString(TENANT), decision("account_closure", first, firstPayload, "checker.cy"),
                Outcome.APPROVED);
        assertThat(get("/v1/account-closures/" + first).get("status").asString()).isEqualTo("REJECTED");

        String second = post("/v1/account-closures", """
                {"deposit_account_id":"%s","reason_code":"CUSTOMER_REQUEST"}""".formatted(account), "maker.anna", 201)
                .get("id").asString();
        Map<?, ?> payload = (Map<?, ?>) lastRequest("account_closure").get("payload");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("account_closure", second, payload, "maker.anna"),
                Outcome.APPROVED);
        assertThat(get("/v1/account-closures/" + second).get("status").asString()).isEqualTo("PENDING_APPROVAL");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("account_closure", second, payload, "checker.ben"),
                Outcome.APPROVED);
        assertThat(get("/v1/account-closures/" + second).get("status").asString()).isEqualTo("COMPLETED");
        assertThat(get("/v1/deposit-accounts/" + account).get("status").asString()).isEqualTo("CLOSED");
    }

    private Map<String, Object> lastRequest(String resourceType) {
        return requests.reversed().stream().filter(r -> resourceType.equals(r.get("resource_type"))).findFirst()
                .orElseThrow(() -> new AssertionError("no approval request for " + resourceType));
    }

    private ObjectNode decision(String type, String id, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", type);
        row.put("resource_id", id);
        row.put("action", "approve");
        row.put("maker", "maker.anna");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String accountBody() {
        return """
                {"account_type":"CURRENT","customer_id":"%s","product_id":"%s",
                 "product_version_id":"%s","legal_entity_id":"%s","currency":"EUR"}"""
                .formatted(CUSTOMER, PRODUCT, VERSION, UUID.randomUUID());
    }

    /** Opens, approves (directly) and activates an account. */
    private String openActive() throws Exception {
        String id = post("/v1/deposit-accounts", accountBody(), "maker.anna", 201).get("id").asString();
        post("/v1/deposit-accounts/" + id + "/approve", "{}", "checker.ben", 200);
        post("/v1/deposit-accounts/" + id + "/activate", null, "checker.ben", 200);
        return id;
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
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
                .header("X-Tenant-Id", TENANT).header("X-User", "ops").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
}
