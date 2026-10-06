package io.meridian.loanorigination;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, proposing a manual credit decision and
 * uploading an application document open approval requests in workflow-service, and decisions arriving from
 * workflow-service are applied with the approver as principal, still under the four-eyes rule. product-catalog and
 * loan-service stay behind {@link FakeServices}; workflow-service is stubbed on the same mocked {@link ServiceClient}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.loan-origination.callback-secret=it-secret",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class LoanOriginationWorkflowApprovalIT {
    static final String TENANT = "0199b000-0000-7000-8000-00000000d0c7";
    static final String ACCOUNT = "0199b000-0000-7000-8000-00000000a001";
    static final String WF = "workflow-service";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final List<Map<String, Object>> requests = Collections.synchronizedList(new ArrayList<>());
    final AtomicReference<String> lastKey = new AtomicReference<>();
    FakeServices fake;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stub() {
        fake = new FakeServices(json);
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> WF.equals(i.getArgument(0))
                ? json.readTree("{\"data\":[]}") : fake.get(i.getArgument(0), i.getArgument(1)));
        when(serviceClient.post(anyString(), anyString(), any(), any())).thenAnswer(i -> {
            if (WF.equals(i.getArgument(0))) {
                requests.add((Map<String, Object>) i.getArgument(2));
                lastKey.set(i.getArgument(3));
                return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
            }
            return fake.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3));
        });
    }

    static volatile boolean scorecardReady;

    /** Auto-decide needs an active champion APPLICATION scorecard in this test's tenant (same as the FlowIT). */
    @BeforeEach
    void champion() throws Exception {
        if (scorecardReady) {
            return;
        }
        String sc = post("/v1/scorecards", """
                {"code":"APP-WF","name":"Workflow IT application","scorecard_type":"APPLICATION","owner":"mrm"}""",
                201, "modeler").get("id").asString();
        String base = "/v1/scorecards/" + sc + "/versions";
        String v = post(base, "{\"scorecard_id\":\"" + sc + "\",\"version\":\"1.0\",\"base_points\":550,\"features\":"
                + LoanOriginationFlowIT.FEATURES + ",\"cutoffs\":" + LoanOriginationFlowIT.CUTOFFS + "}", 201, "modeler")
                .get("id").asString();
        post(base + "/" + v + "/activate", "{}", 200, "mrm");
        scorecardReady = true;
    }

    // ------------------------------------------------------------------ manual credit decisions

    @Test
    void proposalOpensWorkflowRequestAndApprovalFromWorkflowRecordsTheDecision() throws Exception {
        String a = declinedApplication("0199b000-0000-7000-8000-00000000e01f");
        String requestId = propose(a, "APPROVED", "6000");

        Map<String, Object> req = last("credit_decision_request");
        assertThat(req).containsEntry("resource_id", requestId).containsEntry("action", "approve")
                .containsEntry("currency", "EUR");
        assertThat(new BigDecimal((String) req.get("amount"))).isEqualByComparingTo("6000");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("outcome")).isEqualTo("APPROVED");
        assertThat(payload.get("loan_application_id")).isEqualTo(a);
        assertThat(lastKey.get()).endsWith(":propose:" + requestId + ":APPROVED");

        // the maker approving in the inbox is still refused by the four-eyes rule
        dispatch("credit_decision_request", requestId, payload, "underwriter", Outcome.APPROVED);
        assertThat(get("/v1/credit-decision-requests/" + requestId).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatch("credit_decision_request", requestId, payload, "credit-head", Outcome.APPROVED);
        JsonNode r = get("/v1/credit-decision-requests/" + requestId);
        assertThat(r.get("status").asString()).isEqualTo("APPROVED");
        assertThat(r.get("reviewed_by").asString()).isEqualTo("credit-head");
        assertThat(get("/v1/loan-applications/" + a).get("status").asString()).isEqualTo("APPROVED");
        JsonNode decisions = get("/v1/loan-applications/" + a + "/decisions").get("data");
        assertThat(decisions).hasSize(2);

        // redelivery of the same decision is harmless
        dispatch("credit_decision_request", requestId, payload, "credit-head", Outcome.APPROVED);
        assertThat(get("/v1/loan-applications/" + a + "/decisions").get("data")).hasSize(2);
    }

    @Test
    void rejectionFromWorkflowRejectsAndStaleDecisionsAreIgnored() throws Exception {
        String a = declinedApplication("0199b000-0000-7000-8000-00000000e02f");
        String first = propose(a, "APPROVED", "5000");
        Map<?, ?> firstPayload = (Map<?, ?>) last("credit_decision_request").get("payload");

        dispatch("credit_decision_request", first, firstPayload, "credit-head", Outcome.REJECTED);
        assertThat(get("/v1/credit-decision-requests/" + first).get("status").asString()).isEqualTo("REJECTED");

        // a late approval of the rejected request does nothing
        dispatch("credit_decision_request", first, firstPayload, "credit-head", Outcome.APPROVED);
        assertThat(get("/v1/credit-decision-requests/" + first).get("status").asString()).isEqualTo("REJECTED");
        assertThat(get("/v1/loan-applications/" + a).get("status").asString()).isEqualTo("DECLINED");

        // a decision whose payload does not match the pending request's content is ignored
        String second = propose(a, "COUNTER_OFFER", "4000");
        ObjectNode stale = decision("credit_decision_request", second, firstPayload, "credit-head");
        dispatcher.dispatch(UUID.fromString(TENANT), stale, Outcome.APPROVED);
        assertThat(get("/v1/credit-decision-requests/" + second).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        // expiry/cancellation in workflow leaves the request pending for a direct decision
        Map<?, ?> secondPayload = (Map<?, ?>) last("credit_decision_request").get("payload");
        dispatch("credit_decision_request", second, secondPayload, "credit-head", Outcome.EXPIRED);
        assertThat(get("/v1/credit-decision-requests/" + second).get("status").asString()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void directDecisionClosesTheInboxItemAfterCommit() throws Exception {
        String a = declinedApplication("0199b000-0000-7000-8000-00000000e03f");
        String requestId = propose(a, "APPROVED", "6000");
        post("/v1/credit-decision-requests/" + requestId + "/approve", "{\"comment\":\"ok\"}", 200, "credit-head");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient, atLeastOnce()).get(eq(WF), path.capture());
        assertThat(path.getAllValues()).anyMatch(p -> p.startsWith("/v1/approval-requests?")
                && p.contains("resource_type=credit_decision_request") && p.contains("resource_id=" + requestId)
                && p.contains("status=PENDING"));
    }

    // ------------------------------------------------------------------ document verification

    @Test
    void uploadedDocumentIsVerifiedOrRejectedFromWorkflowUnderFourEyes() throws Exception {
        String a = post("/v1/loan-applications", appBody("0199b000-0000-7000-8000-00000000e04c", "10000", 24), 201, "agent")
                .get("id").asString();
        String doc = post("/v1/loan-applications/" + a + "/documents", """
                {"loan_application_id":"%s","document_type":"ID_PROOF","document_id":"%s"}""".formatted(a, UUID.randomUUID()),
                201, "agent").get("id").asString();
        Map<String, Object> req = last("loan_application_document");
        assertThat(req).containsEntry("resource_id", doc).containsEntry("action", "verify");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("document_id")).isNotNull();

        dispatch("loan_application_document", doc, payload, "agent", Outcome.APPROVED);
        assertThat(get("/v1/loan-applications/" + a + "/documents/" + doc).get("status").asString()).isEqualTo("UPLOADED");
        dispatch("loan_application_document", doc, payload, "checker", Outcome.APPROVED);
        JsonNode verified = get("/v1/loan-applications/" + a + "/documents/" + doc);
        assertThat(verified.get("status").asString()).isEqualTo("VERIFIED");
        assertThat(verified.get("verified_by").asString()).isEqualTo("checker");
        // redelivery is harmless
        dispatch("loan_application_document", doc, payload, "checker", Outcome.REJECTED);
        assertThat(get("/v1/loan-applications/" + a + "/documents/" + doc).get("status").asString()).isEqualTo("VERIFIED");

        // a replaced file makes the decision on the old one stale; the new file gets its own request
        String income = post("/v1/loan-applications/" + a + "/documents", """
                {"loan_application_id":"%s","document_type":"INCOME_PROOF","document_id":"%s"}""".formatted(a, UUID.randomUUID()),
                201, "agent").get("id").asString();
        Map<?, ?> oldPayload = (Map<?, ?>) last("loan_application_document").get("payload");
        patch("/v1/loan-applications/" + a + "/documents/" + income, "{\"document_id\":\"" + UUID.randomUUID() + "\"}", "agent");
        Map<?, ?> newPayload = (Map<?, ?>) last("loan_application_document").get("payload");
        assertThat(newPayload.get("document_id")).isNotEqualTo(oldPayload.get("document_id"));
        dispatch("loan_application_document", income, oldPayload, "checker", Outcome.APPROVED);
        assertThat(get("/v1/loan-applications/" + a + "/documents/" + income).get("status").asString()).isEqualTo("UPLOADED");
        dispatch("loan_application_document", income, newPayload, "checker", Outcome.REJECTED);
        assertThat(get("/v1/loan-applications/" + a + "/documents/" + income).get("status").asString()).isEqualTo("REJECTED");
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> last(String resourceType) {
        synchronized (requests) {
            for (int i = requests.size() - 1; i >= 0; i--) {
                if (resourceType.equals(requests.get(i).get("resource_type"))) {
                    return requests.get(i);
                }
            }
        }
        throw new AssertionError("no approval request for " + resourceType);
    }

    private void dispatch(String type, String id, Map<?, ?> payload, String approver, Outcome outcome) throws Exception {
        dispatcher.dispatch(UUID.fromString(TENANT), decision(type, id, payload, approver), outcome);
    }

    private ObjectNode decision(String type, String id, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", type);
        row.put("resource_id", id);
        row.put("action", "credit_decision_request".equals(type) ? "approve" : "verify");
        row.put("maker", "maker");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String propose(String a, String outcome, String amount) throws Exception {
        JsonNode r = post("/v1/loan-applications/" + a + "/decide", """
                {"outcome":"%s","approved_amount":"%s","approved_term_months":24,"approved_rate":"0.14",
                 "reason_codes":[{"code":"SECURED_BY_SALARY_ASSIGNMENT"}]}""".formatted(outcome, amount), 200, "underwriter");
        assertThat(r.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        return r.get("id").asString();
    }

    /** A delinquent customer (sandbox bureau: last hex digit f) is declined automatically. */
    private String declinedApplication(String customer) throws Exception {
        String a = post("/v1/loan-applications", appBody(customer, "8000", 24), 201, "agent").get("id").asString();
        String applicant = post("/v1/loan-applications/" + a + "/applicants", """
                {"loan_application_id":"%s","customer_id":"%s","role":"PRIMARY","date_of_birth":"1985-04-12","employment_status":"EMPLOYED",
                 "employer_name":"Acme","employment_start_date":"2018-01-01","monthly_net_income":"3500","dependants":1,
                 "residential_status":"OWNER","share_of_liability":"1"}""".formatted(a, customer), 201, "agent").get("id").asString();
        post("/v1/loan-applications/" + a + "/financial-items", """
                {"loan_applicant_id":"%s","item_type":"SALARY","amount":"3500","currency":"EUR","frequency":"MONTHLY",
                 "is_verified":true,"verification_source":"PAYROLL"}""".formatted(applicant), 201, "agent");
        post("/v1/loan-applications/" + a + "/submit", null, 200, "agent");
        assertThat(post("/v1/loan-applications/" + a + "/auto-decide", null, 200, "system").get("outcome").asString())
                .isEqualTo("DECLINED");
        return a;
    }

    private String appBody(String customer, String amount, int term) {
        return """
                {"customer_id":"%s","product_id":"%s","channel":"WEB","loan_type":"INSTALMENT","purpose":"PERSONAL",
                 "requested_amount":"%s","currency":"EUR","requested_term_months":%d,"requested_frequency":"MONTHLY",
                 "consent_bureau":true,"disbursement_account_id":"%s","repayment_account_id":"%s"}"""
                .formatted(customer, FakeServices.PRODUCT, amount, term, ACCOUNT, ACCOUNT);
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

    private void patch(String path, String body, String user) throws Exception {
        HttpResponse<String> current = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).GET().build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("If-Match", current.headers().firstValue("ETag").orElse("\"1\""))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "viewer").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
}
