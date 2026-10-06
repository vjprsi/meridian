package io.meridian.gl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.gl.application.LedgerAggregationService;
import io.meridian.gl.application.LedgerAggregationService.LedgerPosting;
import io.meridian.gl.application.LedgerAggregationService.PostingLine;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end over HTTP against PostgreSQL 18 with row-level security. One legal entity with a small chart of
 * accounts, monthly periods for January and February 2026, ledger postings aggregated into the GL, a manual journal
 * under maker-checker, IFRS 9 provisions, the four-eyes period close, ERP export and reopen.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GlFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000c1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000c2";
    static final String MAKER = "alice";
    static final String CHECKER = "bob";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    LedgerAggregationService aggregation;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient services;

    final HttpClient http = HttpClient.newHttpClient();
    final String le = UUID.randomUUID().toString();
    final String otherLe = UUID.randomUUID().toString();
    String assets;
    String cash;
    String allowance;
    String liabilities;
    String deposits;
    String income;
    String fees;
    String expenses;
    String impairment;
    String jan;
    String feb;

    @BeforeAll
    void chart() throws Exception {
        assets = account("1000", "Assets", "ASSET", null, false);
        cash = account("1100", "Cash", "ASSET", assets, true);
        allowance = account("1900", "Loss allowance", "ASSET", assets, true);
        liabilities = account("2000", "Liabilities", "LIABILITY", null, false);
        deposits = account("2100", "Customer deposits", "LIABILITY", liabilities, true);
        income = account("4000", "Income", "INCOME", null, false);
        fees = account("4100", "Fee income", "INCOME", income, true);
        expenses = account("5000", "Expenses", "EXPENSE", null, false);
        impairment = account("5100", "Impairment expense", "EXPENSE", expenses, true);
    }

    @Test
    @Order(1)
    void chartOfAccountsEnforcesHierarchyRules() throws Exception {
        JsonNode c = get("/v1/gl/accounts/" + cash, TENANT);
        assertThat(c.get("level").asInt()).isEqualTo(2);
        assertThat(c.get("parent_id").asString()).isEqualTo(assets);

        assertThat(code(post("/v1/gl/accounts", accountBody("1110", "Petty cash", "ASSET", cash, true), MAKER, 422)))
                .isEqualTo("parent_is_postable");
        assertThat(code(post("/v1/gl/accounts", accountBody("1120", "Wrong class", "LIABILITY", assets, true), MAKER, 422)))
                .isEqualTo("account_class_mismatch");
        String foreignHeader = post("/v1/gl/accounts", """
                {"legal_entity_id":"%s","code":"1000","name":"Other assets","account_class":"ASSET","is_postable":false}"""
                .formatted(otherLe), MAKER, 201).get("id").asString();
        assertThat(code(post("/v1/gl/accounts", accountBody("1130", "Cross entity", "ASSET", foreignHeader, true), MAKER, 422)))
                .isEqualTo("parent_legal_entity_mismatch");

        // Header with children cannot become postable; stale If-Match is rejected.
        HttpResponse<String> r = send("PATCH", "/v1/gl/accounts/" + assets, "{\"is_postable\":true}", null, "\"1\"",
                TENANT, MAKER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(422);
        assertThat(code(json.readTree(r.body()))).isEqualTo("account_has_children");
        send("PATCH", "/v1/gl/accounts/" + cash, "{\"name\":\"Cash at bank\"}", null, "\"1\"", TENANT, MAKER);
        r = send("PATCH", "/v1/gl/accounts/" + cash, "{\"name\":\"Cash again\"}", null, "\"1\"", TENANT, MAKER);
        assertThat(r.statusCode()).isEqualTo(409);
    }

    @Test
    @Order(2)
    void createIsIdempotentAndValidated() throws Exception {
        String body = accountBody("2200", "Term deposits", "LIABILITY", liabilities, true);
        HttpResponse<String> first = send("POST", "/v1/gl/accounts", body, "acct-2200", null, TENANT, MAKER);
        assertThat(first.statusCode()).isEqualTo(201);
        HttpResponse<String> replay = send("POST", "/v1/gl/accounts", body, "acct-2200", null, TENANT, MAKER);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString())
                .isEqualTo(json.readTree(first.body()).get("id").asString());
        HttpResponse<String> reused = send("POST", "/v1/gl/accounts",
                accountBody("2300", "Other", "LIABILITY", liabilities, true), "acct-2200", null, TENANT, MAKER);
        assertThat(reused.statusCode()).isEqualTo(422);
        assertThat(code(json.readTree(reused.body()))).isEqualTo("idempotency_key_reused");

        HttpResponse<String> missing = send("POST", "/v1/gl/accounts",
                "{\"legal_entity_id\":\"%s\",\"code\":\"9\"}".formatted(le), "acct-bad", null, TENANT, MAKER);
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(code(json.readTree(missing.body()))).isEqualTo("validation_failed");
    }

    @Test
    @Order(3)
    void mappingRulesResolveByPriorityAndEffectiveDate() throws Exception {
        rule("{\"ledger_account_type\":\"ASSET\",\"gl_account_id\":\"%s\"}".formatted(cash), 201);
        rule("{\"ledger_account_type\":\"LIABILITY\",\"gl_account_id\":\"%s\"}".formatted(deposits), 201);
        rule("{\"ledger_account_type\":\"INCOME\",\"entry_type\":\"FEE\",\"gl_account_id\":\"%s\"}".formatted(fees), 201);
        String term = get("/v1/gl/accounts?legal_entity_id=" + le + "&code=2200", TENANT).get("data").get(0).get("id").asString();
        rule("{\"ledger_account_type\":\"LIABILITY\",\"priority\":10,\"valid_from\":\"2030-01-01\",\"gl_account_id\":\"%s\"}"
                .formatted(term), 201);

        assertThat(code(rule("{\"ledger_account_type\":\"LIABILITY\",\"gl_account_id\":\"%s\"}".formatted(deposits), 409)))
                .isEqualTo("conflicting_mapping_rule");
        assertThat(code(rule("{\"gl_account_id\":\"%s\"}".formatted(liabilities), 422))).isEqualTo("gl_account_not_postable");
        assertThat(code(rule("{\"gl_account_id\":\"%s\",\"valid_to\":\"2019-01-01\"}".formatted(cash), 422)))
                .isEqualTo("invalid_validity_range");

        HttpResponse<String> sim = send("POST", "/v1/gl/mapping-rules/simulate",
                "{\"legal_entity_id\":\"%s\",\"ledger_account_type\":\"LIABILITY\"}".formatted(le), key(), null, TENANT, MAKER);
        assertThat(sim.statusCode()).as(sim.body()).isEqualTo(200);
        assertThat(json.readTree(sim.body()).get("code").asString()).isEqualTo("2100");
        assertThat(sim.headers().firstValue("X-Mapping-Rule-Id")).isPresent();
        JsonNode future = post("/v1/gl/mapping-rules/simulate",
                "{\"legal_entity_id\":\"%s\",\"ledger_account_type\":\"LIABILITY\",\"as_of\":\"2030-06-01\"}".formatted(le),
                MAKER, 200);
        assertThat(future.get("code").asString()).isEqualTo("2200");
        JsonNode fee = post("/v1/gl/mapping-rules/simulate",
                "{\"legal_entity_id\":\"%s\",\"ledger_account_type\":\"INCOME\",\"entry_type\":\"FEE\"}".formatted(le), MAKER, 200);
        assertThat(fee.get("code").asString()).isEqualTo("4100");
        assertThat(code(post("/v1/gl/mapping-rules/simulate",
                "{\"legal_entity_id\":\"%s\",\"ledger_account_type\":\"EXPENSE\"}".formatted(le), MAKER, 422)))
                .isEqualTo("no_mapping_rule");
    }

    @Test
    @Order(4)
    void periodsAreCalendarAlignedAndNonOverlapping() throws Exception {
        jan = period("MONTH", "2026-01-01", "2026-01-31", 201).get("id").asString();
        feb = period("MONTH", "2026-02-01", "2026-02-28", 201).get("id").asString();
        assertThat(get("/v1/gl/periods/" + jan, TENANT).get("status").asString()).isEqualTo("OPEN");
        assertThat(code(period("MONTH", "2026-01-01", "2026-01-31", 409))).isEqualTo("overlapping_period");
        assertThat(code(period("MONTH", "2026-03-05", "2026-04-04", 422))).isEqualTo("invalid_period_boundaries");
        assertThat(code(post("/v1/gl/periods", """
                {"legal_entity_id":"%s","period_type":"MONTH","start_date":"2026-05-01","end_date":"2026-05-31","status":"CLOSED"}"""
                .formatted(le), MAKER, 422))).isEqualTo("invalid_initial_status");
    }

    @Test
    @Order(5)
    void ledgerPostingsAggregateIntoGlBalancesOnce() throws Exception {
        UUID je1 = UUID.randomUUID();
        LedgerPosting deposit = new LedgerPosting(je1, UUID.fromString(le), "STANDARD", LocalDate.parse("2026-01-15"),
                "Cash deposit", List.of(
                line("ASSET", "DEBIT", "1000.00"), line("LIABILITY", "CREDIT", "1000.00")));
        LedgerAggregationService.Aggregated first = asTenant(() -> aggregation.aggregate(deposit));
        assertThat(first.duplicate()).isFalse();
        LedgerAggregationService.Aggregated again = asTenant(() -> aggregation.aggregate(deposit));
        assertThat(again.duplicate()).isTrue();
        assertThat(json.readTree(again.journalJson()).get("id").asString())
                .isEqualTo(json.readTree(first.journalJson()).get("id").asString());

        // Event-driven path: ledger.journal_entry.posted payload enriched from ledger-service (mocked).
        String ledgerId = UUID.randomUUID().toString();
        String customerAcct = UUID.randomUUID().toString();
        String feeAcct = UUID.randomUUID().toString();
        when(services.get(eq("ledger-service"), eq("/v1/ledgers/" + ledgerId)))
                .thenReturn(json.readTree("{\"id\":\"%s\",\"legal_entity_id\":\"%s\"}".formatted(ledgerId, le)));
        when(services.get(eq("ledger-service"), eq("/v1/ledger-accounts/" + customerAcct)))
                .thenReturn(json.readTree("{\"id\":\"%s\",\"account_type\":\"LIABILITY\"}".formatted(customerAcct)));
        when(services.get(eq("ledger-service"), eq("/v1/ledger-accounts/" + feeAcct)))
                .thenReturn(json.readTree("{\"id\":\"%s\",\"account_type\":\"INCOME\"}".formatted(feeAcct)));
        JsonNode event = json.readTree("""
                {"id":"%s","ledger_id":"%s","entry_type":"FEE","value_date":"2026-01-20","effective_at":"2026-01-20T10:00:00Z",
                 "entries":[{"ledger_account_id":"%s","direction":"DEBIT","amount":"10.00","currency":"USD","base_amount":"10.00"},
                            {"ledger_account_id":"%s","direction":"CREDIT","amount":"10.00","currency":"USD","base_amount":"10.00"}]}"""
                .formatted(UUID.randomUUID(), ledgerId, customerAcct, feeAcct));
        assertThat(asTenant(() -> aggregation.aggregateLedgerEvent(event)).duplicate()).isFalse();

        assertBalance(cash, jan, "1000", "0", "1000");
        assertBalance(deposits, jan, "10", "1000", "-990");
        assertBalance(fees, jan, "0", "10", "-10");
        HttpResponse<String> tb = send("GET", "/v1/gl/periods/" + jan + "/trial-balance", null, null, null, TENANT, MAKER);
        assertThat(tb.headers().firstValue("X-Trial-Balance-Balanced")).contains("true");
        assertThat(json.readTree(tb.body()).get("data")).hasSize(3);
        JsonNode journals = get("/v1/gl/journals?source_journal_entry_id=" + je1, TENANT).get("data");
        assertThat(journals).hasSize(1);
        assertThat(journals.get(0).get("journal_type").asString()).isEqualTo("AUTO_AGGREGATE");
        assertThat(outboxCount("ledger.gl_journal.posted")).isEqualTo(2);
    }

    @Test
    @Order(6)
    void manualJournalNeedsBalanceAndFourEyesApproval() throws Exception {
        assertThat(code(post("/v1/gl/journals", journal("2026-01-25", jl(impairment, "DEBIT", "50.00"),
                jl(cash, "CREDIT", "49.99")), MAKER, 422))).isEqualTo("unbalanced_journal");
        assertThat(code(post("/v1/gl/journals", journal("2026-01-25", jl(expenses, "DEBIT", "50.00"),
                jl(cash, "CREDIT", "50.00")), MAKER, 422))).isEqualTo("account_not_postable");
        assertThat(code(post("/v1/gl/journals", journal("2026-02-25", jl(impairment, "DEBIT", "50.00"),
                jl(cash, "CREDIT", "50.00")), MAKER, 422))).isEqualTo("value_date_outside_period");

        JsonNode j = post("/v1/gl/journals", journal("2026-01-25", jl(impairment, "DEBIT", "50.00"),
                jl(cash, "CREDIT", "50.00")), MAKER, 201);
        String id = j.get("id").asString();
        assertThat(j.hasNonNull("approved_by")).isFalse();
        assertThat(get("/v1/gl/journals/" + id + "/lines", TENANT).get("data")).hasSize(2);
        assertBalance(cash, jan, "1000", "0", "1000");

        // The close checklist blocks while a manual journal awaits approval.
        JsonNode failedRun = post("/v1/gl/periods/" + jan + "/close", null, MAKER, 200);
        assertThat(failedRun.get("status").asString()).isEqualTo("FAILED");
        assertThat(failedRun.get("steps").get("checklist").get(0).get("status").asString()).isEqualTo("FAILED");
        assertThat(get("/v1/gl/periods/" + jan, TENANT).get("status").asString()).isEqualTo("OPEN");

        assertThat(code(post("/v1/gl/journals/" + id + "/approve", "{}", MAKER, 422))).isEqualTo("four_eyes_violation");
        HttpResponse<String> ok = send("POST", "/v1/gl/journals/" + id + "/approve", "{\"comment\":\"ok\"}", "appr-1",
                null, TENANT, CHECKER);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(json.readTree(ok.body()).get("approved_by").asString()).isEqualTo(CHECKER);
        HttpResponse<String> replay = send("POST", "/v1/gl/journals/" + id + "/approve", "{\"comment\":\"ok\"}", "appr-1",
                null, TENANT, CHECKER);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(code(post("/v1/gl/journals/" + id + "/approve", "{}", CHECKER, 409))).isEqualTo("already_approved");
        assertBalance(cash, jan, "1000", "50", "950");
        assertBalance(impairment, jan, "50", "0", "50");
    }

    @Test
    @Order(7)
    void eclIsStageBasedAndPostsProvisionJournal() throws Exception {
        String exposures = """
                [{"exposure_type":"LOAN","exposure_id":"%s","stage":"STAGE_1","ead":"10000","pd_12m":"0.02","pd_lifetime":"0.10","lgd":"0.45","currency":"USD"},
                 {"exposure_type":"LOAN","exposure_id":"%s","stage":"STAGE_2","ead":"5000","pd_12m":"0.05","pd_lifetime":"0.15","lgd":"0.40","currency":"USD"},
                 {"exposure_type":"CARD","exposure_id":"%s","stage":"STAGE_3","ead":"1000","lgd":"0.60","currency":"USD"}]"""
                .formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String body = """
                {"legal_entity_id":"%s","reporting_date":"2026-01-31","exposures":%s,
                 "provision_expense_gl_account_id":"%s","allowance_gl_account_id":"%s"}"""
                .formatted(le, exposures, impairment, allowance);
        JsonNode run = post("/v1/gl/ecl-provisions/calculate", body, MAKER, 200);
        assertThat(run.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(run.get("accounting_period_id").asString()).isEqualTo(jan);
        assertThat(new BigDecimal(run.get("steps").get("total_ecl").get("USD").asString())).isEqualByComparingTo("990.00");
        JsonNode provisions = get("/v1/gl/ecl-provisions?legal_entity_id=" + le, TENANT).get("data");
        assertThat(provisions).hasSize(3);
        provisions.forEach(p -> assertThat(p.hasNonNull("gl_journal_id")).isTrue());
        assertBalance(allowance, jan, "0", "990", "-990");
        assertThat(code(post("/v1/gl/ecl-provisions/calculate", body, MAKER, 409))).isEqualTo("already_calculated");
        assertThat(code(post("/v1/gl/ecl-provisions/calculate", """
                {"legal_entity_id":"%s","reporting_date":"2026-01-30","exposures":[{"exposure_type":"LOAN","exposure_id":"%s",
                 "stage":"STAGE_1","ead":"100","pd_12m":"1.5","lgd":"0.4","currency":"USD"}]}"""
                .formatted(le, UUID.randomUUID()), MAKER, 422))).isEqualTo("invalid_pd_12m");
        assertThat(outboxCount("ledger.ecl_provision.calculated")).isEqualTo(3);
    }

    @Test
    @Order(8)
    void periodCloseIsMakerCheckerAndFreezesPostings() throws Exception {
        JsonNode run = post("/v1/gl/periods/" + jan + "/close", null, MAKER, 200);
        assertThat(run.get("status").asString()).as(run.toString()).isEqualTo("SUCCEEDED");
        JsonNode soft = get("/v1/gl/periods/" + jan, TENANT);
        assertThat(soft.get("status").asString()).isEqualTo("SOFT_CLOSED");
        assertThat(soft.get("closed_by").asString()).isEqualTo(MAKER);
        assertThat(code(post("/v1/gl/periods/" + jan + "/close", null, MAKER, 409))).isEqualTo("invalid_transition");

        assertThat(code(post("/v1/gl/journals", journal("2026-01-26", jl(impairment, "DEBIT", "5.00"),
                jl(cash, "CREDIT", "5.00")), MAKER, 409))).isEqualTo("period_closed");
        LedgerPosting late = new LedgerPosting(UUID.randomUUID(), UUID.fromString(le), "STANDARD",
                LocalDate.parse("2026-01-28"), null, List.of(line("ASSET", "DEBIT", "1.00"), line("LIABILITY", "CREDIT", "1.00")));
        assertThatThrownBy(() -> asTenant(() -> aggregation.aggregate(late)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("period_closed"));

        assertThat(code(post("/v1/gl/periods/" + jan + "/approve-close", "{}", MAKER, 422))).isEqualTo("four_eyes_violation");
        JsonNode closed = post("/v1/gl/periods/" + jan + "/approve-close", "{\"comment\":\"reviewed\"}", CHECKER, 200);
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("approved_by").asString()).isEqualTo(CHECKER);
        assertThat(outboxCount("ledger.accounting_period.closed")).isEqualTo(1);
        assertThat(outboxCount("ledger.period_close_run.completed")).isEqualTo(3);

        // Balance-sheet balances roll into the next period's opening balance.
        asTenant(() -> aggregation.aggregate(new LedgerPosting(UUID.randomUUID(), UUID.fromString(le), "STANDARD",
                LocalDate.parse("2026-02-03"), null, List.of(line("ASSET", "DEBIT", "200.00"),
                line("LIABILITY", "CREDIT", "200.00")))));
        JsonNode febCash = get("/v1/gl/balances?gl_account_id=" + cash + "&accounting_period_id=" + feb, TENANT)
                .get("data").get(0);
        assertThat(new BigDecimal(febCash.get("opening_balance").asString())).isEqualByComparingTo("950");
        assertThat(new BigDecimal(febCash.get("closing_balance").asString())).isEqualByComparingTo("1150");
    }

    @Test
    @Order(9)
    void erpExportOfClosedPeriodWithSignedDownload() throws Exception {
        assertThat(code(post("/v1/gl/erp-exports", erp(feb), MAKER, 409))).isEqualTo("period_not_closed");
        JsonNode export = post("/v1/gl/erp-exports", erp(jan), MAKER, 201);
        assertThat(export.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(export.get("line_count").asInt()).isEqualTo(5);
        assertThat(export.get("checksum").asString()).hasSize(64);
        HttpResponse<String> dl = send("GET", "/v1/gl/erp-exports/" + export.get("id").asString() + "/download", null,
                null, null, TENANT, MAKER);
        assertThat(dl.statusCode()).isEqualTo(204);
        assertThat(dl.headers().firstValue("Location").orElseThrow()).contains("signature=").contains("expires=");
        assertThat(dl.headers().firstValue("X-Checksum-SHA256")).contains(export.get("checksum").asString());
    }

    @Test
    @Order(10)
    void reopenRequiresReason() throws Exception {
        HttpResponse<String> noReason = send("POST", "/v1/gl/periods/" + jan + "/reopen", "{}", key(), null, TENANT, CHECKER);
        assertThat(noReason.statusCode()).isEqualTo(400);
        assertThat(code(post("/v1/gl/periods/" + jan + "/reopen", "{\"reason\":\"  \"}", CHECKER, 422)))
                .isEqualTo("validation_failed");
        JsonNode reopened = post("/v1/gl/periods/" + jan + "/reopen", "{\"reason\":\"late invoice\"}", CHECKER, 200);
        assertThat(reopened.get("status").asString()).isEqualTo("REOPENED");
        assertThat(reopened.hasNonNull("approved_by")).isFalse();
        assertThat(code(post("/v1/gl/periods/" + jan + "/approve-close", "{}", CHECKER, 409))).isEqualTo("invalid_transition");
        assertThat(code(post("/v1/gl/periods/" + feb + "/reopen", "{\"reason\":\"x\"}", CHECKER, 409)))
                .isEqualTo("invalid_transition");
        post("/v1/gl/journals", journal("2026-01-26", jl(impairment, "DEBIT", "5.00"), jl(cash, "CREDIT", "5.00")), MAKER, 201);
        assertThat(outboxCount("ledger.accounting_period.reopened")).isEqualTo(1);
    }

    @Test
    @Order(11)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/gl/accounts/" + cash, null, null, null, OTHER_TENANT, MAKER).statusCode()).isEqualTo(404);
        assertThat(get("/v1/gl/periods", OTHER_TENANT).get("data")).isEmpty();
        assertThat(get("/v1/gl/balances", OTHER_TENANT).get("data")).isEmpty();
        HttpResponse<String> close = send("POST", "/v1/gl/periods/" + feb + "/close", null, key(), null, OTHER_TENANT, MAKER);
        assertThat(close.statusCode()).isEqualTo(404);
        HttpResponse<String> sim = send("POST", "/v1/gl/mapping-rules/simulate",
                "{\"ledger_account_type\":\"LIABILITY\"}", key(), null, OTHER_TENANT, MAKER);
        assertThat(sim.statusCode()).isEqualTo(422);
    }

    // ------------------------------------------------------------------ helpers

    private PostingLine line(String type, String direction, String amount) {
        BigDecimal a = new BigDecimal(amount);
        return new PostingLine(UUID.randomUUID(), type, null, null, null, direction, a, "USD", a);
    }

    private <T> T asTenant(Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "ledger-consumer", body);
    }

    private long outboxCount(String type) throws Exception {
        return asTenant(() -> jdbc.sql("select count(*) from outbox where event_type = ? and tenant_id = ?::uuid")
                .params(type, TENANT).query(Long.class).single());
    }

    private String account(String code, String name, String cls, String parent, boolean postable) throws Exception {
        return post("/v1/gl/accounts", accountBody(code, name, cls, parent, postable), MAKER, 201).get("id").asString();
    }

    private String accountBody(String code, String name, String cls, String parent, boolean postable) {
        return """
                {"legal_entity_id":"%s","code":"%s","name":"%s","account_class":"%s","is_postable":%s%s}"""
                .formatted(le, code, name, cls, postable, parent == null ? "" : ",\"parent_id\":\"" + parent + "\"");
    }

    private JsonNode rule(String fields, int expected) throws Exception {
        String body = "{\"legal_entity_id\":\"%s\",\"valid_from\":\"2020-01-01\",".formatted(le) + fields.substring(1);
        return post("/v1/gl/mapping-rules", body, MAKER, expected);
    }

    private JsonNode period(String type, String start, String end, int expected) throws Exception {
        return post("/v1/gl/periods", """
                {"legal_entity_id":"%s","period_type":"%s","start_date":"%s","end_date":"%s"}"""
                .formatted(le, type, start, end), MAKER, expected);
    }

    private String journal(String valueDate, String... lines) {
        return """
                {"legal_entity_id":"%s","accounting_period_id":"%s","journal_type":"MANUAL_ADJUSTMENT","value_date":"%s",
                 "description":"adjustment","lines":[%s]}""".formatted(le, jan, valueDate, String.join(",", lines));
    }

    private static String jl(String account, String direction, String amount) {
        return """
                {"gl_account_id":"%s","direction":"%s","amount":"%s","currency":"USD"}""".formatted(account, direction, amount);
    }

    private String erp(String period) {
        return """
                {"legal_entity_id":"%s","accounting_period_id":"%s","target_system":"SAP"}""".formatted(le, period);
    }

    private void assertBalance(String account, String period, String debits, String credits, String closing)
            throws Exception {
        JsonNode data = get("/v1/gl/balances?gl_account_id=" + account + "&accounting_period_id=" + period, TENANT)
                .get("data");
        assertThat(data).as("balance rows").hasSize(1);
        JsonNode b = data.get(0);
        assertThat(new BigDecimal(b.get("debits").asString())).as("debits").isEqualByComparingTo(debits);
        assertThat(new BigDecimal(b.get("credits").asString())).as("credits").isEqualByComparingTo(credits);
        assertThat(new BigDecimal(b.get("closing_balance").asString())).as("closing").isEqualByComparingTo(closing);
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
