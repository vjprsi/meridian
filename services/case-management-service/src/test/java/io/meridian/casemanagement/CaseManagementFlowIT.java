package io.meridian.casemanagement;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Case management end to end: numbered cases, alert linking, notes with tipping-off protection, tasks, evidence,
 * escalation, timeline and summary, SAR drafting / four-eyes approval / filing / acknowledgement / goAML export, and
 * the maker-checker disposition that closes a case.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CaseManagementFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000e1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000e2";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final String customer = UUID.randomUUID().toString();
    final String alertId = UUID.randomUUID().toString();
    String caseId;
    String sarId;

    @BeforeAll
    void openCase() throws Exception {
        String body = """
                {"case_type":"AML","priority":"MEDIUM","customer_id":"%s","title":"Structuring & mule activity",
                 "description":"Cash deposits below threshold then rapid transfers","total_amount":"27950.00","currency":"USD",
                 "tags":["structuring","mule"]}""".formatted(customer);
        HttpResponse<String> first = send("POST", "/v1/risk/cases", body, "open-1", TENANT, "intake");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        JsonNode c = json.readTree(first.body());
        caseId = c.get("id").asString();
        assertThat(c.get("case_number").asString()).matches("AML-\\d{4}-\\d{6}");
        assertThat(c.get("status").asString()).isEqualTo("OPEN");
        assertThat(c.get("due_at").asString()).isNotBlank();
        HttpResponse<String> replay = send("POST", "/v1/risk/cases", body, "open-1", TENANT, "intake");
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(caseId);
    }

    @Test
    @Order(1)
    void alertsAreLinkedOnce() throws Exception {
        String link = """
                {"source":"TM_ALERT","alert_id":"%s","summary":"STRUCTURING: 3 cash deposits 9,300-9,500 USD",
                 "added_reason":"same customer"}""".formatted(alertId);
        JsonNode a1 = post("/v1/risk/cases/" + caseId + "/alerts", link, 200, "intake");
        JsonNode a2 = post("/v1/risk/cases/" + caseId + "/alerts", link, 200, "intake");
        assertThat(a2.get("id").asString()).isEqualTo(a1.get("id").asString());
        post("/v1/risk/cases/" + caseId + "/alerts", """
                {"source":"FRAUD_ALERT","alert_id":"%s","summary":"Device shared by 3 customers"}""".formatted(UUID.randomUUID()), 200, "intake");
        assertThat(post("/v1/risk/cases/" + caseId + "/alerts", "{\"source\":\"TM_ALERT\"}", 422, "intake")
                .get("code").asString()).isEqualTo("alert_id_required");
        assertThat(get("/v1/risk/cases/" + caseId + "/alerts", TENANT).get("data")).hasSize(2);
    }

    @Test
    @Order(2)
    void investigationWorkNotesTasksEvidence() throws Exception {
        JsonNode assigned = post("/v1/risk/cases/" + caseId + "/assign", "{\"assigned_to\":\"inv-1\",\"team\":\"AML-L2\"}", 200, "lead");
        assertThat(assigned.get("status").asString()).isEqualTo("INVESTIGATING");
        post("/v1/risk/cases/" + caseId + "/notes", "{\"body\":\"Customer statements requested\"}", 200, "inv-1");
        JsonNode restricted = post("/v1/risk/cases/" + caseId + "/notes",
                "{\"body\":\"Law enforcement production order received\",\"visibility\":\"RESTRICTED\"}", 200, "mlro");
        assertThat(restricted.get("author").asString()).isEqualTo("mlro");
        assertThat(post("/v1/risk/cases/" + caseId + "/notes", "{\"body\":\"x\",\"visibility\":\"PUBLIC\"}", 422, "inv-1")
                .get("code").asString()).isEqualTo("invalid_visibility");

        JsonNode task = post("/v1/risk/case-tasks", """
                {"aml_case_id":"%s","title":"Review 12 months of statements","assigned_to":"inv-1"}""".formatted(caseId), 201, "inv-1");
        String taskId = task.get("id").asString();
        JsonNode inProgress = patch("/v1/risk/case-tasks/" + taskId, "{\"status\":\"IN_PROGRESS\"}", task.get("row_version").asString(), 200, "inv-1");
        assertThat(patch("/v1/risk/case-tasks/" + taskId, "{\"status\":\"DONE\"}", inProgress.get("row_version").asString(), 422, "inv-1")
                .get("code").asString()).isEqualTo("use_complete");
        JsonNode done = post("/v1/risk/case-tasks/" + taskId + "/complete", "{\"note\":\"pattern confirmed\"}", 200, "inv-1");
        assertThat(done.get("status").asString()).isEqualTo("DONE");
        assertThat(done.get("completed_by").asString()).isEqualTo("inv-1");
        assertThat(post("/v1/risk/case-tasks/" + taskId + "/complete", "{}", 409, "inv-1").get("code").asString())
                .isEqualTo("invalid_transition");

        assertThat(post("/v1/risk/case-evidence", """
                {"aml_case_id":"%s","document_id":"%s","evidence_type":"STATEMENT","sha256":"abc"}"""
                .formatted(caseId, UUID.randomUUID()), 422, "inv-1").get("code").asString()).isEqualTo("invalid_sha256");
        post("/v1/risk/case-evidence", """
                {"aml_case_id":"%s","document_id":"%s","evidence_type":"STATEMENT","description":"Statements Jan-Aug",
                 "sha256":"%s"}""".formatted(caseId, UUID.randomUUID(), "A".repeat(64)), 201, "inv-1");

        JsonNode escalated = post("/v1/risk/cases/" + caseId + "/escalate",
                "{\"escalated_to\":\"mlro\",\"reason\":\"Mule network suspected\"}", 200, "inv-1");
        assertThat(escalated.get("status").asString()).isEqualTo("ESCALATED");
        assertThat(escalated.get("priority").asString()).isEqualTo("HIGH");
        assertThat(post("/v1/risk/cases/" + caseId + "/escalate", "{\"escalated_to\":\"x\",\"reason\":\"y\"}", 409, "inv-1")
                .get("code").asString()).isEqualTo("invalid_transition");
    }

    @Test
    @Order(3)
    void timelineAndSummary() throws Exception {
        JsonNode tl = get("/v1/risk/cases/" + caseId + "/timeline", TENANT);
        List<String> types = new ArrayList<>();
        tl.get("items").forEach(i -> types.add(i.get("type").asString()));
        assertThat(types.getFirst()).isEqualTo("CASE_OPENED");
        assertThat(types).contains("ALERT_LINKED", "NOTE", "TASK_CREATED", "TASK_COMPLETED", "EVIDENCE_ADDED");
        assertThat(tl.toString()).contains("Restricted note").doesNotContain("production order");
        assertThat(get("/v1/risk/cases/" + caseId + "/timeline?include_restricted=true", TENANT).toString()).contains("production order");

        JsonNode summary = post("/v1/risk/cases/" + caseId + "/ai-summary", "", 200, "inv-1");
        assertThat(summary.get("summary").asString()).contains(get("/v1/risk/cases/" + caseId, TENANT).get("case_number").asString());
        assertThat(summary.get("citations").toString()).contains("aml_case_alert:");
        assertThat(get("/v1/risk/cases/" + caseId, TENANT).get("ai_summary").asString()).isEqualTo(summary.get("summary").asString());
    }

    @Test
    @Order(4)
    void sarIsDraftedApprovedByASecondPersonFiledAndExported() throws Exception {
        JsonNode sar = post("/v1/risk/sar-reports", """
                {"aml_case_id":"%s","report_type":"STR","jurisdiction":"GB","regulator":"NCA",
                 "activity_start":"2026-08-01","activity_end":"2026-08-20",
                 "subjects":[{"name":"John Doe","type":"INDIVIDUAL","role":"SUBJECT","country":"GB","id_number":"P123"}],
                 "transactions":[{"transaction_id":"t1","date":"2026-08-01","amount":"9300","currency":"USD","from":"cash","to":"acct 1"},
                                 {"transaction_id":"t2","date":"2026-08-05","amount":"9450","currency":"USD","from":"cash","to":"acct 1"}]}"""
                .formatted(caseId), 201, "inv-1");
        sarId = sar.get("id").asString();
        assertThat(sar.get("status").asString()).isEqualTo("DRAFT");
        assertThat(sar.get("suspicious_amount").asString()).startsWith("27950");
        assertThat(sar.get("filing_deadline").asString()).isNotBlank();
        assertThat(post("/v1/risk/sar-reports/" + sarId + "/submit-for-approval", "{}", 422, "inv-1").get("code").asString())
                .isEqualTo("narrative_required");

        JsonNode draft = post("/v1/risk/sar-reports/" + sarId + "/draft-narrative", "", 200, "inv-1");
        String narrative = draft.get("narrative").asString();
        assertThat(narrative).contains("WHO: John Doe", "WHEN: The activity took place between 2026-08-01 and 2026-08-20");
        assertThat(draft.get("facts_used").get("alerts").asInt()).isEqualTo(2);
        JsonNode current = get("/v1/risk/sar-reports/" + sarId, TENANT);
        assertThat(current.get("ai_draft_narrative").asString()).isEqualTo(narrative);
        JsonNode edited = patch("/v1/risk/sar-reports/" + sarId, json.writeValueAsString(java.util.Map.of("narrative",
                narrative + " Reviewed by investigator <inv-1> & confirmed.")), current.get("row_version").asString(), 200, "inv-1");
        JsonNode submitted = post("/v1/risk/sar-reports/" + sarId + "/submit-for-approval", "{}", 200, "inv-1");
        assertThat(submitted.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(submitted.get("prepared_by").asString()).isEqualTo("inv-1");
        assertThat(patch("/v1/risk/sar-reports/" + sarId, "{\"regulator\":\"x\"}", submitted.get("row_version").asString(), 409, "inv-1")
                .get("code").asString()).isEqualTo("invalid_transition");
        assertThat(post("/v1/risk/sar-reports/" + sarId + "/approve", "{}", 422, "inv-1").get("code").asString())
                .isEqualTo("four_eyes_violation");
        assertThat(raw("GET", "/v1/risk/sar-reports/" + sarId + "/export", null, TENANT, "x").statusCode()).isEqualTo(409);
        JsonNode approved = post("/v1/risk/sar-reports/" + sarId + "/approve", "{\"comment\":\"ok\"}", 200, "mlro");
        assertThat(approved.get("approved_by").asString()).isEqualTo("mlro");

        HttpResponse<String> xml = raw("GET", "/v1/risk/sar-reports/" + sarId + "/export", null, TENANT, "mlro");
        assertThat(xml.statusCode()).isEqualTo(200);
        assertThat(xml.headers().firstValue("Content-Type").orElse("")).startsWith("application/xml");
        assertThat(xml.body()).contains("<report_code>STR</report_code>", "<amount_local>9450.00</amount_local>",
                "&lt;inv-1&gt; &amp; confirmed", "<indicator>TM_ALERT</indicator>");

        assertThat(post("/v1/risk/sar-reports/" + sarId + "/file", "{\"channel\":\"FAX\"}", 422, "mlro").get("code").asString())
                .isEqualTo("invalid_channel");
        JsonNode filed = post("/v1/risk/sar-reports/" + sarId + "/file", "{\"channel\":\"API\"}", 200, "mlro");
        assertThat(filed.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(filed.get("submitted_at").asString()).isNotBlank();
        JsonNode ack = post("/v1/risk/sar-reports/" + sarId + "/acknowledge", "{\"regulator_reference\":\"NCA-2026-77\"}", 200, "mlro");
        assertThat(ack.get("status").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(edited.get("narrative").asString()).contains("confirmed");
    }

    @Test
    @Order(5)
    void dispositionNeedsApprovalByAnotherUser() throws Exception {
        JsonNode task = post("/v1/risk/case-tasks", "{\"aml_case_id\":\"%s\",\"title\":\"Call customer\"}".formatted(caseId), 201, "inv-1");
        String close = """
                {"disposition":"SAR_FILED","rationale":"STR filed and acknowledged","actions_taken":["Account restricted","Exit relationship"]}""";
        assertThat(post("/v1/risk/cases/" + caseId + "/close", close, 409, "inv-1").get("code").asString()).isEqualTo("open_tasks");
        patch("/v1/risk/case-tasks/" + task.get("id").asString(), "{\"status\":\"CANCELLED\"}", task.get("row_version").asString(), 200, "inv-1");

        JsonNode d1 = post("/v1/risk/cases/" + caseId + "/close", close, 200, "inv-1");
        assertThat(d1.get("approval_status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(d1.get("decided_by").asString()).isEqualTo("inv-1");
        assertThat(get("/v1/risk/cases/" + caseId, TENANT).get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(post("/v1/risk/case-dispositions/" + d1.get("id").asString() + "/approve", "{}", 422, "inv-1")
                .get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode rejected = post("/v1/risk/case-dispositions/" + d1.get("id").asString() + "/reject",
                "{\"reason\":\"add the exit date\"}", 200, "mlro");
        assertThat(rejected.get("approval_status").asString()).isEqualTo("REJECTED");
        assertThat(get("/v1/risk/cases/" + caseId, TENANT).get("status").asString()).isEqualTo("INVESTIGATING");

        JsonNode d2 = post("/v1/risk/cases/" + caseId + "/close", close.replace("Exit relationship", "Exit relationship on 2026-10-31"), 200, "inv-1");
        JsonNode approved = post("/v1/risk/case-dispositions/" + d2.get("id").asString() + "/approve", "{\"comment\":\"agreed\"}", 200, "mlro");
        assertThat(approved.get("approval_status").asString()).isEqualTo("APPROVED");
        JsonNode closed = get("/v1/risk/cases/" + caseId, TENANT);
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("closed_at").asString()).isNotBlank();
        assertThat(post("/v1/risk/cases/" + caseId + "/notes", "{\"body\":\"post-closure note\"}", 200, "mlro").get("id")).isNotNull();
        assertThat(post("/v1/risk/case-tasks", "{\"aml_case_id\":\"%s\",\"title\":\"x\"}".formatted(caseId), 409, "inv-1")
                .get("code").asString()).isEqualTo("invalid_transition");

        JsonNode reopened = post("/v1/risk/cases/" + caseId + "/reopen", "{\"reason\":\"new production order\"}", 200, "mlro");
        assertThat(reopened.get("status").asString()).isEqualTo("INVESTIGATING");
        assertThat(get("/v1/risk/cases/" + caseId + "/timeline", TENANT).toString()).contains("DISPOSITION_APPROVED", "SAR_FILED");
    }

    @Test
    @Order(6)
    void ctrWithoutCaseAndTenantIsolation() throws Exception {
        assertThat(post("/v1/risk/sar-reports", "{\"report_type\":\"SAR\",\"jurisdiction\":\"US\",\"regulator\":\"FinCEN\"}",
                422, "x").get("code").asString()).isEqualTo("aml_case_required");
        post("/v1/risk/sar-reports", "{\"report_type\":\"CTR\",\"jurisdiction\":\"US\",\"regulator\":\"FinCEN\"}", 201, "x");
        assertThat(raw("GET", "/v1/risk/cases/" + caseId, null, OTHER_TENANT, "x").statusCode()).isEqualTo(404);
        assertThat(get("/v1/risk/sar-reports", OTHER_TENANT).get("data")).isEmpty();
        assertThat(raw("POST", "/v1/risk/cases/" + caseId + "/assign", "{\"assigned_to\":\"x\"}", OTHER_TENANT, "x").statusCode())
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), TENANT, user);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private JsonNode patch(String path, String body, String version, int expected, String user) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("X-Tenant-Id", TENANT).header("X-User", user)
                .header("If-Match", version).method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = raw("GET", path, null, tenant, "reader");
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> raw(String method, String path, String body, String tenant, String user) throws Exception {
        return send(method, path, body, "GET".equals(method) ? null : UUID.randomUUID().toString(), tenant, user);
    }

    private HttpResponse<String> send(String method, String path, String body, String key, String tenant, String user)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenant)
                .header("X-User", user)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
