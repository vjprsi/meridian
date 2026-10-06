package io.meridian.collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
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
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, requests to litigate and payment plans
 * outside policy open an approval request in workflow-service, and the decision arriving from workflow-service is
 * applied with the approver as principal, still under the four-eyes rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class CollectionsWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000c0a7";
    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

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

    // ------------------------------------------------------------------ legal proceedings

    @Test
    void legalProceedingApprovedFromWorkflowUnderFourEyesAndRedeliveryIsHarmless() throws Exception {
        String caseId = openCase("100.00", "60.00");
        String id = post("/v1/collections/cases/" + caseId + "/start-legal",
                "{\"claim_amount\":\"80.00\",\"law_firm\":\"Panel LLP\"}", "maker.anna").get("id").asString();

        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "legal_proceeding").containsEntry("resource_id", id)
                .containsEntry("action", "approve").containsEntry("amount", "80.00").containsEntry("currency", "EUR");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("collection_case_id")).isEqualTo(caseId);

        // the maker approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision("legal_proceeding", id, "approve", payload, "maker.anna"),
                Outcome.APPROVED);
        assertThat(get("/v1/collections/legal-proceedings/" + id).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("legal_proceeding", id, "approve", payload, "checker.ben"),
                Outcome.APPROVED);
        JsonNode lp = get("/v1/collections/legal-proceedings/" + id);
        assertThat(lp.get("status").asString()).isEqualTo("INITIATED");
        assertThat(lp.get("approved_by").asString()).isEqualTo("checker.ben");
        assertThat(get("/v1/collections/cases/" + caseId).get("status").asString()).isEqualTo("LEGAL");

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision("legal_proceeding", id, "approve", payload, "checker.ben"),
                Outcome.APPROVED);
        assertThat(get("/v1/collections/legal-proceedings/" + id).get("status").asString()).isEqualTo("INITIATED");
    }

    @Test
    void legalProceedingRejectedFromWorkflowAndStaleDecisionIsIgnored() throws Exception {
        String caseId = openCase("100.00", "60.00");
        String id = post("/v1/collections/cases/" + caseId + "/start-legal", "{\"claim_amount\":\"90.00\"}", "maker.anna")
                .get("id").asString();
        Map<?, ?> payload = (Map<?, ?>) lastRequest.get().get("payload");

        // a decision for a different claim than the pending one is ignored
        ObjectNode stale = decision("legal_proceeding", id, "approve", payload, "checker.ben");
        ((ObjectNode) stale.get("payload")).put("claim_amount", "1.00");
        dispatcher.dispatch(UUID.fromString(TENANT), stale, Outcome.APPROVED);
        assertThat(get("/v1/collections/legal-proceedings/" + id).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        // expiry leaves it pending
        dispatcher.dispatch(UUID.fromString(TENANT), decision("legal_proceeding", id, "approve", payload, "checker.ben"),
                Outcome.EXPIRED);
        assertThat(get("/v1/collections/legal-proceedings/" + id).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("legal_proceeding", id, "approve", payload, "checker.ben"),
                Outcome.REJECTED);
        JsonNode lp = get("/v1/collections/legal-proceedings/" + id);
        assertThat(lp.get("status").asString()).isEqualTo("REJECTED");
        assertThat(lp.get("notes").asString()).contains("rejected in workflow request");

        // a late approval for the rejected request does nothing
        dispatcher.dispatch(UUID.fromString(TENANT), decision("legal_proceeding", id, "approve", payload, "checker.ben"),
                Outcome.APPROVED);
        assertThat(get("/v1/collections/legal-proceedings/" + id).get("status").asString()).isEqualTo("REJECTED");
        assertThat(get("/v1/collections/cases/" + caseId).get("status").asString()).isNotEqualTo("LEGAL");
    }

    @Test
    void directLegalApprovalClosesTheInboxItemAfterCommit() throws Exception {
        String caseId = openCase("100.00", "60.00");
        String id = post("/v1/collections/cases/" + caseId + "/start-legal", "{\"claim_amount\":\"50.00\"}", "maker.anna")
                .get("id").asString();
        post("/v1/collections/legal-proceedings/" + id + "/approve", "{}", "checker.ben");
        verify(serviceClient).get(eq("workflow-service"),
                argThat(p -> p.contains("resource_type=legal_proceeding") && p.contains("resource_id=" + id)
                        && p.contains("status=PENDING")));
    }

    // ------------------------------------------------------------------ payment plans

    @Test
    void paymentPlanOutsidePolicyActivatedFromWorkflowUnderFourEyes() throws Exception {
        String caseId = openCase("500.00", "260.00");
        String id = post("/v1/collections/cases/" + caseId + "/payment-plan", """
                {"installment_count":13,"frequency":"MONTHLY","start_date":"%s"}""".formatted(TODAY.plusDays(5)),
                "maker.anna").get("id").asString();

        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "payment_plan").containsEntry("resource_id", id)
                .containsEntry("action", "activate").containsEntry("currency", "EUR");
        assertThat(new BigDecimal((String) req.get("amount"))).isEqualByComparingTo("260.00");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("installment_count")).isEqualTo(13);

        dispatcher.dispatch(UUID.fromString(TENANT), decision("payment_plan", id, "activate", payload, "maker.anna"),
                Outcome.APPROVED);
        assertThat(get("/v1/collections/payment-plans/" + id).get("status").asString()).isEqualTo("PROPOSED");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("payment_plan", id, "activate", payload, "checker.ben"),
                Outcome.APPROVED);
        JsonNode plan = get("/v1/collections/payment-plans/" + id);
        assertThat(plan.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(plan.get("activated_by").asString()).isEqualTo("checker.ben");
        assertThat(get("/v1/collections/cases/" + caseId).get("status").asString()).isEqualTo("PLAN_ACTIVE");

        // redelivery is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision("payment_plan", id, "activate", payload, "checker.ben"),
                Outcome.APPROVED);
        assertThat(get("/v1/collections/payment-plans/" + id).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void paymentPlanRejectedFromWorkflowIsCancelledAndStaleDecisionIsIgnored() throws Exception {
        String caseId = openCase("500.00", "260.00");
        String id = post("/v1/collections/cases/" + caseId + "/payment-plan", """
                {"installment_count":24,"frequency":"MONTHLY","start_date":"%s"}""".formatted(TODAY.plusDays(3)),
                "maker.anna").get("id").asString();
        Map<?, ?> payload = (Map<?, ?>) lastRequest.get().get("payload");

        ObjectNode stale = decision("payment_plan", id, "activate", payload, "checker.ben");
        ((ObjectNode) stale.get("payload")).put("installment_count", 6);
        dispatcher.dispatch(UUID.fromString(TENANT), stale, Outcome.APPROVED);
        assertThat(get("/v1/collections/payment-plans/" + id).get("status").asString()).isEqualTo("PROPOSED");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("payment_plan", id, "activate", payload, "checker.ben"),
                Outcome.REJECTED);
        JsonNode plan = get("/v1/collections/payment-plans/" + id);
        assertThat(plan.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(plan.get("cancel_reason").asString()).contains("rejected in workflow request");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("payment_plan", id, "activate", payload, "checker.ben"),
                Outcome.APPROVED);
        assertThat(get("/v1/collections/payment-plans/" + id).get("status").asString()).isEqualTo("CANCELLED");
    }

    @Test
    void planWithinPolicyOpensNoRequestAndDirectActivationClosesTheInboxItem() throws Exception {
        String caseId = openCase("500.00", "260.00");
        lastRequest.set(null);
        String inPolicy = post("/v1/collections/cases/" + caseId + "/payment-plan", """
                {"installment_count":3,"frequency":"MONTHLY","start_date":"%s"}""".formatted(TODAY.plusDays(3)),
                "maker.anna").get("id").asString();
        assertThat(lastRequest.get()).isNull();
        post("/v1/collections/payment-plans/" + inPolicy + "/cancel", "{\"reason\":\"redo\"}", "maker.anna");

        String outside = post("/v1/collections/cases/" + caseId + "/payment-plan", """
                {"installment_count":18,"frequency":"MONTHLY","start_date":"%s"}""".formatted(TODAY.plusDays(3)),
                "maker.anna").get("id").asString();
        assertThat(lastRequest.get()).containsEntry("resource_id", outside);
        post("/v1/collections/payment-plans/" + outside + "/activate", null, "checker.ben");
        verify(serviceClient).get(eq("workflow-service"),
                argThat(p -> p.contains("resource_type=payment_plan") && p.contains("resource_id=" + outside)));
        verify(serviceClient, never()).post(eq("workflow-service"), startsWith("/v1/approval-requests/"), any(), anyString());
    }

    // ------------------------------------------------------------------ helpers

    private String openCase(String total, String overdue) throws Exception {
        String body = """
                {"customer_id":"%s","exposure_type":"LOAN","exposure_id":"%s","days_past_due":20,"overdue_amount":"%s",
                 "total_outstanding":"%s","currency":"EUR"}""".formatted(UUID.randomUUID(), UUID.randomUUID(), overdue, total);
        return post("/v1/collections/cases/open", body, "ops").get("id").asString();
    }

    private ObjectNode decision(String type, String id, String action, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", type);
        row.put("resource_id", id);
        row.put("action", action);
        row.put("maker", "maker.anna");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private JsonNode post(String path, String body, String user) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "viewer").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
}
