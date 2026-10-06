package io.meridian.loan;

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
import io.meridian.common.error.ApiException;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, requesting a write-off or submitting a
 * restructure opens an approval request in workflow-service, and the decision arriving from workflow-service is
 * applied with the approver as principal, still under the four-eyes rule. Ledger, deposits and the product catalog
 * stay behind the mocked {@link ServiceClient} ({@link FakeLedger}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true",
        "meridian.loan.ledger-id=0199b000-0000-7000-8000-0000000000aa",
        "meridian.loan.gl.interest-receivable=0199b000-0000-7000-8000-0000000000e1",
        "meridian.loan.gl.interest-income=0199b000-0000-7000-8000-0000000000e2",
        "meridian.loan.gl.interest-suspense=0199b000-0000-7000-8000-0000000000e3",
        "meridian.loan.gl.fee-receivable=0199b000-0000-7000-8000-0000000000e4",
        "meridian.loan.gl.fee-income=0199b000-0000-7000-8000-0000000000e5",
        "meridian.loan.gl.penalty-receivable=0199b000-0000-7000-8000-0000000000e6",
        "meridian.loan.gl.penalty-income=0199b000-0000-7000-8000-0000000000e7",
        "meridian.loan.gl.write-off-expense=0199b000-0000-7000-8000-0000000000e8",
        "meridian.loan.gl.recovery-income=0199b000-0000-7000-8000-0000000000e9",
        "meridian.loan.gl.agency-commission=0199b000-0000-7000-8000-0000000000ea",
        "meridian.loan.gl.clearing=0199b000-0000-7000-8000-0000000000eb",
        "meridian.loan.gl.overpayment=0199b000-0000-7000-8000-0000000000ec",
        "meridian.loan.gl.tax-payable=0199b000-0000-7000-8000-0000000000ed",
        "meridian.loan.gl.waiver-expense=0199b000-0000-7000-8000-0000000000ee"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoanWorkflowApprovalIT {
    static final String TENANT = "0199b000-0000-7000-8000-00000000d0c7";
    static final String CUSTOMER = "0199b000-0000-7000-8000-00000000c0c7";
    static final String PRODUCT = "0199b000-0000-7000-8000-00000000f001";
    static final String VERSION = "0199b000-0000-7000-8000-00000000f002";
    static final String LEGAL_ENTITY = "0199b000-0000-7000-8000-00000000e001";

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
    final AtomicReference<String> lastRequestId = new AtomicReference<>();
    final Map<String, UUID> deposits = new HashMap<>();
    FakeLedger ledger;
    String account;

    @BeforeAll
    void setUp() {
        ledger = new FakeLedger(json, Clock.systemUTC());
        UUID ledgerAccount = ledger.openCustomerAccount("EUR");
        ledger.credit(ledgerAccount, "50000", null);
        account = UUID.randomUUID().toString();
        deposits.put(account, ledgerAccount);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubServices() {
        when(serviceClient.post(anyString(), anyString(), any(), any())).thenAnswer(i -> {
            if ("workflow-service".equals(i.getArgument(0))) {
                if ("/v1/approval-requests".equals(i.getArgument(1))) {
                    lastRequest.set((Map<String, Object>) i.getArgument(2));
                    lastRequestId.set(UUID.randomUUID().toString());
                    return json.readTree("{\"id\":\"" + lastRequestId.get() + "\",\"status\":\"PENDING\"}");
                }
                return json.readTree("{\"status\":\"CANCELLED\"}");
            }
            return ledger.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3));
        });
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> {
            String service = i.getArgument(0);
            String path = i.getArgument(1);
            return switch (service) {
                case "workflow-service" -> json.readTree("{\"data\":[]}");
                case "product-catalog-service" -> catalog(path);
                case "deposit-service" -> deposit(path);
                default -> ledger.get(service, path);
            };
        });
    }

    @Test
    void writeOffApprovedFromWorkflowPostsUnderFourEyesAndRedeliveryIsHarmless() throws Exception {
        String loan = disbursedLoan("2000");
        String id = post("/v1/loans/" + loan + "/write-off", "{\"write_off_type\":\"FULL\",\"reason\":\"uncollectable\"}",
                "maker.anna", 200).get("id").asString();

        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "loan_write_off").containsEntry("resource_id", id)
                .containsEntry("action", "approve").containsEntry("currency", "EUR");
        assertThat(new BigDecimal((String) req.get("amount"))).isGreaterThanOrEqualTo(new BigDecimal("2000"));
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("total_amount")).isNotNull();
        String requestId = lastRequestId.get();
        assertThat(get("/v1/loan-write-offs/" + id).get("approval_request_id").asString()).isEqualTo(requestId);

        // the maker approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision("loan_write_off", id, requestId, payload, "maker.anna"), Outcome.APPROVED);
        assertThat(get("/v1/loan-write-offs/" + id).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("loan_write_off", id, requestId, payload, "checker.ben"), Outcome.APPROVED);
        JsonNode w = get("/v1/loan-write-offs/" + id);
        assertThat(w.get("status").asString()).isEqualTo("POSTED");
        assertThat(w.get("approved_by").asString()).isEqualTo("checker.ben");
        assertThat(w.hasNonNull("journal_entry_id")).isTrue();
        assertThat(get("/v1/loans/" + loan).get("status").asString()).isEqualTo("WRITTEN_OFF");
        int postings = ledger.calls("POST", "/v1/journal-entries").size();

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision("loan_write_off", id, requestId, payload, "checker.ben"), Outcome.APPROVED);
        assertThat(get("/v1/loan-write-offs/" + id).get("status").asString()).isEqualTo("POSTED");
        assertThat(ledger.calls("POST", "/v1/journal-entries")).hasSize(postings);
    }

    @Test
    void restructureRejectedFromWorkflowAndStaleDecisionIsIgnored() throws Exception {
        String loan = disbursedLoan("5000");
        String first = proposeAndSubmit(loan);
        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "loan_restructure").containsEntry("resource_id", first)
                .containsEntry("currency", "EUR");
        Map<?, ?> firstPayload = (Map<?, ?>) req.get("payload");
        assertThat(firstPayload.get("submitted_by")).isEqualTo("maker.anna");
        String firstRequest = lastRequestId.get();

        dispatcher.dispatch(UUID.fromString(TENANT), decision("loan_restructure", first, firstRequest, firstPayload, "checker.ben"),
                Outcome.REJECTED);
        JsonNode rejected = get("/v1/loan-restructures/" + first);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejection_reason").asString()).contains(firstRequest);

        // a new restructure: a decision carrying another (stale) request id does nothing
        String second = proposeAndSubmit(loan);
        Map<?, ?> payload = (Map<?, ?>) lastRequest.get().get("payload");
        String secondRequest = lastRequestId.get();
        dispatcher.dispatch(UUID.fromString(TENANT), decision("loan_restructure", second, firstRequest, payload, "checker.ben"),
                Outcome.APPROVED);
        assertThat(get("/v1/loan-restructures/" + second).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        // the maker cannot approve their own restructure from the inbox either
        dispatcher.dispatch(UUID.fromString(TENANT), decision("loan_restructure", second, secondRequest, payload, "maker.anna"),
                Outcome.APPROVED);
        assertThat(get("/v1/loan-restructures/" + second).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("loan_restructure", second, secondRequest, payload, "checker.ben"),
                Outcome.APPROVED);
        JsonNode approved = get("/v1/loan-restructures/" + second);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("approved_by").asString()).isEqualTo("checker.ben");

        // a late rejection of the already approved restructure is ignored
        dispatcher.dispatch(UUID.fromString(TENANT), decision("loan_restructure", second, secondRequest, payload, "checker.ben"),
                Outcome.REJECTED);
        assertThat(get("/v1/loan-restructures/" + second).get("status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void directDecisionClosesTheInboxItemAfterCommit() throws Exception {
        String loan = disbursedLoan("1500");
        String id = post("/v1/loans/" + loan + "/write-off", "{\"write_off_type\":\"FULL\",\"reason\":\"fraud\"}",
                "maker.anna", 200).get("id").asString();
        post("/v1/loan-write-offs/" + id + "/reject", "{\"reason\":\"collect first\"}", "checker.ben", 200);
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_id=" + id).contains("status=PENDING").contains("resource_type=loan_write_off");
        verify(serviceClient, never()).post(eq("workflow-service"), startsWith("/v1/approval-requests/"), any(), anyString());
    }

    private String proposeAndSubmit(String loan) throws Exception {
        String id = post("/v1/loans/" + loan + "/restructure", """
                {"restructure_type":"TERM_EXTENSION","reason":"hardship: reduced income","new_term_months":24}""",
                "maker.anna", 200).get("id").asString();
        post("/v1/loan-restructures/" + id + "/submit", null, "maker.anna", 200);
        return id;
    }

    private ObjectNode decision(String type, String resource, String requestId, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", requestId);
        row.put("resource_type", type);
        row.put("resource_id", resource);
        row.put("action", "approve");
        row.put("maker", "maker.anna");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String disbursedLoan(String principal) throws Exception {
        String loan = post("/v1/loans", """
                {"customer_id":"%s","product_id":"%s","product_version_id":"%s","legal_entity_id":"%s","loan_type":"INSTALMENT",
                 "currency":"EUR","principal_amount":"%s","interest_rate_type":"FIXED","interest_rate":"0.12","schedule_type":"ANNUITY",
                 "repayment_frequency":"MONTHLY","term_months":12,"disbursement_account_id":"%s","repayment_account_id":"%s"}"""
                .formatted(CUSTOMER, PRODUCT, VERSION, LEGAL_ENTITY, principal, account, account), "ops", 201).get("id").asString();
        post("/v1/loans/" + loan + "/disburse", "{\"method\":\"DEPOSIT_ACCOUNT\"}", "ops", 200);
        return loan;
    }

    private JsonNode deposit(String path) {
        String id = path.substring("/v1/deposit-accounts/".length());
        UUID acc = deposits.get(id);
        if (acc == null) {
            throw ApiException.notFound("deposit account", id);
        }
        return json.readTree("{\"id\":\"" + id + "\",\"ledger_account_id\":\"" + acc + "\",\"currency\":\"EUR\",\"status\":\"ACTIVE\"}");
    }

    private JsonNode catalog(String path) {
        if (path.equals("/v1/product-versions/" + VERSION)) {
            return json.readTree("{\"id\":\"" + VERSION + "\",\"product_id\":\"" + PRODUCT + "\",\"status\":\"PUBLISHED\"}");
        }
        if (path.startsWith("/v1/product-versions/" + VERSION + "/interest-rates")) {
            return json.readTree("""
                    {"data":[{"rate_kind":"DEBIT_INTEREST","rate_type":"FIXED","fixed_rate":"0.12","day_count":"ACT_365"}],"has_more":false}""");
        }
        if (path.startsWith("/v1/product-versions/" + VERSION + "/fees")) {
            return json.readTree("{\"data\":[],\"has_more\":false}");
        }
        if (path.startsWith("/v1/product-versions/" + VERSION + "/parameters")) {
            return json.readTree("""
                    {"data":[{"param_key":"lending.min_amount","value_text":"100"},{"param_key":"lending.max_amount","value_text":"50000"},
                             {"param_key":"lending.max_term_months","value_text":"84"}],"has_more":false}""");
        }
        throw new ApiException(404, "not_found", path);
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
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
