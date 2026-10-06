package io.meridian.regulatoryreporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.regulatoryreporting.domain.ReportRenderer;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end over HTTP against PostgreSQL 18 with row-level security: a balance-sheet report fed by gl-service's
 * trial balance and loan-service's portfolio (ServiceClient mocked), maker-checker publication, runs with
 * validation, adjustments under four eyes, XBRL / CSV / JSON outputs with checksums, drilldown, submission to the
 * simulated regulator gateway, reruns, cancellation, schedules and deadline jobs (idempotent per business date),
 * filing calendar and CRS / FATCA generation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RegulatoryReportingFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000e1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000e2";
    static final String MAKER = "alice";
    static final String CHECKER = "bob";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient services;

    final HttpClient http = HttpClient.newHttpClient();
    final String le = UUID.randomUUID().toString();
    String definition;
    String goodRun;
    String badRun;
    String submission;

    @BeforeEach
    void upstream() throws Exception {
        // Balanced trial balance for period P-OK, unbalanced for P-BAD (debit-positive closing balances).
        when(services.get(eq("gl-service"), eq("/v1/gl/periods/P-OK/trial-balance"))).thenReturn(json.readTree("""
                {"balanced":true,"lines":[
                  {"gl_account_id":"a1","code":"1100","account_class":"ASSET","closing_balance":"1000.00"},
                  {"gl_account_id":"a2","code":"1200","account_class":"ASSET","closing_balance":"500.00"},
                  {"gl_account_id":"l1","code":"2100","account_class":"LIABILITY","closing_balance":"-1200.00"},
                  {"gl_account_id":"e1","code":"3100","account_class":"EQUITY","closing_balance":"-300.00"}]}"""));
        when(services.get(eq("gl-service"), eq("/v1/gl/periods/P-BAD/trial-balance"))).thenReturn(json.readTree("""
                {"balanced":false,"lines":[
                  {"gl_account_id":"a1","code":"1100","account_class":"ASSET","closing_balance":"1000.00"},
                  {"gl_account_id":"l1","code":"2100","account_class":"LIABILITY","closing_balance":"-900.00"}]}"""));
        when(services.get(eq("gl-service"), eq("/v1/gl/periods/P-DOWN/trial-balance")))
                .thenThrow(new ApiException(502, "upstream_error", "gl-service is not reachable"));
        when(services.get(eq("loan-service"), startsWith("/v1/loans?legal_entity_id=" + le))).thenReturn(json.readTree("""
                {"data":[{"id":"ln1","outstanding_principal":"700.00","status":"ACTIVE"},
                         {"id":"ln2","outstanding_principal":"300.50","status":"ACTIVE"},
                         {"id":"ln3","outstanding_principal":"50.00","status":"CLOSED"}],"has_more":false}"""));
    }

    @Test
    @Order(1)
    void definitionsValidateFormatAndDeadline() throws Exception {
        assertThat(code(post("/v1/reporting/definitions", definitionBody("NG-CBN-PDF", "PDF", null), MAKER, 422)))
                .isEqualTo("validation_failed");
        assertThat(code(post("/v1/reporting/definitions", definitionBody("NG-CBN-BAD", "XBRL", "\"deadline_rule\":\"SOON\","),
                MAKER, 422))).isEqualTo("validation_failed");
        JsonNode d = post("/v1/reporting/definitions", definitionBody("NG-CBN-MBR300", "XBRL",
                "\"deadline_rule\":\"PERIOD_END+10BD\",\"filing_offset_days\":2,"), MAKER, 201);
        definition = d.get("id").asString();
        assertThat(d.get("status").asString()).isEqualTo("DRAFT");
        assertThat(d.has("active_version")).isFalse();
        // No published version yet: runs are refused.
        assertThat(code(post("/v1/reporting/runs", runBody("2026-01-31", "P-OK"), MAKER, 422))).isEqualTo("definition_not_active");
    }

    @Test
    @Order(2)
    void versionsAreCompiledAndPublishedUnderFourEyes() throws Exception {
        JsonNode bad = post("/v1/reporting/definitions/" + definition + "/versions", versionBody("""
                {"cell_ref":"A","source":"tb","value":"closing_balance"},
                {"cell_ref":"B","expression":"C + 1"},
                {"cell_ref":"C","expression":"B * 2"},
                {"cell_ref":"D","expression":"UNKNOWN_CELL"}""", "[]"), MAKER, 422);
        assertThat(code(bad)).isEqualTo("invalid_definition");
        assertThat(bad.get("errors").toString()).contains("unknown cell reference UNKNOWN_CELL");
        JsonNode cyc = post("/v1/reporting/definitions/" + definition + "/versions", versionBody("""
                {"cell_ref":"B","expression":"C + 1"},{"cell_ref":"C","expression":"B * 2"}""", "[]"), MAKER, 422);
        assertThat(cyc.get("errors").toString()).contains("circular");

        JsonNode v1 = post("/v1/reporting/definitions/" + definition + "/versions", versionBody(MAPPINGS, RULES), MAKER, 201);
        assertThat(v1.get("definition_version").asInt()).isEqualTo(1);
        assertThat(v1.get("status").asString()).isEqualTo("DRAFT");
        assertThat(v1.get("field_mappings").isArray()).isTrue();

        assertThat(code(post("/v1/reporting/definitions/" + definition + "/publish", "{\"definition_version\":1}", MAKER, 422)))
                .isEqualTo("four_eyes_violation");
        JsonNode published = post("/v1/reporting/definitions/" + definition + "/publish",
                "{\"definition_version\":1,\"comment\":\"approved by finance\"}", CHECKER, 200);
        assertThat(published.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(published.get("approved_by").asString()).isEqualTo(CHECKER);
        JsonNode def = get("/v1/reporting/definitions/" + definition, TENANT);
        assertThat(def.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(def.get("active_version").asInt()).isEqualTo(1);
        assertThat(code(post("/v1/reporting/definitions/" + definition + "/publish", "{\"definition_version\":1}", CHECKER, 409)))
                .isEqualTo("invalid_transition");
        assertThat(get("/v1/reporting/definitions/" + definition + "/versions", TENANT).get("data")).hasSize(1);
        assertThat(outboxCount("reporting.report_definition.published")).isEqualTo(1);
    }

    @Test
    @Order(3)
    void cleanRunProducesFilesWithChecksumsAndLineage() throws Exception {
        String body = runBody("2026-01-31", "P-OK");
        HttpResponse<String> first = send("POST", "/v1/reporting/runs", body, "run-jan", null, TENANT, MAKER);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        JsonNode run = json.readTree(first.body());
        goodRun = run.get("id").asString();
        assertThat(run.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(run.get("error_count").asInt()).isZero();
        assertThat(run.get("period_start").asString()).isEqualTo("2026-01-01");
        assertThat(run.get("data_snapshot_id").asString()).startsWith("sha256:");
        HttpResponse<String> replay = send("POST", "/v1/reporting/runs", body, "run-jan", null, TENANT, MAKER);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(goodRun);

        JsonNode cells = get("/v1/reporting/runs/" + goodRun + "/cells?limit=50", TENANT).get("data");
        assertThat(cellValue(cells, "ASSETS")).isEqualTo("1500.00");
        assertThat(cellValue(cells, "LIABILITIES")).isEqualTo("1200.00");
        assertThat(cellValue(cells, "TOTAL_LE")).isEqualTo("1500.00");
        assertThat(cellValue(cells, "ACTIVE_LOANS")).isEqualTo("2");
        assertThat(cellValue(cells, "LOAN_BOOK")).isEqualTo("1000.50");

        JsonNode outputs = get("/v1/reporting/runs/" + goodRun + "/outputs", TENANT).get("data");
        assertThat(outputs).hasSize(3);
        String xmlId = null;
        String csvId = null;
        for (JsonNode o : outputs) {
            if ("XBRL".equals(o.get("format").asString())) {
                xmlId = o.get("id").asString();
            } else if ("CSV".equals(o.get("format").asString())) {
                csvId = o.get("id").asString();
            }
        }
        JsonNode xmlMeta = get("/v1/reporting/outputs/" + xmlId, TENANT);
        assertThat(xmlMeta.has("content")).isFalse();
        HttpResponse<String> xml = send("GET", "/v1/reporting/outputs/" + xmlId + "/xml", null, null, null, TENANT, MAKER);
        assertThat(xml.statusCode()).isEqualTo(200);
        assertThat(xml.headers().firstValue("Content-Type").orElse("")).startsWith("application/xml");
        assertThat(xml.body()).contains("<xbrli:xbrl").contains("<rep:ASSETS contextRef=\"C1\" unitRef=\"U1\" decimals=\"2\">1500.00</rep:ASSETS>");
        assertThat(ReportRenderer.sha256(xml.body())).isEqualTo(xmlMeta.get("sha256").asString());
        HttpResponse<String> csv = send("GET", "/v1/reporting/outputs/" + csvId + "/csv", null, null, null, TENANT, MAKER);
        assertThat(csv.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(csv.body()).startsWith("cell_ref,label,type,value").contains("ASSETS,Total assets,NUMBER,1500.00");
        assertThat(send("GET", "/v1/reporting/outputs/" + csvId + "/xml", null, null, null, TENANT, MAKER).statusCode())
                .isEqualTo(404);

        JsonNode drill = get("/v1/reporting/runs/" + goodRun + "/drilldown?cell_ref=ASSETS", TENANT);
        assertThat(drill.get("source_name").asString()).isEqualTo("tb");
        assertThat(drill.get("source_count").asInt()).isEqualTo(2);
        assertThat(drill.get("source_records").get(0).get("id").asString()).isEqualTo("a1");
        assertThat(drill.get("mapping").get("filter").asString()).isEqualTo("account_class = 'ASSET'");
        assertThat(code(sendJson("GET", "/v1/reporting/runs/" + goodRun + "/drilldown?cell_ref=NOPE", null, TENANT, MAKER, 404)))
                .isEqualTo("cell_not_found");
        assertThat(outboxCount("reporting.report_run.completed")).isEqualTo(1);
    }

    @Test
    @Order(4)
    void validationErrorsAreFixedByAdjustmentsUnderFourEyes() throws Exception {
        JsonNode run = post("/v1/reporting/runs", runBody("2026-02-28", "P-BAD"), MAKER, 201);
        badRun = run.get("id").asString();
        assertThat(run.get("status").asString()).isEqualTo("PARTIALLY_SUCCEEDED");
        assertThat(run.get("error_count").asInt()).isEqualTo(2);
        JsonNode results = get("/v1/reporting/runs/" + badRun + "/validation-results", TENANT).get("data");
        assertThat(results.toString()).contains("BS_BALANCE").contains("BS_TOTAL");
        assertThat(outboxCount("reporting.report_run.validation_failed")).isEqualTo(1);

        assertThat(code(post("/v1/reporting/runs/" + badRun + "/adjustments",
                "{\"cell_ref\":\"EQUITY\",\"adjusted_value\":\"abc\",\"reason\":\"x\"}", MAKER, 422))).isEqualTo("validation_failed");
        JsonNode adj = post("/v1/reporting/runs/" + badRun + "/adjustments",
                "{\"cell_ref\":\"EQUITY\",\"adjusted_value\":\"100\",\"reason\":\"Retained earnings booked late\"}", MAKER, 201);
        assertThat(adj.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(adj.get("original_value").asString()).isEqualTo("0.00");
        assertThat(adj.get("adjusted_value").asString()).isEqualTo("100.00");
        assertThat(code(post("/v1/reporting/runs/" + badRun + "/adjustments",
                "{\"cell_ref\":\"EQUITY\",\"adjusted_value\":\"1\",\"reason\":\"again\"}", MAKER, 409))).isEqualTo("adjustment_pending");
        String adjId = adj.get("id").asString();
        assertThat(code(post("/v1/reporting/adjustments/" + adjId + "/approve", "{}", MAKER, 422))).isEqualTo("four_eyes_violation");
        JsonNode approved = post("/v1/reporting/adjustments/" + adjId + "/approve", "{\"comment\":\"ok\"}", CHECKER, 200);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(code(post("/v1/reporting/adjustments/" + adjId + "/reject", "{\"reason\":\"no\"}", CHECKER, 409)))
                .isEqualTo("invalid_transition");

        JsonNode after = get("/v1/reporting/runs/" + badRun, TENANT);
        assertThat(after.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(after.get("revision").asInt()).isEqualTo(2);
        assertThat(after.get("error_count").asInt()).isZero();
        assertThat(get("/v1/reporting/outputs?report_run_id=" + badRun + "&revision=2", TENANT).get("data")).hasSize(3);
        JsonNode drill = get("/v1/reporting/runs/" + badRun + "/drilldown?cell_ref=EQUITY", TENANT);
        assertThat(drill.get("is_adjusted").asBoolean()).isTrue();
        assertThat(drill.get("value").asString()).isEqualTo("100.00");
        assertThat(drill.get("computed_value").asString()).isEqualTo("0.00");
        assertThat(drill.get("adjustments")).hasSize(1);

        // A revision-1 file can no longer be submitted.
        String oldOutput = get("/v1/reporting/outputs?report_run_id=" + badRun + "&revision=1&format=XBRL", TENANT)
                .get("data").get(0).get("id").asString();
        assertThat(code(post("/v1/reporting/submissions", submissionBody(badRun, oldOutput), MAKER, 422))).isEqualTo("output_outdated");
    }

    @Test
    @Order(5)
    void submissionIsSignedOffSubmittedAndAcknowledged() throws Exception {
        String output = get("/v1/reporting/outputs?report_run_id=" + goodRun + "&format=XBRL", TENANT).get("data").get(0)
                .get("id").asString();
        JsonNode s = post("/v1/reporting/submissions", submissionBody(goodRun, output), MAKER, 201);
        submission = s.get("id").asString();
        assertThat(s.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        // Due 10 business days after 31 Jan 2026 (13 Feb), plus a 2-day jurisdiction offset rolled to Monday 16 Feb.
        assertThat(s.get("deadline_at").asString()).startsWith("2026-02-16T23:59:59");
        assertThat(code(post("/v1/reporting/submissions", submissionBody(goodRun, output), MAKER, 409))).isEqualTo("submission_exists");
        assertThat(code(post("/v1/reporting/submissions/" + submission + "/submit", null, MAKER, 409))).isEqualTo("invalid_transition");
        assertThat(code(post("/v1/reporting/submissions/" + submission + "/approve", "{}", MAKER, 422))).isEqualTo("four_eyes_violation");
        assertThat(post("/v1/reporting/submissions/" + submission + "/approve", "{}", CHECKER, 200).get("status").asString())
                .isEqualTo("APPROVED");
        JsonNode submitted = post("/v1/reporting/submissions/" + submission + "/submit", null, CHECKER, 200);
        assertThat(submitted.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(submitted.get("gateway_message").asString()).contains("accepted for processing");
        assertThat(submitted.hasNonNull("submitted_at")).isTrue();
        JsonNode ack = post("/v1/reporting/submissions/" + submission + "/acknowledge",
                "{\"regulator_reference\":\"CBN-2026-000123\"}", CHECKER, 200);
        assertThat(ack.get("status").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(ack.get("regulator_reference").asString()).isEqualTo("CBN-2026-000123");
        // Submitted runs cannot be adjusted any more.
        assertThat(code(post("/v1/reporting/runs/" + goodRun + "/adjustments",
                "{\"cell_ref\":\"ASSETS\",\"adjusted_value\":\"1\",\"reason\":\"late\"}", MAKER, 409))).isEqualTo("run_already_submitted");
        JsonNode rejected = post("/v1/reporting/submissions/" + submission + "/record-rejection",
                "{\"rejection_reason\":\"Taxonomy version outdated\"}", CHECKER, 200);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(outboxCount("reporting.report_submission.submitted")).isEqualTo(1);
        assertThat(outboxCount("reporting.report_submission.acknowledged")).isEqualTo(1);
        assertThat(outboxCount("reporting.report_submission.rejected")).isEqualTo(1);

        // Resubmission: a corrected run supersedes the rejected one.
        JsonNode rerun = post("/v1/reporting/runs/" + goodRun + "/rerun", "{\"reason\":\"regulator rejection\"}", MAKER, 201);
        assertThat(rerun.get("trigger").asString()).isEqualTo("RESUBMISSION");
        assertThat(rerun.get("supersedes_run_id").asString()).isEqualTo(goodRun);
        assertThat(rerun.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(get("/v1/reporting/runs/" + goodRun, TENANT).get("superseded_by_run_id").asString())
                .isEqualTo(rerun.get("id").asString());
        assertThat(code(post("/v1/reporting/runs/" + goodRun + "/rerun", "{}", MAKER, 409))).isEqualTo("already_superseded");
    }

    @Test
    @Order(6)
    void queuedRunsCanBeCancelledAndFailedSourcesFailTheRun() throws Exception {
        String queuedBody = runBody("2026-03-31", "P-OK").replace("\"trigger\":\"MANUAL\"", "\"trigger\":\"API\",\"execute\":false");
        JsonNode queued = post("/v1/reporting/runs", queuedBody, MAKER, 201);
        assertThat(queued.get("status").asString()).isEqualTo("QUEUED");
        String id = queued.get("id").asString();
        assertThat(post("/v1/reporting/runs/" + id + "/cancel", "{\"reason\":\"wrong period\"}", MAKER, 200).get("status").asString())
                .isEqualTo("CANCELLED");
        assertThat(code(post("/v1/reporting/runs/" + id + "/execute", null, MAKER, 409))).isEqualTo("invalid_transition");
        assertThat(code(post("/v1/reporting/runs/" + id + "/cancel", "{}", MAKER, 409))).isEqualTo("invalid_transition");

        JsonNode failed = post("/v1/reporting/runs", runBody("2026-04-30", "P-DOWN"), MAKER, 201);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("error").asString()).contains("upstream_error");
        assertThat(outboxCount("reporting.report_run.failed")).isEqualTo(1);
        assertThat(outboxCount("reporting.report_run.cancelled")).isEqualTo(1);
        assertThat(code(post("/v1/reporting/runs", runBody("2026-04-30", "P-OK")
                .replace("\"trigger\":\"MANUAL\"", "\"trigger\":\"SCHEDULE\""), MAKER, 422))).isEqualTo("validation_failed");
    }

    @Test
    @Order(7)
    void schedulesRunOncePerBusinessDate() throws Exception {
        assertThat(code(post("/v1/reporting/schedules", scheduleBody("every day"), MAKER, 422))).isEqualTo("validation_failed");
        JsonNode sch = post("/v1/reporting/schedules", scheduleBody("0 0 6 * * MON-FRI"), MAKER, 201);
        assertThat(sch.hasNonNull("next_run_at")).isTrue();
        // Weekend: the weekday cron does not fire.
        JsonNode weekend = post("/v1/reporting/schedules/run-due?business_date=2026-03-01", null, MAKER, 200);
        assertThat(weekend.get("counts").get("due").asInt()).isZero();
        // Monthly report: a run on Monday 2 March reports the February period end.
        JsonNode job = post("/v1/reporting/schedules/run-due?business_date=2026-03-02", null, MAKER, 200);
        assertThat(job.get("status").asString()).as(job.toString()).isEqualTo("COMPLETED");
        assertThat(job.get("counts").get("runs_created").asInt()).isEqualTo(1);
        assertThat(job.get("replayed").asBoolean()).isFalse();
        JsonNode runs = get("/v1/reporting/runs?trigger=SCHEDULE", TENANT).get("data");
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).get("as_of_date").asString()).isEqualTo("2026-02-28");
        assertThat(runs.get(0).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(runs.get(0).get("report_schedule_id").asString()).isEqualTo(sch.get("id").asString());
        assertThat(get("/v1/reporting/schedules/" + sch.get("id").asString(), TENANT).get("last_run_date").asString())
                .isEqualTo("2026-03-02");

        JsonNode replay = post("/v1/reporting/schedules/run-due?business_date=2026-03-02", null, MAKER, 200);
        assertThat(replay.get("replayed").asBoolean()).isTrue();
        assertThat(get("/v1/reporting/runs?trigger=SCHEDULE", TENANT).get("data")).hasSize(1);
        assertThat(get("/v1/reporting/job-runs?job=SCHEDULED_RUNS", TENANT).get("data")).hasSize(2);
        assertThat(code(sendJson("DELETE", "/v1/reporting/schedules/" + sch.get("id").asString(), null, TENANT, MAKER, 409)))
                .isEqualTo("schedule_has_runs");
    }

    @Test
    @Order(8)
    void calendarAndDeadlineAlerts() throws Exception {
        JsonNode cal = get("/v1/reporting/definitions/" + definition + "/calendar?from=2026-02-01&to=2026-04-30&legal_entity_id="
                + le, TENANT);
        JsonNode entries = cal.get("entries");
        assertThat(entries.size()).isEqualTo(3);
        assertThat(entries.get(0).get("period_end").asString()).isEqualTo("2026-01-31");
        assertThat(entries.get(0).get("due_date").asString()).isEqualTo("2026-02-16");
        assertThat(entries.get(0).get("status").asString()).isIn("FILED", "IN_PROGRESS", "OVERDUE");

        // A second clean submission due 16 Feb triggers an alert three days ahead, once per business date.
        String rerun = get("/v1/reporting/runs?supersedes_run_id=" + goodRun, TENANT).get("data").get(0).get("id").asString();
        String output = get("/v1/reporting/outputs?report_run_id=" + rerun + "&format=JSON", TENANT).get("data").get(0)
                .get("id").asString();
        post("/v1/reporting/submissions", submissionBody(rerun, output), MAKER, 201);
        JsonNode alerts = post("/v1/reporting/submissions/deadline-alerts?business_date=2026-02-13", null, MAKER, 200);
        assertThat(alerts.get("counts").get("alerts").asInt()).isEqualTo(1);
        JsonNode again = post("/v1/reporting/submissions/deadline-alerts?business_date=2026-02-13", null, MAKER, 200);
        assertThat(again.get("replayed").asBoolean()).isTrue();
        assertThat(outboxCount("reporting.report_submission.deadline_approaching")).isEqualTo(1);
    }

    @Test
    @Order(9)
    void crsRecordsAreGeneratedAndExportedAsOecdXml() throws Exception {
        String c1 = UUID.randomUUID().toString();
        String c2 = UUID.randomUUID().toString();
        String c3 = UUID.randomUUID().toString();
        String acc1 = UUID.randomUUID().toString();
        String ledger1 = UUID.randomUUID().toString();
        when(services.get(eq("customer-service"), startsWith("/v1/customers?legal_entity_id=" + le))).thenReturn(json.readTree("""
                {"data":[
                 {"id":"%s","customer_type":"INDIVIDUAL","first_name":"Ada","last_name":"Obi","date_of_birth":"1980-05-01",
                  "tax_residence_country":"GB","nationality":"NG"},
                 {"id":"%s","customer_type":"INDIVIDUAL","first_name":"Local","last_name":"Person","tax_residence_country":"NG"},
                 {"id":"%s","customer_type":"COMPANY","legal_name":"Acme US Inc","tax_residence_country":"US","us_person":true}
                ],"has_more":false}""".formatted(c1, c2, c3)));
        when(services.get(eq("customer-service"), startsWith("/v1/customers/" + c1 + "/identifiers"))).thenReturn(json.readTree("""
                {"data":[{"identifier_type":"TAX_ID","identifier_value":"GB-UTR-998877","issuing_country":"GB"}],"has_more":false}"""));
        when(services.get(eq("customer-service"), startsWith("/v1/customers/" + c1 + "/addresses"))).thenReturn(json.readTree("""
                {"data":[{"address_type":"RESIDENTIAL","line1":"1 High St","city":"London","postal_code":"E1 1AA","country":"GB"}],
                 "has_more":false}"""));
        when(services.get(eq("ledger-service"), startsWith("/v1/ledger-accounts/" + ledger1 + "/balance-at")))
                .thenReturn(json.readTree("{\"posted_balance\":\"250000.456\"}"));
        when(services.get(eq("customer-service"), startsWith("/v1/customers/" + c3 + "/"))).thenReturn(json.readTree("{\"data\":[]}"));
        // Interest capitalisations: acc1 earned 1,200 in 2025 (and 999 in 2024); other accounts none.
        when(services.get(eq("deposit-service"), startsWith("/v1/deposit-accounts/"))).thenAnswer(inv -> {
            String path = inv.getArgument(1);
            if (path.startsWith("/v1/deposit-accounts/" + acc1 + "/capitalisations")) {
                return json.readTree("""
                        {"data":[{"period_end":"2025-06-30","gross_amount":"1200.00","kind":"CREDIT"},
                                 {"period_end":"2024-12-31","gross_amount":"999.00","kind":"CREDIT"}],"has_more":false}""");
            }
            return json.readTree("{\"data\":[]}");
        });
        when(services.get(eq("deposit-service"), startsWith("/v1/deposit-accounts?customer_id=" + c1))).thenReturn(json.readTree("""
                {"data":[{"id":"%s","account_number":"0123456789","currency":"NGN","status":"ACTIVE","legal_entity_id":"%s",
                          "ledger_account_id":"%s"}],"has_more":false}""".formatted(acc1, le, ledger1)));
        when(services.get(eq("deposit-service"), startsWith("/v1/deposit-accounts?customer_id=" + c3))).thenReturn(json.readTree("""
                {"data":[{"id":"%s","account_number":"9988776655","currency":"USD","status":"CLOSED","legal_entity_id":"%s"}],
                 "has_more":false}""".formatted(UUID.randomUUID(), le)));

        String body = "{\"regime\":\"CRS\",\"reporting_year\":2025,\"legal_entity_id\":\"%s\",\"reporting_jurisdiction\":\"NG\"}"
                .formatted(le);
        JsonNode gen = post("/v1/reporting/crs-fatca-records/generate", body, MAKER, 200);
        assertThat(gen.get("customers_scanned").asInt()).isEqualTo(3);
        assertThat(gen.get("records_created").asInt()).isEqualTo(1);
        assertThat(gen.get("not_reportable").asInt()).isEqualTo(2);
        JsonNode again = post("/v1/reporting/crs-fatca-records/generate", body, MAKER, 200);
        assertThat(again.get("records_created").asInt()).isZero();
        assertThat(again.get("records_updated").asInt()).isEqualTo(1);
        JsonNode rec = get("/v1/reporting/crs-fatca-records?regime=CRS&reporting_year=2025", TENANT).get("data").get(0);
        assertThat(rec.get("tax_residence").asString()).isEqualTo("GB");
        assertThat(rec.get("tin").asString()).isEqualTo("GB-UTR-998877");
        assertThat(new java.math.BigDecimal(rec.get("account_balance").asString())).isEqualByComparingTo("250000.46");
        assertThat(new java.math.BigDecimal(rec.get("gross_interest").asString())).isEqualByComparingTo("1200.00");
        assertThat(rec.get("holder_name").asString()).isEqualTo("Ada Obi");

        JsonNode fatca = post("/v1/reporting/crs-fatca-records/generate", body.replace("\"CRS\"", "\"FATCA\""), MAKER, 200);
        assertThat(fatca.get("records_created").asInt()).isEqualTo(1);
        JsonNode fr = get("/v1/reporting/crs-fatca-records?regime=FATCA", TENANT).get("data").get(0);
        assertThat(fr.get("account_holder_type").asString()).isEqualTo("ENTITY");
        assertThat(fr.get("is_closed").asBoolean()).isTrue();

        HttpResponse<String> xml = send("GET", "/v1/reporting/crs-fatca-records/oecd-xml?regime=CRS&reporting_year=2025&legal_entity_id="
                + le + "&transmitting_country=NG", null, null, null, TENANT, MAKER);
        assertThat(xml.statusCode()).as(xml.body()).isEqualTo(200);
        assertThat(xml.body()).contains("<crs:CRS_OECD").contains("<crs:MessageTypeIndic>CRS701</crs:MessageTypeIndic>")
                .contains("receivingCountry=\"GB\"").contains("<crs:AccountNumber>0123456789</crs:AccountNumber>")
                .contains("<crs:TIN issuedBy=\"GB\">GB-UTR-998877</crs:TIN>").contains("<crs:Type>CRS502</crs:Type>");
        assertThat(send("GET", "/v1/reporting/crs-fatca-records/oecd-xml?regime=CRS&reporting_year=2019&legal_entity_id="
                + le + "&transmitting_country=NG", null, null, null, TENANT, MAKER).statusCode()).isEqualTo(422);
    }

    @Test
    @Order(10)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/reporting/runs/" + goodRun, null, null, null, OTHER_TENANT, MAKER).statusCode()).isEqualTo(404);
        assertThat(get("/v1/reporting/definitions", OTHER_TENANT).get("data")).isEmpty();
        assertThat(get("/v1/reporting/crs-fatca-records", OTHER_TENANT).get("data")).isEmpty();
        HttpResponse<String> approve = send("POST", "/v1/reporting/submissions/" + submission + "/approve", "{}", key(), null,
                OTHER_TENANT, CHECKER);
        assertThat(approve.statusCode()).isEqualTo(404);
        assertThat(post("/v1/reporting/definitions/" + definition + "/deprecate", "{\"reason\":\"replaced by MBR400\"}",
                MAKER, 200).get("status").asString()).isEqualTo("DEPRECATED");
        assertThat(code(post("/v1/reporting/definitions/" + definition + "/deprecate", "{\"reason\":\"again\"}", MAKER, 409)))
                .isEqualTo("invalid_transition");
        assertThat(code(post("/v1/reporting/runs", runBody("2026-05-31", "P-OK"), MAKER, 422))).isEqualTo("definition_not_active");
    }

    // ------------------------------------------------------------------ fixtures

    static final String MAPPINGS = """
            {"cell_ref":"ASSETS","label":"Total assets","source":"tb","aggregate":"SUM","value":"closing_balance",
             "filter":"account_class = 'ASSET'"},
            {"cell_ref":"LIABILITIES","label":"Total liabilities","source":"tb","value":"-closing_balance",
             "filter":"account_class = 'LIABILITY'"},
            {"cell_ref":"EQUITY","label":"Equity","source":"tb","value":"-closing_balance","filter":"account_class = 'EQUITY'"},
            {"cell_ref":"TOTAL_LE","label":"Liabilities and equity","expression":"LIABILITIES + EQUITY"},
            {"cell_ref":"ACTIVE_LOANS","label":"Active loans","source":"loans","aggregate":"COUNT","filter":"status = 'ACTIVE'"},
            {"cell_ref":"LOAN_BOOK","label":"Loan book","source":"loans","value":"outstanding_principal",
             "filter":"status IN ('ACTIVE', 'ARREARS')"}""";

    static final String RULES = """
            [{"code":"BS_BALANCE","type":"BALANCE","debit":["ASSETS"],"credit":["LIABILITIES","EQUITY"],"tolerance":"0.01"},
             {"code":"BS_TOTAL","type":"EQUALS","left":"ASSETS","right":"TOTAL_LE","tolerance":"0.01"},
             {"code":"REQ","type":"REQUIRED","cells":["ASSETS","LIABILITIES"]},
             {"code":"LOANS_POSITIVE","type":"RANGE","cell":"ACTIVE_LOANS","min":0,"severity":"WARNING"}]""";

    private String versionBody(String mappings, String rules) {
        return """
                {"effective_from":"2026-01-01","change_note":"initial",
                 "data_sources":[
                   {"name":"tb","type":"SERVICE","service":"gl-service","path":"/v1/gl/periods/{param.period_id}/trial-balance",
                    "rows_pointer":"/lines","id_field":"gl_account_id"},
                   {"name":"loans","type":"SERVICE","service":"loan-service","path":"/v1/loans?legal_entity_id={legal_entity_id}"}],
                 "field_mappings":[%s],
                 "validation_rules":%s}""".formatted(mappings, rules);
    }

    private String definitionBody(String code, String format, String extra) {
        return """
                {"code":"%s","name":"Monthly balance sheet","country_pack":"NG","jurisdiction":"NG","regulator":"CBN",
                 "report_family":"BALANCE_SHEET","frequency":"MONTHLY",%s"format":"%s"}"""
                .formatted(code, extra == null ? "" : extra, format);
    }

    private String runBody(String asOf, String period) {
        return """
                {"report_definition_id":"%s","legal_entity_id":"%s","as_of_date":"%s","trigger":"MANUAL",
                 "parameters":{"period_id":"%s","currency":"NGN"}}""".formatted(definition, le, asOf, period);
    }

    private String submissionBody(String run, String output) {
        return "{\"report_run_id\":\"%s\",\"report_output_id\":\"%s\",\"channel\":\"API\"}".formatted(run, output);
    }

    private String scheduleBody(String cron) {
        return """
                {"report_definition_id":"%s","legal_entity_id":"%s","cron":"%s","timezone":"Africa/Lagos",
                 "parameters":{"period_id":"P-OK","currency":"NGN"}}""".formatted(definition, le, cron);
    }

    private static String cellValue(JsonNode cells, String ref) {
        for (JsonNode c : cells) {
            if (ref.equals(c.get("cell_ref").asString())) {
                return c.get("value").asString();
            }
        }
        throw new AssertionError("no cell " + ref);
    }

    private <T> T asTenant(Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", body);
    }

    private long outboxCount(String type) throws Exception {
        return asTenant(() -> jdbc.sql("select count(*) from outbox where event_type = ? and tenant_id = ?::uuid")
                .params(type, TENANT).query(Long.class).single());
    }

    private static String code(JsonNode problem) {
        return problem.path("code").asString();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, key(), null, TENANT, user);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, null, tenant, MAKER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private JsonNode sendJson(String method, String path, String body, String tenant, String user, int expected) throws Exception {
        HttpResponse<String> r = send(method, path, body, null, null, tenant, user);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String ifMatch,
                                      String tenant, String user) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
