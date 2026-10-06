package io.meridian.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
 * End-to-end over HTTP against PostgreSQL 18 with row-level security. A bank statement source reconciled against
 * the ledger entries of its nostro account (ledger-service stubbed): CSV and camt.053 intake, rule matching with
 * exact and tolerance rules, breaks with suggestions, the exception workflow, unmatch and manual matching.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReconciliationFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000d1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000d2";
    static final String USER = "recon-analyst";

    static final String CSV = """
            date,amount,ccy,dc,ref,e2e,name,text
            2026-03-02,100.00,USD,C,BANKREF1,E2E-1,Alice,Payment 1
            2026-03-03,250.00,USD,C,BANKREF2,E2E-2,Bob,"Payment 2, invoice 7"
            2026-03-06,74.90,USD,D,BANKREF3,,Shop,Card
            2026-03-07,999.00,USD,C,BANKREF4,E2E-9,Eve,Unknown
            """;

    static final String CAMT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08"><BkToCstmrStmt><Stmt>
              <Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy="EUR">1000.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Dt><Dt>2026-03-01</Dt></Dt></Bal>
              <Bal><Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy="EUR">%s</Amt><CdtDbtInd>CRDT</CdtDbtInd><Dt><Dt>2026-03-31</Dt></Dt></Bal>
              <Ntry><Amt Ccy="EUR">500.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><BookgDt><Dt>2026-03-10</Dt></BookgDt><ValDt><Dt>2026-03-10</Dt></ValDt>
                <AcctSvcrRef>CAMT-1</AcctSvcrRef><NtryDtls><TxDtls><Refs><EndToEndId>E2E-C1</EndToEndId></Refs>
                <RltdPties><Dbtr><Nm>ACME Ltd</Nm></Dbtr></RltdPties><RmtInf><Ustrd>Invoice 42</Ustrd></RmtInf></TxDtls></NtryDtls></Ntry>
              <Ntry><Amt Ccy="EUR">200.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><BookgDt><Dt>2026-03-12</Dt></BookgDt><ValDt><Dt>2026-03-12</Dt></ValDt>
                <AcctSvcrRef>CAMT-2</AcctSvcrRef></Ntry>
            </Stmt></BkToCstmrStmt></Document>
            """;

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient services;

    final HttpClient http = HttpClient.newHttpClient();
    final String nostro = UUID.randomUUID().toString();
    final String i1 = UUID.randomUUID().toString();
    final String i2 = UUID.randomUUID().toString();
    final String i3 = UUID.randomUUID().toString();
    final String i4 = UUID.randomUUID().toString();
    String source;
    String file;
    String run;
    final Map<String, JsonNode> breaks = new HashMap<>();

    @BeforeEach
    void stubLedger() throws Exception {
        when(services.get(eq("ledger-service"), startsWith("/v1/ledger-entries?ledger_account_id=" + nostro)))
                .thenReturn(json.readTree("""
                        {"data":[
                          {"id":"%s","ledger_account_id":"%s","direction":"CREDIT","amount":"100.00","currency":"USD","effective_at":"2026-03-02T10:00:00Z","value_date":"2026-03-02","reference":"E2E-1"},
                          {"id":"%s","ledger_account_id":"%s","direction":"CREDIT","amount":"250.00","currency":"USD","effective_at":"2026-03-03T10:00:00Z","value_date":"2026-03-03","reference":"E2E-2"},
                          {"id":"%s","ledger_account_id":"%s","direction":"DEBIT","amount":"75.50","currency":"USD","effective_at":"2026-03-05T10:00:00Z","value_date":"2026-03-05","description":"card spend"},
                          {"id":"%s","ledger_account_id":"%s","direction":"DEBIT","amount":"40.00","currency":"USD","effective_at":"2026-03-20T10:00:00Z","value_date":"2026-03-20","reference":"ORPHAN"}],
                         "has_more":false}""".formatted(i1, nostro, i2, nostro, i3, nostro, i4, nostro)));
        when(services.post(eq("ledger-service"), eq("/v1/journal-entries"), any(), any()))
                .thenReturn(json.readTree("{\"id\":\"0199a000-0000-7000-8000-00000000adj1\"}".replace("adj1", "0a01")));
    }

    @Test
    @Order(1)
    void sourcesAndRulesAreValidated() throws Exception {
        source = post("/v1/reconciliation/sources", """
                {"name":"Nostro USD","source_type":"BANK_STATEMENT","file_format":"CSV","ledger_account_id":"%s","currency":"USD",
                 "parser_config":{"columns":{"value_date":"date","amount":"amount","currency":"ccy","direction":"dc",
                   "external_ref":"ref","end_to_end_id":"e2e","counterparty":"name","narrative":"text"}}}"""
                .formatted(nostro), 201).get("id").asString();
        assertThat(code(post("/v1/reconciliation/rules", rule("MANY_TO_MANY", "[\"amount\"]", "0", 0, true, 1), 422)))
                .isEqualTo("unsupported_match_type");
        assertThat(code(post("/v1/reconciliation/rules", rule("ONE_TO_MANY", "[\"amount\"]", "0", 0, true, 1), 422)))
                .isEqualTo("reference_field_required");
        assertThat(code(post("/v1/reconciliation/rules", rule("ONE_TO_ONE", "[\"colour\"]", "0", 0, true, 1), 422)))
                .isEqualTo("validation_failed");
        assertThat(code(post("/v1/reconciliation/rules", rule("ONE_TO_ONE", "[\"amount\"]", "-1", 0, true, 1), 422)))
                .isEqualTo("validation_failed");
        post("/v1/reconciliation/rules",
                rule("ONE_TO_ONE", "[\"end_to_end_id\",\"amount\",\"value_date\",\"direction\"]", "0", 0, true, 10), 201);
        post("/v1/reconciliation/rules", rule("ONE_TO_ONE", "[\"amount\",\"value_date\"]", "1.00", 2, false, 50), 201);
    }

    @Test
    @Order(2)
    void csvFileIsParsedOnceAndIdempotent() throws Exception {
        String body = fileBody(source, "nostro-2026-03.csv", CSV);
        HttpResponse<String> created = send("POST", "/v1/reconciliation/files/import", body, "file-1", null, TENANT);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        JsonNode f = json.readTree(created.body());
        file = f.get("id").asString();
        assertThat(f.get("status").asString()).isEqualTo("PARSED");
        assertThat(f.get("record_count").asInt()).isEqualTo(4);
        assertThat(f.get("checksum").asString()).hasSize(64);
        assertThat(f.get("statement_date").asString()).isEqualTo("2026-03-07");
        JsonNode records = get("/v1/reconciliation/files/" + file + "/records", TENANT).get("data");
        assertThat(records).hasSize(4);
        assertThat(values(records, "narrative")).contains("Payment 2, invoice 7");

        HttpResponse<String> replay = send("POST", "/v1/reconciliation/files/import", body, "file-1", null, TENANT);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(code(post("/v1/reconciliation/files/import", body, 409))).isEqualTo("duplicate_file");
        assertThat(code(post("/v1/reconciliation/files/import",
                "{\"recon_source_id\":\"%s\",\"file_name\":\"empty.csv\"}".formatted(source), 422))).isEqualTo("content_required");
        assertThat(send("POST", "/v1/reconciliation/files/import", "{\"recon_source_id\":\"%s\"}".formatted(source), key(), null,
                TENANT).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/v1/reconciliation/files", body, key(), null, TENANT).statusCode())
                .as("files are created by /import only").isEqualTo(405);
        assertThat(outboxCount("ledger.recon_file.parsed")).isEqualTo(1);
    }

    @Test
    @Order(3)
    void failedFileIsReprocessedAfterParserFix() throws Exception {
        JsonNode src = post("/v1/reconciliation/sources", """
                {"name":"Card scheme","source_type":"CARD_SCHEME","file_format":"CSV","currency":"USD",
                 "parser_config":{"columns":{"value_date":"settle_date"}}}""", 201);
        String content = "value_date,amount,direction\n2026-03-01,10.00,C\n2026-03-02,-3.00,\n";
        JsonNode f = post("/v1/reconciliation/files/import", fileBody(src.get("id").asString(), "scheme.csv", content), 200);
        assertThat(f.get("status").asString()).isEqualTo("FAILED");
        assertThat(f.get("error").asString()).contains("settle_date");
        HttpResponse<String> patched = send("PATCH", "/v1/reconciliation/sources/" + src.get("id").asString(),
                "{\"parser_config\":{\"columns\":{}}}", null, "\"1\"", TENANT);
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        JsonNode again = post("/v1/reconciliation/files/" + f.get("id").asString() + "/reprocess", null, 200);
        assertThat(again.get("status").asString()).isEqualTo("PARSED");
        assertThat(again.get("record_count").asInt()).isEqualTo(2);
        assertThat(code(post("/v1/reconciliation/files/" + f.get("id").asString() + "/reprocess", null, 409)))
                .isEqualTo("invalid_transition");
    }

    @Test
    @Order(4)
    void camt053IsParsedAndBalanceChecked() throws Exception {
        String src = post("/v1/reconciliation/sources",
                "{\"name\":\"Nostro EUR\",\"source_type\":\"BANK_STATEMENT\",\"file_format\":\"CAMT_053\",\"currency\":\"EUR\"}",
                201).get("id").asString();
        JsonNode ok = post("/v1/reconciliation/files/import", fileBody(src, "stmt.xml", CAMT.formatted("1300.00")), 200);
        assertThat(ok.get("status").asString()).as(ok.toString()).isEqualTo("PARSED");
        assertThat(ok.get("record_count").asInt()).isEqualTo(2);
        assertThat(ok.get("closing_balance").asString()).startsWith("1300");
        assertThat(ok.get("statement_date").asString()).isEqualTo("2026-03-31");
        JsonNode recs = get("/v1/reconciliation/files/" + ok.get("id").asString() + "/records", TENANT).get("data");
        assertThat(values(recs, "end_to_end_id")).contains("E2E-C1");
        assertThat(values(recs, "counterparty")).contains("ACME Ltd");

        JsonNode bad = post("/v1/reconciliation/files/import", fileBody(src, "stmt-bad.xml", CAMT.formatted("1999.00")), 200);
        assertThat(bad.get("status").asString()).isEqualTo("FAILED");
        assertThat(bad.get("error").asString()).contains("Balance check failed");

        JsonNode pre = post("/v1/reconciliation/files/import", """
                {"recon_source_id":"%s","file_name":"api-push.json","opening_balance":"0","closing_balance":"30.00",
                 "records":[{"value_date":"2026-03-15","direction":"CREDIT","amount":"30.00","currency":"EUR","end_to_end_id":"E2E-P1"}]}"""
                .formatted(src), 200);
        assertThat(pre.get("status").asString()).as(pre.toString()).isEqualTo("PARSED");
        assertThat(pre.get("record_count").asInt()).isEqualTo(1);
    }

    @Test
    @Order(5)
    void runMatchesExactAndRaisesBreaksWithSuggestions() throws Exception {
        assertThat(code(post("/v1/reconciliation/runs", runBody(source, "2026-03-31", "2026-03-01"), 422)))
                .isEqualTo("invalid_date_range");
        JsonNode r = post("/v1/reconciliation/runs", runBody(source, "2026-03-01", "2026-03-31"), 201);
        run = r.get("id").asString();
        assertThat(r.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(r.get("matched_count").asInt()).isEqualTo(2);
        assertThat(r.get("exception_count").asInt()).isEqualTo(3);
        assertThat(outboxCount("ledger.recon_match.created")).isEqualTo(2);

        JsonNode matches = get("/v1/reconciliation/matches?recon_run_id=" + run, TENANT).get("data");
        assertThat(matches).hasSize(2);
        assertThat(values(matches, "match_type")).containsOnly("ONE_TO_ONE");
        JsonNode exceptions = get("/v1/reconciliation/exceptions?recon_run_id=" + run, TENANT).get("data");
        assertThat(exceptions).hasSize(3);
        exceptions.forEach(e -> breaks.put(e.get("reason").asString(), e));
        assertThat(breaks).containsOnlyKeys("AMOUNT_MISMATCH", "MISSING_INTERNAL", "MISSING_EXTERNAL");
        JsonNode suggestion = breaks.get("AMOUNT_MISMATCH").get("suggested_match");
        assertThat(suggestion.get("internal_entry_ids").get(0).asString()).isEqualTo(i3);
        assertThat(new BigDecimal(suggestion.get("difference").asString())).isEqualByComparingTo("-0.60");
        assertThat(breaks.get("MISSING_EXTERNAL").get("internal_entry_id").asString()).isEqualTo(i4);
        verify(services).get(eq("ledger-service"), startsWith("/v1/ledger-entries?ledger_account_id=" + nostro));

        JsonNode rerun = post("/v1/reconciliation/runs", runBody(source, "2026-03-01", "2026-03-31"), 201);
        assertThat(rerun.get("matched_count").asInt()).isZero();
        assertThat(rerun.get("exception_count").asInt()).as("open breaks are not raised twice").isZero();
        assertThat(outboxCount("ledger.recon_exception.raised")).isEqualTo(3);
        assertThat(outboxCount("ledger.recon_run.completed")).isEqualTo(2);
    }

    @Test
    @Order(6)
    void exceptionWorkflowAssignAcceptResolve() throws Exception {
        String mismatch = breaks.get("AMOUNT_MISMATCH").get("id").asString();
        HttpResponse<String> patch = send("PATCH", "/v1/reconciliation/exceptions/" + mismatch,
                "{\"status\":\"RESOLVED\"}", null, "\"1\"", TENANT);
        assertThat(patch.statusCode()).isEqualTo(422);
        assertThat(code(json.readTree(patch.body()))).isEqualTo("field_not_updatable");

        HttpResponse<String> assigned = send("POST", "/v1/reconciliation/exceptions/" + mismatch + "/assign",
                "{\"assigned_to\":\"carol\"}", "assign-1", null, TENANT);
        assertThat(assigned.statusCode()).isEqualTo(200);
        assertThat(json.readTree(assigned.body()).get("status").asString()).isEqualTo("INVESTIGATING");
        HttpResponse<String> assignedAgain = send("POST", "/v1/reconciliation/exceptions/" + mismatch + "/assign",
                "{\"assigned_to\":\"carol\"}", "assign-1", null, TENANT);
        assertThat(assignedAgain.headers().firstValue("Idempotent-Replayed")).contains("true");

        JsonNode match = post("/v1/reconciliation/exceptions/" + mismatch + "/accept-suggestion", null, 200);
        assertThat(match.get("confirmed_by").asString()).isEqualTo(USER);
        assertThat(match.get("internal_entry_ids").get(0).asString()).isEqualTo(i3);
        assertThat(match.get("external_record_ids").isArray()).isTrue();
        assertThat(get("/v1/reconciliation/exceptions/" + mismatch, TENANT).get("status").asString()).isEqualTo("RESOLVED");
        assertThat(code(post("/v1/reconciliation/exceptions/" + mismatch + "/accept-suggestion", null, 409)))
                .isEqualTo("invalid_transition");

        String missingInternal = breaks.get("MISSING_INTERNAL").get("id").asString();
        assertThat(code(post("/v1/reconciliation/exceptions/" + missingInternal + "/accept-suggestion", null, 409)))
                .isEqualTo("no_suggestion");
        assertThat(code(post("/v1/reconciliation/exceptions/" + missingInternal + "/resolve",
                "{\"resolution_note\":\"  \"}", 422))).isEqualTo("validation_failed");
        JsonNode resolved = post("/v1/reconciliation/exceptions/" + missingInternal + "/resolve", """
                {"resolution_note":"Booked missing receipt","adjustment":{"ledger_id":"%s","entries":[
                  {"ledger_account_id":"%s","direction":"DEBIT","amount":"999.00","currency":"USD"},
                  {"ledger_account_id":"%s","direction":"CREDIT","amount":"999.00","currency":"USD"}]}}"""
                .formatted(UUID.randomUUID(), nostro, UUID.randomUUID()), 200);
        assertThat(resolved.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(resolved.get("resolution_note").asString()).contains("ledger adjustment");
        verify(services).post(eq("ledger-service"), eq("/v1/journal-entries"), any(), startsWith("recon-adj:" + missingInternal));
        assertThat(code(post("/v1/reconciliation/exceptions/" + missingInternal + "/resolve",
                "{\"resolution_note\":\"again\"}", 409))).isEqualTo("invalid_transition");

        String missingExternal = breaks.get("MISSING_EXTERNAL").get("id").asString();
        JsonNode writtenOff = post("/v1/reconciliation/exceptions/" + missingExternal + "/resolve",
                "{\"resolution_note\":\"Immaterial\",\"adjustment\":{\"write_off\":true}}", 200);
        assertThat(writtenOff.get("status").asString()).isEqualTo("WRITTEN_OFF");
        assertThat(outboxCount("ledger.recon_exception.resolved")).isEqualTo(3);
    }

    @Test
    @Order(7)
    void unmatchReturnsRecordsToTheQueueAndManualMatchCompletesTheFile() throws Exception {
        JsonNode auto = null;
        for (JsonNode m : get("/v1/reconciliation/matches?recon_run_id=" + run, TENANT).get("data")) {
            if (!m.hasNonNull("confirmed_by")) {
                auto = m;
            }
        }
        assertThat(auto).as("an auto-confirmed match of the first run").isNotNull();
        String matchId = auto.get("id").asString();
        assertThat(code(post("/v1/reconciliation/matches/" + matchId + "/unmatch", "{\"reason\":\" \"}", 422)))
                .isEqualTo("validation_failed");
        JsonNode undo = post("/v1/reconciliation/matches/" + matchId + "/unmatch", "{\"reason\":\"wrong pairing\"}", 200);
        assertThat(undo.hasNonNull("unmatched_at")).isTrue();
        assertThat(undo.get("external_record_ids")).isEqualTo(auto.get("external_record_ids"));
        assertThat(code(post("/v1/reconciliation/matches/" + matchId + "/unmatch", "{\"reason\":\"again\"}", 409)))
                .isEqualTo("already_unmatched");
        assertThat(code(post("/v1/reconciliation/matches/" + undo.get("id").asString() + "/unmatch",
                "{\"reason\":\"x\"}", 409))).isEqualTo("not_unmatchable");

        JsonNode rerun = post("/v1/reconciliation/runs", runBody(source, "2026-03-01", "2026-03-31"), 201);
        assertThat(rerun.get("matched_count").asInt()).as("the unmatched pair is matched again").isEqualTo(1);
        assertThat(rerun.get("exception_count").asInt()).isZero();
        assertThat(outboxCount("ledger.recon_match.unmatched")).isEqualTo(1);

        // The 999.00 record (break resolved with an adjustment) is matched manually to the adjustment entry.
        String e4 = breaks.get("MISSING_INTERNAL").get("external_record_id").asString();
        String manual = """
                {"match_type":"MANUAL","external_record_ids":["%s"],"internal_entry_ids":["%s"],"confirmed_by":"someone-else"}"""
                .formatted(e4, UUID.randomUUID());
        JsonNode m = post("/v1/reconciliation/matches", manual, 201);
        assertThat(m.get("confirmed_by").asString()).isEqualTo(USER);
        assertThat(code(post("/v1/reconciliation/matches", manual.replace("someone-else", "x"), 409)))
                .isEqualTo("already_matched");
        assertThat(get("/v1/reconciliation/files/" + file, TENANT).get("status").asString()).isEqualTo("MATCHED");
        // 2 rule matches + accepted suggestion + rematch after unmatch + manual match
        assertThat(outboxCount("ledger.recon_match.created")).isEqualTo(5);
    }

    @Test
    @Order(8)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/reconciliation/sources/" + source, null, null, null, OTHER_TENANT).statusCode())
                .isEqualTo(404);
        assertThat(get("/v1/reconciliation/exceptions", OTHER_TENANT).get("data")).isEmpty();
        assertThat(get("/v1/reconciliation/files", OTHER_TENANT).get("data")).isEmpty();
        String anyBreak = breaks.get("MISSING_EXTERNAL").get("id").asString();
        assertThat(send("POST", "/v1/reconciliation/exceptions/" + anyBreak + "/assign", "{\"assigned_to\":\"mallory\"}",
                key(), null, OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/v1/reconciliation/runs", runBody(source, "2026-03-01", "2026-03-31"), key(), null,
                OTHER_TENANT).statusCode()).isEqualTo(422);
    }

    // ------------------------------------------------------------------ helpers

    private static java.util.List<String> values(JsonNode arr, String field) {
        java.util.List<String> out = new java.util.ArrayList<>();
        arr.forEach(n -> {
            if (n.hasNonNull(field)) {
                out.add(n.get(field).asString());
            }
        });
        return out;
    }

    private long outboxCount(String type) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", () ->
                jdbc.sql("select count(*) from outbox where event_type = ? and tenant_id = ?::uuid")
                        .params(type, TENANT).query(Long.class).single());
    }

    private String rule(String type, String fields, String tolerance, int days, boolean auto, int priority) {
        return """
                {"recon_source_id":"%s","name":"%s rule","priority":%d,"match_type":"%s","match_fields":%s,
                 "amount_tolerance":"%s","date_tolerance_days":%d,"auto_confirm":%s}"""
                .formatted(source, type, priority, type, fields, tolerance, days, auto);
    }

    private String fileBody(String sourceId, String name, String content) throws Exception {
        return """
                {"recon_source_id":"%s","file_name":"%s","content":%s}"""
                .formatted(sourceId, name, json.writeValueAsString(content));
    }

    private static String runBody(String sourceId, String from, String to) {
        return """
                {"recon_source_id":"%s","from_date":"%s","to_date":"%s"}""".formatted(sourceId, from, to);
    }

    private static String code(JsonNode problem) {
        return problem.path("code").asString();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, key(), null, TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, null, tenant);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String ifMatch,
                                      String tenant) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", USER).header("Content-Type", "application/json");
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
