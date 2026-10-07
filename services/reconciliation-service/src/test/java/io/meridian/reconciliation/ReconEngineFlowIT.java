package io.meridian.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
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
 * The matching engine end to end: a rule set published under four-eyes, an MT940 statement imported once (by file
 * hash and by statement id + sequence), a run matching by end-to-end id, remittance reference with a deducted fee
 * and a batch against two ledger entries sharing its reference, explainable matches, run metrics, the metrics API
 * (auto-match rate per rule, break ageing) and the per-tenant EOD endpoint used by business-day-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReconEngineFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000e1";
    static final String MAKER = "recon-analyst";
    static final String CHECKER = "recon-head";

    static final String MT940 = """
            :20:NOSTRO-USD-0305
            :25:US33000111122223333
            :28C:64/1
            :60F:C260301USD1000,00
            :61:2603020302C1500,00NTRFE2E-RUN-0001//BR0001
            :86:/EREF/E2E-RUN-0001/NAME/ACME LTD/REMI/USTD//Order 77/
            :61:2603030303C997,50NTRFNONREF//BR0002
            :86:/NAME/Blue River GmbH/REMI/USTD//Invoice INV-2026-00042/
            :61:2603040304D3000,00NTRFNONREF//BATCH-7Q2X9
            :86:SEPA BULK DEBIT
            :61:2603050305D75,00NMSCNONREF//BR0004
            :86:ACCOUNT MAINTENANCE FEE
            :62F:C260305USD422,50
            -
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
    final String otherAccount = UUID.randomUUID().toString();
    final String e1 = UUID.randomUUID().toString();
    final String e2 = UUID.randomUUID().toString();
    final String e3 = UUID.randomUUID().toString();
    final String e4 = UUID.randomUUID().toString();
    final String e5 = UUID.randomUUID().toString();
    String source;
    String ruleSet;
    String run;

    @BeforeEach
    void stubLedger() throws Exception {
        when(services.get(eq("ledger-service"), startsWith("/v1/ledger-entries?ledger_account_id=" + nostro)))
                .thenReturn(json.readTree("""
                        {"data":[
                          {"id":"%s","direction":"CREDIT","amount":"1500.00","currency":"USD","value_date":"2026-03-02","reference":"E2E-RUN-0001"},
                          {"id":"%s","direction":"CREDIT","amount":"1000.00","currency":"USD","value_date":"2026-03-03","reference":"INV-2026-00042","description":"Invoice INV-2026-00042 Blue River"},
                          {"id":"%s","direction":"DEBIT","amount":"1200.00","currency":"USD","value_date":"2026-03-04","reference":"BATCH-7Q2X9"},
                          {"id":"%s","direction":"DEBIT","amount":"1800.00","currency":"USD","value_date":"2026-03-04","reference":"BATCH-7Q2X9"},
                          {"id":"%s","direction":"DEBIT","amount":"60.00","currency":"USD","value_date":"2026-03-05","reference":"PAY-IN-FLIGHT"}],
                         "has_more":false}""".formatted(e1, e2, e3, e4, e5)));
        when(services.get(eq("ledger-service"), startsWith("/v1/ledger-entries?ledger_account_id=" + otherAccount)))
                .thenReturn(json.readTree("{\"data\":[],\"has_more\":false}"));
    }

    @Test
    @Order(1)
    void ruleSetIsPublishedUnderFourEyes() throws Exception {
        source = post(MAKER, "/v1/reconciliation/sources", """
                {"name":"Nostro USD","source_type":"BANK_STATEMENT","file_format":"MT940","currency":"USD",
                 "ledger_account_id":"%s","parser_config":{"eod_lookback_days":4}}""".formatted(nostro), 201)
                .get("id").asString();
        post(MAKER, "/v1/reconciliation/sources", """
                {"name":"Empty card source","source_type":"CARD_SCHEME","file_format":"CSV","currency":"USD",
                 "ledger_account_id":"%s"}""".formatted(otherAccount), 201);

        assertThat(code(post(MAKER, "/v1/reconciliation/rule-sets", """
                {"name":"bad","rules":[{"code":"rx","type":"REFERENCE_EXTRACT","patterns":["(a+)+$"]}]}""", 422)))
                .isEqualTo("validation_failed");
        JsonNode draft = post(MAKER, "/v1/reconciliation/rule-sets",
                "{\"recon_source_id\":\"" + source + "\",\"name\":\"Nostro standard\"}", 201);
        ruleSet = draft.get("id").asString();
        assertThat(draft.get("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.get("version").asInt()).isEqualTo(1);
        assertThat(draft.get("rules").size()).as("the standard template").isGreaterThanOrEqualTo(9);

        JsonNode submitted = post(MAKER, "/v1/reconciliation/rule-sets/" + ruleSet + "/submit", null, 200);
        assertThat(submitted.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(submitted.get("submitted_by").asString()).isEqualTo(MAKER);
        assertThat(code(post(MAKER, "/v1/reconciliation/rule-sets/" + ruleSet + "/approve", null, 422)))
                .isEqualTo("four_eyes_violation");
        HttpResponse<String> edit = send("PATCH", MAKER, "/v1/reconciliation/rule-sets/" + ruleSet,
                "{\"auto_match_threshold\":0.92}", null, "\"" + submitted.get("row_version").asString() + "\"");
        assertThat(edit.statusCode()).as("pending versions are frozen").isEqualTo(409);

        JsonNode rejected = post(CHECKER, "/v1/reconciliation/rule-sets/" + ruleSet + "/reject",
                "{\"reason\":\"raise the threshold\"}", 200);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        HttpResponse<String> fixed = send("PATCH", MAKER, "/v1/reconciliation/rule-sets/" + ruleSet,
                "{\"auto_match_threshold\":0.92}", null, "\"" + rejected.get("row_version").asString() + "\"");
        assertThat(fixed.statusCode()).as(fixed.body()).isEqualTo(200);
        assertThat(json.readTree(fixed.body()).get("status").asString()).isEqualTo("DRAFT");
        post(MAKER, "/v1/reconciliation/rule-sets/" + ruleSet + "/submit", null, 200);
        JsonNode published = post(CHECKER, "/v1/reconciliation/rule-sets/" + ruleSet + "/approve", null, 200);
        assertThat(published.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(published.get("approved_by").asString()).isEqualTo(CHECKER);
        assertThat(outboxCount("ledger.recon_rule_set.published")).isEqualTo(1);
    }

    @Test
    @Order(2)
    void mt940IsImportedOnceByHashAndByStatementId() throws Exception {
        JsonNode f = post(MAKER, "/v1/reconciliation/files/import", file("stmt-64-1.sta", MT940), 200);
        assertThat(f.get("status").asString()).as(f.toString()).isEqualTo("PARSED");
        assertThat(f.get("record_count").asInt()).isEqualTo(4);
        assertThat(f.get("statement_id").asString()).isEqualTo("NOSTRO-USD-0305");
        assertThat(f.get("sequence_no").asString()).isEqualTo("64/1");
        assertThat(f.get("account_id").asString()).isEqualTo("US33000111122223333");
        assertThat(dec(f.get("closing_balance"))).isEqualByComparingTo("422.50");
        assertThat(dec(f.get("movements_total"))).isEqualByComparingTo("-577.50");
        JsonNode records = get("/v1/reconciliation/files/" + f.get("id").asString() + "/records").get("data");
        assertThat(records).hasSize(4);
        JsonNode first = null;
        for (JsonNode rec : records) {
            if (rec.path("line_no").asInt() == 1) {
                first = rec;
            }
        }
        assertThat(first).as(records.toString()).isNotNull();
        assertThat(first.get("end_to_end_id").asString()).isEqualTo("E2E-RUN-0001");
        assertThat(first.get("counterparty").asString()).isEqualTo("ACME LTD");
        assertThat(first.get("is_reversal").asBoolean()).isFalse();

        assertThat(code(post(MAKER, "/v1/reconciliation/files/import", file("again.sta", MT940), 409)))
                .isEqualTo("duplicate_file");
        assertThat(code(post(MAKER, "/v1/reconciliation/files/import", file("resent.sta", MT940.replace("-\n", "-\n\n")), 409)))
                .as("same statement id and sequence, different bytes").isEqualTo("duplicate_statement");
        JsonNode broken = post(MAKER, "/v1/reconciliation/files/import",
                file("broken.sta", MT940.replace(":28C:64/1", ":28C:64/2").replace("C260305USD422,50", "C260305USD999,99")), 200);
        assertThat(broken.get("status").asString()).isEqualTo("FAILED");
        assertThat(broken.get("error").asString()).contains("Balance check failed");
    }

    @Test
    @Order(3)
    void runMatchesWithTheRuleSetAndExplainsEveryMatch() throws Exception {
        JsonNode r = post(MAKER, "/v1/reconciliation/runs", """
                {"recon_source_id":"%s","from_date":"2026-03-01","to_date":"2026-03-05"}""".formatted(source), 201);
        run = r.get("id").asString();
        assertThat(r.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(r.get("recon_rule_set_id").asString()).isEqualTo(ruleSet);
        assertThat(r.get("external_count").asInt()).isEqualTo(4);
        assertThat(r.get("matched_count").asInt()).isEqualTo(3);
        assertThat(r.get("exception_count").asInt()).as("bank fee line + in-flight payment").isEqualTo(2);
        assertThat(dec(r.get("auto_match_rate"))).isEqualByComparingTo("0.75");
        JsonNode metrics = r.get("metrics");
        assertThat(metrics.get("engine").asString()).isEqualTo("rule_set");
        assertThat(metrics.get("break_reasons").get("MISSING_INTERNAL").asInt()).isEqualTo(1);

        Map<String, JsonNode> byRule = new HashMap<>();
        for (JsonNode m : get("/v1/reconciliation/matches?recon_run_id=" + run).get("data")) {
            byRule.put(m.get("rule_code").asString(), m);
            assertThat(m.get("recon_rule_set_id").asString()).isEqualTo(ruleSet);
            assertThat(m.get("explanation").get("components").isObject()).isTrue();
        }
        assertThat(byRule).containsOnlyKeys("exact_end_to_end", "batch_keyed", "remittance_ref");
        assertThat(byRule.get("exact_end_to_end").get("internal_entry_ids").get(0).asString()).isEqualTo(e1);
        JsonNode fee = byRule.get("remittance_ref");
        assertThat(fee.get("explanation").get("components").get("amount_fit").asString()).isEqualTo("FEE");
        assertThat(dec(fee.get("difference"))).isEqualByComparingTo("-2.50");
        JsonNode batch = byRule.get("batch_keyed");
        assertThat(batch.get("match_type").asString()).isEqualTo("ONE_TO_MANY");
        assertThat(batch.get("internal_entry_ids").size()).isEqualTo(2);

        Map<String, JsonNode> breaks = new HashMap<>();
        get("/v1/reconciliation/exceptions?recon_run_id=" + run).get("data").forEach(e -> breaks.put(e.get("reason").asString(), e));
        assertThat(breaks).containsOnlyKeys("MISSING_INTERNAL", "MISSING_EXTERNAL");
        assertThat(breaks.get("MISSING_EXTERNAL").get("internal_entry_id").asString()).isEqualTo(e5);
    }

    @Test
    @Order(4)
    void metricsReportRatesPerRuleAndBreakAgeing() throws Exception {
        JsonNode m = get("/v1/reconciliation/runs/metrics?from_date=2026-01-01&to_date=2026-12-31&recon_source_id=" + source);
        assertThat(m.get("totals").get("runs").asInt()).isEqualTo(1);
        assertThat(m.get("totals").get("auto_match_rate").asDouble()).isEqualTo(0.75);
        assertThat(m.get("by_source").get(0).get("name").asString()).isEqualTo("Nostro USD");
        java.util.List<String> rules = new java.util.ArrayList<>();
        m.get("by_rule").forEach(x -> rules.add(x.get("rule").asString()));
        assertThat(rules).contains("exact_end_to_end", "batch_keyed", "remittance_ref");
        JsonNode ageing = m.get("open_breaks");
        assertThat(ageing.get("total").asInt()).isEqualTo(2);
        assertThat(ageing.get("by_age").get("0-1").asInt()).isEqualTo(2);
        assertThat(ageing.get("unassigned").asInt()).isEqualTo(2);
        assertThat(ageing.get("by_reason").has("MISSING_INTERNAL")).isTrue();
        assertThat(send("GET", MAKER, "/v1/reconciliation/runs/metrics?from_date=2026-03-05&to_date=2026-03-01", null, null, null)
                .statusCode()).isEqualTo(422);
    }

    @Test
    @Order(5)
    void endOfDayRunsEverySourceOncePerBusinessDate() throws Exception {
        JsonNode first = post(MAKER, "/v1/reconciliation/runs/eod?business_date=2026-03-05", null, 200);
        assertThat(first.get("sources").asInt()).isEqualTo(2);
        assertThat(first.get("runs_succeeded").asInt()).isEqualTo(1);
        assertThat(first.get("statements_missing").asInt()).as("the empty card source").isEqualTo(1);
        assertThat(first.get("records_processed").asInt()).as("the one open statement line").isEqualTo(1);
        assertThat(first.get("records_failed").asInt()).isZero();
        assertThat(first.get("breaks_raised").asInt()).as("open breaks are not raised twice").isZero();
        String eodRun = null;
        for (JsonNode s : first.get("per_source")) {
            if (s.get("recon_source_id").asString().equals(source)) {
                assertThat(s.get("status").asString()).isEqualTo("SUCCEEDED");
                assertThat(s.get("from_date").asString()).isEqualTo("2026-03-01");
                eodRun = s.get("recon_run_id").asString();
            }
        }
        assertThat(get("/v1/reconciliation/runs/" + eodRun).get("business_date").asString()).isEqualTo("2026-03-05");

        JsonNode again = post(MAKER, "/v1/reconciliation/runs/eod?business_date=2026-03-05", null, 200);
        assertThat(again.get("runs_succeeded").asInt()).isZero();
        assertThat(again.get("runs_skipped").asInt()).as("idempotent per business date and source").isEqualTo(1);
        assertThat(outboxCount("ledger.recon_eod.completed")).isEqualTo(2);
        assertThat(get("/v1/reconciliation/runs?business_date=2026-03-05").get("data")).hasSize(1);
    }

    @Test
    @Order(6)
    void simulationStoresNothing() throws Exception {
        long matches = count("recon_match");
        JsonNode sim = post(MAKER, "/v1/reconciliation/rule-sets/" + ruleSet + "/simulate", """
                {"recon_source_id":"%s","from_date":"2026-03-01","to_date":"2026-03-05"}""".formatted(source), 200);
        assertThat(sim.get("metrics").get("external_count").asInt()).as("only the open fee line is left").isEqualTo(1);
        assertThat(count("recon_match")).isEqualTo(matches);
    }

    // ------------------------------------------------------------------ helpers

    private long outboxCount(String type) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", () ->
                jdbc.sql("select count(*) from outbox where event_type = ? and tenant_id = ?::uuid")
                        .params(type, TENANT).query(Long.class).single());
    }

    private long count(String table) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", () ->
                jdbc.sql("select count(*) from " + table + " where tenant_id = ?::uuid").param(TENANT).query(Long.class).single());
    }

    private String file(String name, String content) throws Exception {
        return """
                {"recon_source_id":"%s","file_name":"%s","content":%s}""".formatted(source, name, json.writeValueAsString(content));
    }

    private static java.math.BigDecimal dec(JsonNode n) {
        return new java.math.BigDecimal(n.asString());
    }

    private static String code(JsonNode problem) {
        return problem.path("code").asString();
    }

    private JsonNode post(String user, String path, String body, int expected) throws Exception {
        HttpResponse<String> r = send("POST", user, path, body, UUID.randomUUID().toString(), null);
        assertThat(r.statusCode()).as(path + " " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = send("GET", MAKER, path, null, null, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String user, String path, String body, String idemKey,
                                      String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json");
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
