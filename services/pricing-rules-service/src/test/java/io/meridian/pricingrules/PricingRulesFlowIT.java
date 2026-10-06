package io.meridian.pricingrules;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.test.MeridianTestContainers;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decision table lifecycle over HTTP against PostgreSQL 18 with RLS: draft, validation, test, maker-checker
 * publication, evaluation log, DMN import/export, price quotes, idempotency and tenant isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PricingRulesFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000c1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000c2";
    static final String MAKER = "alice";
    static final String CHECKER = "bob";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    final HttpClient http = HttpClient.newHttpClient();
    String table;
    String version;
    String evaluationId;

    static final String GRID = """
            {"decision_table_id":"%s","version_no":1,"hit_policy":"FIRST",
             "inputs":{"columns":[{"name":"credit_score","type":"number"},{"name":"segment","type":"string"}]},
             "outputs":{"columns":[{"name":"rate","type":"number","default":"0.25"},{"name":"tier","type":"string"}]},
             "rules":{"rows":[
               {"inputs":["[700..850]","-"],"outputs":["0.05","\\"PRIME\\""],"annotation":"prime"},
               {"inputs":["[600..700)","\\"RETAIL\\",\\"SME\\""],"outputs":["0.09","\\"NEAR_PRIME\\""]},
               {"inputs":["< 600","-"],"outputs":["0.18","\\"SUBPRIME\\""]}]}}""";

    @BeforeAll
    void setUp() throws Exception {
        table = post("/v1/decision-tables", """
                {"code":"LOAN_PRICING","name":"Loan pricing grid","purpose":"PRICING"}""", 201, MAKER)
                .get("id").asString();
    }

    @Test
    @Order(1)
    void invalidDefinitionIsRejectedWithFieldErrors() throws Exception {
        HttpResponse<String> r = send("POST", "/v1/decision-table-versions", """
                {"decision_table_id":"%s","version_no":1,"hit_policy":"FIRST",
                 "inputs":{"columns":[{"name":"score","type":"number"}]},
                 "outputs":{"columns":[{"name":"rate","type":"number"}]},
                 "rules":{"rows":[{"inputs":["[900..100]"],"outputs":["abc"]}]}}""".formatted(table),
                UUID.randomUUID().toString(), TENANT, MAKER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(422);
        JsonNode p = json.readTree(r.body());
        assertThat(p.get("code").asString()).isEqualTo("invalid_decision_table");
        assertThat(p.get("errors")).hasSize(2);
    }

    @Test
    @Order(2)
    void draftIsCreatedEditedAndTested() throws Exception {
        JsonNode v = post("/v1/decision-table-versions", GRID.formatted(table), 201, MAKER);
        version = v.get("id").asString();
        assertThat(v.get("status").asString()).isEqualTo("DRAFT");
        assertThat(v.get("version_no").asInt()).isEqualTo(1);

        JsonNode patched = patch("/v1/decision-table-versions/" + version, "{\"effective_from\":\"%s\"}"
                .formatted(OffsetDateTime.now().minusMinutes(5)), v.get("row_version").asInt(), 200);
        assertThat(patched.get("row_version").asInt()).isEqualTo(v.get("row_version").asInt() + 1);

        JsonNode report = post("/v1/decision-table-versions/" + version + "/test", """
                {"test_cases":{"cases":[
                  {"name":"prime","inputs":{"credit_score":720,"segment":"RETAIL"},"expected":{"rate":"0.05","tier":"PRIME"}},
                  {"name":"corporate falls to default","inputs":{"credit_score":650,"segment":"CORPORATE"},"expected":{"rate":"0.25"}},
                  {"name":"wrong expectation","inputs":{"credit_score":500,"segment":"SME"},"expected":{"rate":"0.10"}}]}}""",
                200, MAKER);
        assertThat(report.get("total").asInt()).isEqualTo(3);
        assertThat(report.get("passed").asInt()).isEqualTo(2);
        assertThat(report.get("results").get(2).get("mismatches").get(0).get("output").asString()).isEqualTo("rate");
    }

    @Test
    @Order(3)
    void evaluationRequiresPublishedVersion() throws Exception {
        HttpResponse<String> r = send("POST", "/v1/decision-tables/" + table + "/evaluate",
                "{\"inputs\":{\"credit_score\":720}}", UUID.randomUUID().toString(), TENANT, MAKER);
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(json.readTree(r.body()).get("code").asString()).isEqualTo("no_published_version");
    }

    @Test
    @Order(4)
    void makerCheckerPublication() throws Exception {
        // approve before submit is an invalid transition
        HttpResponse<String> early = send("POST", "/v1/decision-table-versions/" + version + "/approve", "{}",
                UUID.randomUUID().toString(), TENANT, CHECKER);
        assertThat(early.statusCode()).isEqualTo(409);

        JsonNode submitted = post("/v1/decision-table-versions/" + version + "/submit", "{\"comment\":\"ready\"}", 200, MAKER);
        assertThat(submitted.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(submitted.get("checksum").asString()).hasSize(64);
        assertThat(get("/v1/decision-tables/" + table, TENANT).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        // pending versions are locked
        HttpResponse<String> edit = send("PATCH", "/v1/decision-table-versions/" + version, "{\"hit_policy\":\"UNIQUE\"}",
                null, TENANT, MAKER, submitted.get("row_version").asString());
        assertThat(edit.statusCode()).isEqualTo(409);

        // four eyes
        HttpResponse<String> self = send("POST", "/v1/decision-table-versions/" + version + "/approve", "{}",
                UUID.randomUUID().toString(), TENANT, MAKER);
        assertThat(self.statusCode()).isEqualTo(422);
        assertThat(json.readTree(self.body()).get("code").asString()).isEqualTo("four_eyes_violation");

        JsonNode approved = post("/v1/decision-table-versions/" + version + "/approve", "{\"comment\":\"ok\"}", 200, CHECKER);
        assertThat(approved.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(approved.get("approved_by").asString()).isEqualTo(CHECKER);
        JsonNode t = get("/v1/decision-tables/" + table, TENANT);
        assertThat(t.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(t.get("current_version_id").asString()).isEqualTo(version);
        assertThat(outboxCount("product.decision_table_version.published")).isEqualTo(1);
    }

    @Test
    @Order(5)
    void evaluationIsLoggedAndIdempotent() throws Exception {
        String key = "eval-" + UUID.randomUUID();
        String body = """
                {"inputs":{"credit_score":720,"segment":"RETAIL"},"subject_type":"LOAN_APPLICATION","subject_id":"%s"}"""
                .formatted(UUID.randomUUID());
        HttpResponse<String> first = send("POST", "/v1/decision-tables/" + table + "/evaluate", body, key, TENANT, MAKER);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        JsonNode log = json.readTree(first.body());
        evaluationId = log.get("id").asString();
        assertThat(new BigDecimal(log.get("outputs").get("rate").asString())).isEqualByComparingTo("0.05");
        assertThat(log.get("matched_rules").get("selected").get(0).asInt()).isEqualTo(1);
        assertThat(log.get("decision_table_version_id").asString()).isEqualTo(version);

        HttpResponse<String> replay = send("POST", "/v1/decision-tables/" + table + "/evaluate", body, key, TENANT, MAKER);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(evaluationId);
        assertThat(get("/v1/rule-evaluations?decision_table_id=" + table, TENANT).get("data")).hasSize(1);

        HttpResponse<String> bad = send("POST", "/v1/decision-tables/" + table + "/evaluate",
                "{\"inputs\":{\"credit_score\":\"high\"}}", UUID.randomUUID().toString(), TENANT, MAKER);
        assertThat(bad.statusCode()).isEqualTo(422);
        assertThat(json.readTree(bad.body()).get("code").asString()).isEqualTo("invalid_input");
    }

    @Test
    @Order(6)
    void newVersionSupersedesAndRejectReturnsToDraft() throws Exception {
        JsonNode v2 = post("/v1/decision-table-versions", GRID.formatted(table).replace("0.05", "0.045"), 201, MAKER);
        assertThat(v2.get("version_no").asInt()).isEqualTo(2);
        String v2id = v2.get("id").asString();
        post("/v1/decision-table-versions/" + v2id + "/submit", "{}", 200, MAKER);
        HttpResponse<String> noReason = send("POST", "/v1/decision-table-versions/" + v2id + "/reject", "{}",
                UUID.randomUUID().toString(), TENANT, CHECKER);
        assertThat(noReason.statusCode()).isIn(400, 422);
        JsonNode rejected = post("/v1/decision-table-versions/" + v2id + "/reject", "{\"reason\":\"rate too low\"}", 200, CHECKER);
        assertThat(rejected.get("status").asString()).isEqualTo("DRAFT");
        post("/v1/decision-table-versions/" + v2id + "/submit", "{}", 200, MAKER);
        post("/v1/decision-table-versions/" + v2id + "/approve", "{}", 200, CHECKER);
        assertThat(get("/v1/decision-table-versions/" + version, TENANT).get("status").asString()).isEqualTo("RETIRED");
        assertThat(get("/v1/decision-tables/" + table + "/versions", TENANT).get("data")).hasSize(2);

        JsonNode log = post("/v1/decision-tables/" + table + "/evaluate", "{\"inputs\":{\"credit_score\":800}}", 200, MAKER);
        assertThat(new BigDecimal(log.get("outputs").get("rate").asString())).isEqualByComparingTo("0.045");
    }

    @Test
    @Order(7)
    void dmnExportAndImport() throws Exception {
        HttpResponse<String> export = send("GET", "/v1/decision-table-versions/" + version + "/export-dmn", null, null, TENANT, MAKER);
        assertThat(export.statusCode()).isEqualTo(200);
        assertThat(export.body()).contains("<decisionTable").contains("[700..850]");
        String importBody = json.writeValueAsString(java.util.Map.of("code", "LOAN_PRICING_COPY", "dmn_xml", export.body()));
        JsonNode imported = post("/v1/decision-tables/import-dmn", importBody, 200, MAKER);
        assertThat(imported.get("code").asString()).isEqualTo("LOAN_PRICING_COPY");
        assertThat(imported.get("status").asString()).isEqualTo("DRAFT");
        JsonNode versions = get("/v1/decision-tables/" + imported.get("id").asString() + "/versions", TENANT).get("data");
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).get("rules").get("rows")).hasSize(3);

        HttpResponse<String> bad = send("POST", "/v1/decision-tables/import-dmn",
                "{\"code\":\"X\",\"dmn_xml\":\"<not-dmn>\"}", UUID.randomUUID().toString(), TENANT, MAKER);
        assertThat(bad.statusCode()).isEqualTo(422);
    }

    @Test
    @Order(8)
    void priceQuoteIssueAndAcceptOnce() throws Exception {
        String quoteBody = """
                {"product_id":"%s","product_version_id":"%s","amount":"10000.00","currency":"USD","term_months":24,
                 "interest_rate":"0.05","apr":"0.0525","fees":{"origination":"100.00"},"total_cost":"629.81",
                 "decision_log_id":"%s","expires_at":"%s"}"""
                .formatted(UUID.randomUUID(), UUID.randomUUID(), evaluationId, OffsetDateTime.now().plusDays(7));
        JsonNode quote = post("/v1/price-quotes", quoteBody, 201, MAKER);
        String id = quote.get("id").asString();
        JsonNode accepted = post("/v1/price-quotes/" + id + "/accept", null, 200, MAKER);
        assertThat(accepted.get("accepted_at").isNull()).isFalse();
        HttpResponse<String> again = send("POST", "/v1/price-quotes/" + id + "/accept", null,
                UUID.randomUUID().toString(), TENANT, MAKER);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(json.readTree(again.body()).get("code").asString()).isEqualTo("quote_already_accepted");
        assertThat(outboxCount("product.product_price_quote.accepted")).isEqualTo(1);

        HttpResponse<String> expired = send("POST", "/v1/price-quotes", quoteBody.replace(
                quoteBody.substring(quoteBody.indexOf("\"expires_at\":\"") + 14, quoteBody.lastIndexOf('"')),
                OffsetDateTime.now().minusDays(1).toString()), UUID.randomUUID().toString(), TENANT, MAKER);
        assertThat(expired.statusCode()).isEqualTo(422);
    }

    @Test
    @Order(9)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/decision-tables/" + table, null, null, OTHER_TENANT, MAKER).statusCode()).isEqualTo(404);
        assertThat(get("/v1/rule-evaluations", OTHER_TENANT).get("data")).isEmpty();
        HttpResponse<String> eval = send("POST", "/v1/decision-tables/" + table + "/evaluate",
                "{\"inputs\":{\"credit_score\":700}}", UUID.randomUUID().toString(), OTHER_TENANT, MAKER);
        assertThat(eval.statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    private long outboxCount(String type) {
        return jdbc.sql("select count(*) from outbox where event_type = ?").param(type).query(Long.class).single();
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), TENANT, user);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode patch(String path, String body, int rowVersion, int expected) throws Exception {
        HttpResponse<String> r = send("PATCH", path, body, null, TENANT, MAKER, String.valueOf(rowVersion));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant, MAKER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant, String user)
            throws Exception {
        return send(method, path, body, idemKey, tenant, user, null);
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant, String user,
                                      String ifMatch) throws Exception {
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
