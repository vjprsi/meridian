package io.meridian.regulatoryreporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, adding a definition version, recording an
 * adjustment and preparing a submission open approval requests in workflow-service; decisions arriving from
 * workflow-service are applied with the approver as principal, still under the four-eyes rules, and stale or
 * redelivered decisions are ignored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class RegulatoryReportingWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000e7";
    static final String MAKER = "maker.anna";
    static final String CHECKER = "checker.ben";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final String le = UUID.randomUUID().toString();
    /** Last approval request body and the id workflow-service returned for it, per resource type. */
    final Map<String, Map<String, Object>> lastRequest = new ConcurrentHashMap<>();
    final Map<String, String> lastRequestId = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubs() throws Exception {
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            Map<String, Object> body = (Map<String, Object>) inv.getArgument(2);
            String id = UUID.randomUUID().toString();
            lastRequest.put((String) body.get("resource_type"), body);
            lastRequestId.put((String) body.get("resource_type"), id);
            return json.readTree("{\"id\":\"" + id + "\",\"status\":\"PENDING\"}");
        });
        when(serviceClient.get(eq("workflow-service"), startsWith("/v1/approval-requests?")))
                .thenReturn(json.readTree("{\"data\":[]}"));
        when(serviceClient.get(eq("gl-service"), eq("/v1/gl/periods/P-OK/trial-balance"))).thenReturn(json.readTree("""
                {"balanced":true,"lines":[
                  {"gl_account_id":"a1","code":"1100","account_class":"ASSET","closing_balance":"1500.00"},
                  {"gl_account_id":"l1","code":"2100","account_class":"LIABILITY","closing_balance":"-1200.00"},
                  {"gl_account_id":"e1","code":"3100","account_class":"EQUITY","closing_balance":"-300.00"}]}"""));
        when(serviceClient.get(eq("gl-service"), eq("/v1/gl/periods/P-BAD/trial-balance"))).thenReturn(json.readTree("""
                {"balanced":false,"lines":[
                  {"gl_account_id":"a1","code":"1100","account_class":"ASSET","closing_balance":"1000.00"},
                  {"gl_account_id":"l1","code":"2100","account_class":"LIABILITY","closing_balance":"-900.00"}]}"""));
    }

    @Test
    void definitionVersionIsPublishedFromWorkflowUnderFourEyes() throws Exception {
        String definition = definition("WF-REG-V");
        String version = post("/v1/reporting/definitions/" + definition + "/versions", VERSION, MAKER, 201).get("id").asString();
        Map<String, Object> req = lastRequest.get("report_definition_version");
        assertThat(req).containsEntry("resource_id", version).containsEntry("action", "publish");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("definition_version")).isEqualTo(1);
        assertThat(payload.get("row_version")).isNotNull();

        // the author approving in the inbox is still refused by the four-eyes rule
        dispatch("report_definition_version", version, payload, MAKER, Outcome.APPROVED);
        assertThat(versionStatus(definition)).isEqualTo("DRAFT");

        dispatch("report_definition_version", version, payload, CHECKER, Outcome.APPROVED);
        JsonNode v = get("/v1/reporting/definitions/" + definition + "/versions").get("data").get(0);
        assertThat(v.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(v.get("approved_by").asString()).isEqualTo(CHECKER);
        assertThat(get("/v1/reporting/definitions/" + definition).get("active_version").asInt()).isEqualTo(1);

        // redelivery of the same decision is harmless
        dispatch("report_definition_version", version, payload, CHECKER, Outcome.APPROVED);
        assertThat(versionStatus(definition)).isEqualTo("ACTIVE");

        // a rejected version becomes REJECTED (final); the active version stays in force
        String v2 = post("/v1/reporting/definitions/" + definition + "/versions",
                VERSION.replace("2026-01-01", "2026-06-01"), MAKER, 201).get("id").asString();
        Map<?, ?> p2 = (Map<?, ?>) lastRequest.get("report_definition_version").get("payload");
        dispatch("report_definition_version", v2, p2, MAKER, Outcome.REJECTED);
        assertThat(get("/v1/reporting/definition-versions/" + v2).get("status").asString()).isEqualTo("DRAFT");
        dispatch("report_definition_version", v2, p2, CHECKER, Outcome.REJECTED);
        JsonNode rejected = get("/v1/reporting/definition-versions/" + v2);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejected_by").asString()).isEqualTo(CHECKER);
        assertThat(get("/v1/reporting/definitions/" + definition).get("active_version").asInt()).isEqualTo(1);
        // a late approval of the rejected version does nothing
        dispatch("report_definition_version", v2, p2, CHECKER, Outcome.APPROVED);
        assertThat(get("/v1/reporting/definition-versions/" + v2).get("status").asString()).isEqualTo("REJECTED");

        // direct rejection: four eyes, DRAFT only
        String v3 = post("/v1/reporting/definitions/" + definition + "/versions",
                VERSION.replace("2026-01-01", "2026-07-01"), MAKER, 201).get("id").asString();
        assertThat(post("/v1/reporting/definition-versions/" + v3 + "/reject", "{\"reason\":\"mine\"}", MAKER, 422)
                .get("code").asString()).isEqualTo("four_eyes_violation");
        assertThat(post("/v1/reporting/definition-versions/" + v3 + "/reject", "{\"reason\":\"wrong cell refs\"}", CHECKER, 200)
                .get("rejection_reason").asString()).isEqualTo("wrong cell refs");
        assertThat(post("/v1/reporting/definition-versions/" + v3 + "/reject", "{\"reason\":\"again\"}", CHECKER, 409)
                .get("code").asString()).isEqualTo("invalid_transition");
    }

    @Test
    void adjustmentsAreDecidedFromWorkflowAndStaleDecisionsIgnored() throws Exception {
        String definition = activeDefinition("WF-REG-A");
        String run = post("/v1/reporting/runs", runBody(definition, "2026-02-28", "P-BAD"), MAKER, 201).get("id").asString();

        String adj = post("/v1/reporting/runs/" + run + "/adjustments",
                "{\"cell_ref\":\"EQUITY\",\"adjusted_value\":\"100\",\"reason\":\"Retained earnings booked late\"}", MAKER, 201)
                .get("id").asString();
        Map<String, Object> req = lastRequest.get("report_adjustment");
        assertThat(req).containsEntry("resource_id", adj).containsEntry("action", "approve");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("adjusted_value")).isEqualTo("100.00");

        dispatch("report_adjustment", adj, payload, MAKER, Outcome.APPROVED);
        assertThat(get("/v1/reporting/adjustments/" + adj).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatch("report_adjustment", adj, payload, CHECKER, Outcome.APPROVED);
        JsonNode approved = get("/v1/reporting/adjustments/" + adj);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("approved_by").asString()).isEqualTo(CHECKER);
        assertThat(get("/v1/reporting/runs/" + run).get("revision").asInt()).isEqualTo(2);

        // redelivery does not produce another revision
        dispatch("report_adjustment", adj, payload, CHECKER, Outcome.APPROVED);
        assertThat(get("/v1/reporting/runs/" + run).get("revision").asInt()).isEqualTo(2);

        // rejection from workflow
        String rej = post("/v1/reporting/runs/" + run + "/adjustments",
                "{\"cell_ref\":\"LIABILITIES\",\"adjusted_value\":\"1\",\"reason\":\"typo\"}", MAKER, 201).get("id").asString();
        Map<?, ?> rejPayload = (Map<?, ?>) lastRequest.get("report_adjustment").get("payload");
        dispatch("report_adjustment", rej, rejPayload, CHECKER, Outcome.REJECTED);
        assertThat(get("/v1/reporting/adjustments/" + rej).get("status").asString()).isEqualTo("REJECTED");

        // a decision about other content (stale fingerprint) is ignored
        String other = post("/v1/reporting/runs/" + run + "/adjustments",
                "{\"cell_ref\":\"ASSETS\",\"adjusted_value\":\"1000\",\"reason\":\"reclass\"}", MAKER, 201).get("id").asString();
        ObjectNode stale = decision("report_adjustment", other, lastRequest.get("report_adjustment").get("payload"), CHECKER);
        ((ObjectNode) stale.get("payload")).put("adjusted_value", "999.00");
        dispatcher.dispatch(UUID.fromString(TENANT), stale, Outcome.APPROVED);
        assertThat(get("/v1/reporting/adjustments/" + other).get("status").asString()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void submissionIsSignedOffOrRejectedFromWorkflow() throws Exception {
        String definition = activeDefinition("WF-REG-S");
        String run = post("/v1/reporting/runs", runBody(definition, "2026-01-31", "P-OK"), MAKER, 201).get("id").asString();
        String sub = post("/v1/reporting/submissions", submissionBody(run, xbrlOutput(run)), MAKER, 201).get("id").asString();
        Map<String, Object> req = lastRequest.get("report_submission");
        assertThat(req).containsEntry("resource_id", sub).containsEntry("action", "approve");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("sha256")).isNotNull();
        String requestId = lastRequestId.get("report_submission");
        assertThat(get("/v1/reporting/submissions/" + sub).get("approval_request_id").asString()).isEqualTo(requestId);

        // a decision on another (stale) approval request for this submission is ignored
        ObjectNode stale = decision("report_submission", sub, payload, CHECKER);
        dispatcher.dispatch(UUID.fromString(TENANT), stale, Outcome.APPROVED);
        assertThat(get("/v1/reporting/submissions/" + sub).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        // the preparer cannot sign off from the inbox
        dispatcher.dispatch(UUID.fromString(TENANT), decision("report_submission", sub, payload, MAKER, requestId), Outcome.APPROVED);
        assertThat(get("/v1/reporting/submissions/" + sub).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("report_submission", sub, payload, CHECKER, requestId), Outcome.APPROVED);
        JsonNode signed = get("/v1/reporting/submissions/" + sub);
        assertThat(signed.get("status").asString()).isEqualTo("APPROVED");
        assertThat(signed.get("approved_by").asString()).isEqualTo(CHECKER);
        // redelivery is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision("report_submission", sub, payload, CHECKER, requestId), Outcome.APPROVED);
        assertThat(get("/v1/reporting/submissions/" + sub).get("status").asString()).isEqualTo("APPROVED");

        // a rejected sign-off closes the submission so the run can be submitted again
        String run2 = post("/v1/reporting/runs", runBody(definition, "2026-03-31", "P-OK"), MAKER, 201).get("id").asString();
        String output2 = xbrlOutput(run2);
        String sub2 = post("/v1/reporting/submissions", submissionBody(run2, output2), MAKER, 201).get("id").asString();
        Map<?, ?> p2 = (Map<?, ?>) lastRequest.get("report_submission").get("payload");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("report_submission", sub2, p2, CHECKER,
                lastRequestId.get("report_submission")), Outcome.REJECTED);
        assertThat(get("/v1/reporting/submissions/" + sub2).get("status").asString()).isEqualTo("REJECTED");
        post("/v1/reporting/submissions", submissionBody(run2, output2), MAKER, 201);
    }

    @Test
    void directDecisionClosesTheInboxItemAfterCommit() throws Exception {
        String definition = definition("WF-REG-D");
        String version = post("/v1/reporting/definitions/" + definition + "/versions", VERSION, MAKER, 201).get("id").asString();
        post("/v1/reporting/definitions/" + definition + "/publish", "{\"definition_version\":1}", CHECKER, 200);
        verify(serviceClient, atLeastOnce()).get(eq("workflow-service"), contains("resource_id=" + version));
    }

    // ------------------------------------------------------------------ fixtures

    static final String VERSION = """
            {"effective_from":"2026-01-01","change_note":"initial",
             "data_sources":[{"name":"tb","type":"SERVICE","service":"gl-service",
               "path":"/v1/gl/periods/{param.period_id}/trial-balance","rows_pointer":"/lines","id_field":"gl_account_id"}],
             "field_mappings":[
               {"cell_ref":"ASSETS","label":"Total assets","source":"tb","value":"closing_balance","filter":"account_class = 'ASSET'"},
               {"cell_ref":"LIABILITIES","label":"Total liabilities","source":"tb","value":"-closing_balance",
                "filter":"account_class = 'LIABILITY'"},
               {"cell_ref":"EQUITY","label":"Equity","source":"tb","value":"-closing_balance","filter":"account_class = 'EQUITY'"}],
             "validation_rules":[{"code":"BS_BALANCE","type":"BALANCE","debit":["ASSETS"],"credit":["LIABILITIES","EQUITY"],
               "tolerance":"0.01"}]}""";

    private String definition(String code) throws Exception {
        return post("/v1/reporting/definitions", """
                {"code":"%s","name":"Balance sheet","country_pack":"NG","jurisdiction":"NG","regulator":"CBN",
                 "report_family":"BALANCE_SHEET","frequency":"MONTHLY","format":"XBRL"}""".formatted(code), MAKER, 201)
                .get("id").asString();
    }

    private String activeDefinition(String code) throws Exception {
        String definition = definition(code);
        post("/v1/reporting/definitions/" + definition + "/versions", VERSION, MAKER, 201);
        post("/v1/reporting/definitions/" + definition + "/publish", "{\"definition_version\":1}", CHECKER, 200);
        return definition;
    }

    private String runBody(String definition, String asOf, String period) {
        return """
                {"report_definition_id":"%s","legal_entity_id":"%s","as_of_date":"%s","trigger":"MANUAL",
                 "parameters":{"period_id":"%s","currency":"NGN"}}""".formatted(definition, le, asOf, period);
    }

    private String submissionBody(String run, String output) {
        return "{\"report_run_id\":\"%s\",\"report_output_id\":\"%s\",\"channel\":\"API\"}".formatted(run, output);
    }

    private String xbrlOutput(String run) throws Exception {
        return get("/v1/reporting/outputs?report_run_id=" + run + "&format=XBRL").get("data").get(0).get("id").asString();
    }

    private String versionStatus(String definition) throws Exception {
        return get("/v1/reporting/definitions/" + definition + "/versions").get("data").get(0).get("status").asString();
    }

    private void dispatch(String type, String id, Object payload, String approver, Outcome outcome) throws Exception {
        dispatcher.dispatch(UUID.fromString(TENANT), decision(type, id, payload, approver), outcome);
    }

    private ObjectNode decision(String type, String id, Object payload, String approver) {
        return decision(type, id, payload, approver, UUID.randomUUID().toString());
    }

    private ObjectNode decision(String type, String id, Object payload, String approver, String requestId) {
        ObjectNode row = json.createObjectNode();
        row.put("id", requestId);
        row.put("resource_type", type);
        row.put("resource_id", id);
        row.put("action", "approve");
        row.put("maker", MAKER);
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
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
