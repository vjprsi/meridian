package io.meridian.casemanagement;

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
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, proposing a case disposition and
 * submitting a SAR / STR open approval requests in workflow-service (ids and headers only: no rationale, narrative or
 * subjects, against tipping-off), and the MLRO's decision arriving from workflow-service is applied with the MLRO as
 * principal, still under the four-eyes rules.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class CaseManagementWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000e9";
    static final String RATIONALE = "Customer explained deposits with payroll evidence";
    static final String NARRATIVE = "WHO: Jane Roe deposited cash in amounts just below the reporting threshold on six "
            + "occasions and moved the funds abroad within hours; WHAT, WHEN, WHERE and WHY are set out in the case file.";

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

    @Test
    void dispositionApprovedFromWorkflowClosesTheCase() throws Exception {
        String caseId = openCase();
        String disposition = close(caseId);
        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "aml_case_disposition").containsEntry("resource_id", disposition)
                .containsEntry("action", "approve").containsEntry("currency", "USD");
        assertThat(new BigDecimal((String) req.get("amount"))).isEqualByComparingTo("15000");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("aml_case_id")).isEqualTo(caseId);
        assertThat(payload.get("disposition")).isEqualTo("NO_SUSPICION");
        assertThat(json.writeValueAsString(req)).doesNotContain(RATIONALE).doesNotContain("payroll");

        // the investigator approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision("aml_case_disposition", disposition, payload, "inv-1"), Outcome.APPROVED);
        assertThat(get("/v1/risk/case-dispositions/" + disposition).get("approval_status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("aml_case_disposition", disposition, payload, "mlro"), Outcome.APPROVED);
        JsonNode d = get("/v1/risk/case-dispositions/" + disposition);
        assertThat(d.get("approval_status").asString()).isEqualTo("APPROVED");
        assertThat(d.get("approved_by").asString()).isEqualTo("mlro");
        assertThat(get("/v1/risk/cases/" + caseId).get("status").asString()).isEqualTo("CLOSED");

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision("aml_case_disposition", disposition, payload, "mlro"), Outcome.APPROVED);
        assertThat(get("/v1/risk/case-dispositions/" + disposition).get("approval_status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void dispositionRejectedFromWorkflowReturnsToInvestigationAndStaleDecisionIsIgnored() throws Exception {
        String caseId = openCase();
        String first = close(caseId);
        Map<?, ?> firstPayload = (Map<?, ?>) lastRequest.get().get("payload");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("aml_case_disposition", first, firstPayload, "mlro"), Outcome.REJECTED);
        assertThat(get("/v1/risk/case-dispositions/" + first).get("approval_status").asString()).isEqualTo("REJECTED");
        assertThat(get("/v1/risk/cases/" + caseId).get("status").asString()).isEqualTo("INVESTIGATING");

        // a late approval of the rejected proposal does not close the case or touch the new proposal
        String second = close(caseId);
        dispatcher.dispatch(UUID.fromString(TENANT), decision("aml_case_disposition", first, firstPayload, "mlro"), Outcome.APPROVED);
        assertThat(get("/v1/risk/case-dispositions/" + first).get("approval_status").asString()).isEqualTo("REJECTED");
        assertThat(get("/v1/risk/case-dispositions/" + second).get("approval_status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(get("/v1/risk/cases/" + caseId).get("status").asString()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void sarApprovedFromWorkflowWithoutLeakingReportContent() throws Exception {
        String caseId = openCase();
        String sar = submittedSar(caseId);
        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "sar_report").containsEntry("resource_id", sar)
                .containsEntry("action", "approve");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("aml_case_id")).isEqualTo(caseId);
        assertThat(payload.get("subjects")).isEqualTo(1);
        assertThat(payload.get("row_version")).isNotNull();
        assertThat(json.writeValueAsString(req)).doesNotContain("Jane Roe").doesNotContain("P999").doesNotContain("threshold");

        // the preparer approving in the inbox is refused; a rejection in the inbox returns the report to DRAFT
        dispatcher.dispatch(UUID.fromString(TENANT), decision("sar_report", sar, payload, "inv-1"), Outcome.APPROVED);
        assertThat(get("/v1/risk/sar-reports/" + sar).get("status").asString()).isEqualTo("PENDING_APPROVAL");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("sar_report", sar, payload, "mlro"), Outcome.REJECTED);
        JsonNode returned = get("/v1/risk/sar-reports/" + sar);
        assertThat(returned.get("status").asString()).isEqualTo("DRAFT");
        assertThat(returned.get("returned_by").asString()).isEqualTo("mlro");
        assertThat(returned.get("return_reason").asString()).contains("workflow");

        // a decision for the first submission is ignored once the report is reworked and submitted again
        post("/v1/risk/sar-reports/" + sar + "/submit-for-approval", "{}", 200, "inv-1");
        Map<?, ?> resubmitted = (Map<?, ?>) lastRequest.get().get("payload");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("sar_report", sar, payload, "mlro"), Outcome.APPROVED);
        assertThat(get("/v1/risk/sar-reports/" + sar).get("status").asString()).isEqualTo("PENDING_APPROVAL");
        payload = resubmitted;

        dispatcher.dispatch(UUID.fromString(TENANT), decision("sar_report", sar, payload, "mlro"), Outcome.APPROVED);
        JsonNode approved = get("/v1/risk/sar-reports/" + sar);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("approved_by").asString()).isEqualTo("mlro");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("sar_report", sar, payload, "mlro"), Outcome.APPROVED);
        assertThat(get("/v1/risk/sar-reports/" + sar).get("status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void mlroReturnsSarToDraftDirectly() throws Exception {
        String sar = submittedSar(openCase());
        assertThat(post("/v1/risk/sar-reports/" + sar + "/return-to-draft", "{\"reason\":\"mine\"}", 422, "inv-1")
                .get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode r = post("/v1/risk/sar-reports/" + sar + "/return-to-draft", "{\"reason\":\"add the 2025 transfers\"}", 200, "mlro");
        assertThat(r.get("status").asString()).isEqualTo("DRAFT");
        assertThat(r.get("return_reason").asString()).isEqualTo("add the 2025 transfers");
        assertThat(post("/v1/risk/sar-reports/" + sar + "/return-to-draft", "{\"reason\":\"again\"}", 409, "mlro")
                .get("code").asString()).isEqualTo("invalid_transition");
    }

    @Test
    void directDecisionKeepsFourEyesAndClosesTheInboxItemAfterCommit() throws Exception {
        String disposition = close(openCase());
        assertThat(post("/v1/risk/case-dispositions/" + disposition + "/approve", "{}", 422, "inv-1").get("code").asString())
                .isEqualTo("four_eyes_violation");
        verify(serviceClient, never()).get(eq("workflow-service"), anyString());
        post("/v1/risk/case-dispositions/" + disposition + "/approve", "{\"comment\":\"agreed\"}", 200, "mlro");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_type=aml_case_disposition").contains("resource_id=" + disposition)
                .contains("status=PENDING");
        verify(serviceClient, never()).post(eq("workflow-service"), startsWith("/v1/approval-requests/"), any(), anyString());
    }

    private ObjectNode decision(String type, String id, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", type);
        row.put("resource_id", id);
        row.put("action", "approve");
        row.put("maker", "inv-1");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String openCase() throws Exception {
        return post("/v1/risk/cases", """
                {"case_type":"AML","priority":"HIGH","customer_id":"%s","title":"Cash structuring",
                 "total_amount":"15000.00","currency":"USD"}""".formatted(UUID.randomUUID()), 201, "intake").get("id").asString();
    }

    private String close(String caseId) throws Exception {
        JsonNode d = post("/v1/risk/cases/" + caseId + "/close",
                "{\"disposition\":\"NO_SUSPICION\",\"rationale\":\"" + RATIONALE + "\"}", 200, "inv-1");
        assertThat(d.get("approval_status").asString()).isEqualTo("PENDING_APPROVAL");
        return d.get("id").asString();
    }

    private String submittedSar(String caseId) throws Exception {
        String sar = post("/v1/risk/sar-reports", json.writeValueAsString(Map.of(
                "aml_case_id", caseId, "report_type", "SAR", "jurisdiction", "US", "regulator", "FinCEN",
                "narrative", NARRATIVE,
                "subjects", java.util.List.of(Map.of("name", "Jane Roe", "type", "INDIVIDUAL", "role", "SUBJECT",
                        "country", "US", "id_number", "P999")))), 201, "inv-1").get("id").asString();
        JsonNode submitted = post("/v1/risk/sar-reports/" + sar + "/submit-for-approval", "{}", 200, "inv-1");
        assertThat(submitted.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        return sar;
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
