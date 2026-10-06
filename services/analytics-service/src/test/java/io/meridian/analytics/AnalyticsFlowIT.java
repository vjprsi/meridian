package io.meridian.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * End-to-end over HTTP against PostgreSQL 18 with row-level security: datasets served by loan-service and
 * payment-service (ServiceClient mocked), saved queries in the query DSL with owner / visibility rules and typed
 * parameters, governed KPIs with certification, dashboards and widget data with global filters, signed embed tokens,
 * asynchronous exports, data shares with secret destinations and the per-tenant delivery job.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnalyticsFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000f1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000f2";
    static final String ALICE = "alice";
    static final String BOB = "bob";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient services;

    final HttpClient http = HttpClient.newHttpClient();
    String portfolioQuery;
    String kpi;
    String dashboard;
    String barWidget;

    @BeforeEach
    void upstream() throws Exception {
        when(services.get(eq("loan-service"), startsWith("/v1/loans?limit="))).thenReturn(json.readTree("""
                {"data":[
                 {"id":"l1","status":"ACTIVE","currency":"NGN","product_id":"p1","outstanding_principal":"1000.00","days_past_due":0,
                  "start_date":"2026-01-10","borrower_name":"secret person"},
                 {"id":"l2","status":"ACTIVE","currency":"NGN","product_id":"p1","outstanding_principal":"500.00","days_past_due":45,
                  "start_date":"2026-01-20"},
                 {"id":"l3","status":"ARREARS","currency":"NGN","product_id":"p2","outstanding_principal":"250.00","days_past_due":95,
                  "start_date":"2026-02-05"},
                 {"id":"l4","status":"ACTIVE","currency":"USD","product_id":"p2","outstanding_principal":"4000.00","days_past_due":0,
                  "start_date":"2026-02-15"},
                 {"id":"l5","status":"CLOSED","currency":"NGN","product_id":"p3","outstanding_principal":"0","days_past_due":0,
                  "start_date":"2025-12-01"}],"has_more":false,"next_cursor":null}"""));
        when(services.get(eq("payment-service"), startsWith("/v1/payments")))
                .thenThrow(new ApiException(502, "upstream_error", "payment-service is not reachable"));
    }

    @Test
    @Order(1)
    void datasetsAndValidation() throws Exception {
        JsonNode ds = get("/v1/analytics/saved-queries/datasets", TENANT, ALICE).get("datasets");
        assertThat(ds.size()).isEqualTo(5);
        JsonNode ok = post("/v1/analytics/saved-queries/validate",
                "{\"query\":\"SELECT status, COUNT(*) AS n FROM loans.loans WHERE days_past_due > :dpd GROUP BY status\"}", ALICE, 200);
        assertThat(ok.get("valid").asBoolean()).isTrue();
        assertThat(ok.get("dataset").asString()).isEqualTo("loans.loans");
        assertThat(ok.get("parameters").get(0).asString()).isEqualTo("dpd");
        JsonNode bad = post("/v1/analytics/saved-queries/validate",
                "{\"query\":\"SELECT borrower_name FROM loans.loans; DROP TABLE analytics_kpi\"}", ALICE, 200);
        assertThat(bad.get("valid").asBoolean()).isFalse();
        assertThat(bad.get("errors").get(0).asString()).contains("separators");
    }

    @Test
    @Order(2)
    void savedQueriesRunWithTypedParametersAndPrivacy() throws Exception {
        assertThat(code(post("/v1/analytics/saved-queries", queryBody("SELECT borrower_name FROM loans.loans", "[]", "PRIVATE"),
                ALICE, 422))).isEqualTo("invalid_query");
        assertThat(code(post("/v1/analytics/saved-queries", queryBody(PORTFOLIO, "[]", "PRIVATE"), ALICE, 422)))
                .isEqualTo("validation_failed");
        JsonNode q = post("/v1/analytics/saved-queries", queryBody(PORTFOLIO,
                "[{\"name\":\"min_dpd\",\"type\":\"NUMBER\",\"default\":0}]", "PRIVATE"), ALICE, 201);
        portfolioQuery = q.get("id").asString();
        assertThat(q.get("dataset").asString()).isEqualTo("loans.loans");
        assertThat(q.get("owner").asString()).isEqualTo(ALICE);

        JsonNode run = post("/v1/analytics/saved-queries/" + portfolioQuery + "/run?limit=10", "{}", ALICE, 200);
        assertThat(run.get("columns").toString()).isEqualTo("[\"status\",\"loans\",\"outstanding\"]");
        assertThat(run.get("rows").get(0).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(new BigDecimal(run.get("rows").get(0).get("outstanding").asString())).isEqualByComparingTo("5500.00");
        assertThat(run.get("row_count").asInt()).isEqualTo(2);
        assertThat(run.get("rows").get(1).get("status").asString()).isEqualTo("ARREARS");
        JsonNode filtered = post("/v1/analytics/saved-queries/" + portfolioQuery + "/run",
                "{\"parameters\":{\"min_dpd\":30}}", ALICE, 200);
        assertThat(filtered.get("row_count").asInt()).isEqualTo(2);
        assertThat(new BigDecimal(filtered.get("rows").get(0).get("outstanding").asString())).isEqualByComparingTo("500.00");
        assertThat(code(post("/v1/analytics/saved-queries/" + portfolioQuery + "/run", "{\"parameters\":{\"min_dpd\":\"abc\"}}",
                ALICE, 422))).isEqualTo("validation_failed");
        assertThat(code(post("/v1/analytics/saved-queries/" + portfolioQuery + "/run", "{\"parameters\":{\"region\":\"NG\"}}",
                ALICE, 422))).isEqualTo("validation_failed");
        assertThat(get("/v1/analytics/saved-queries/" + portfolioQuery, TENANT, ALICE).hasNonNull("last_run_at")).isTrue();

        // PRIVATE: invisible to others, listed for the owner only.
        assertThat(send("GET", "/v1/analytics/saved-queries/" + portfolioQuery, null, null, null, TENANT, BOB).statusCode()).isEqualTo(404);
        assertThat(get("/v1/analytics/saved-queries", TENANT, BOB).get("data")).isEmpty();
        assertThat(get("/v1/analytics/saved-queries", TENANT, ALICE).get("data")).hasSize(1);
        JsonNode cur = get("/v1/analytics/saved-queries/" + portfolioQuery, TENANT, ALICE);
        HttpResponse<String> share = send("PATCH", "/v1/analytics/saved-queries/" + portfolioQuery, "{\"visibility\":\"TENANT\"}",
                null, "\"" + cur.get("row_version").asInt() + "\"", TENANT, ALICE);
        assertThat(share.statusCode()).as(share.body()).isEqualTo(200);
        assertThat(get("/v1/analytics/saved-queries", TENANT, BOB).get("data")).hasSize(1);
        HttpResponse<String> hijack = send("PATCH", "/v1/analytics/saved-queries/" + portfolioQuery, "{\"name\":\"mine\"}",
                null, "\"2\"", TENANT, BOB);
        assertThat(hijack.statusCode()).isEqualTo(403);
        assertThat(post("/v1/analytics/saved-queries/" + portfolioQuery + "/run", "{}", BOB, 200).get("row_count").asInt()).isEqualTo(2);
    }

    @Test
    @Order(3)
    void kpisAreEvaluatedAndCertifiedUnderFourEyes() throws Exception {
        assertThat(code(post("/v1/analytics/kpis", kpiBody("{\"aggregate\":\"SUM\",\"value\":\"salary\"}"), ALICE, 422)))
                .isEqualTo("invalid_metric");
        JsonNode k = post("/v1/analytics/kpis", kpiBody(PAR30), ALICE, 201);
        kpi = k.get("id").asString();
        assertThat(k.get("is_certified").asBoolean()).isFalse();

        JsonNode v = post("/v1/analytics/kpis/" + kpi + "/evaluate", "{}", ALICE, 200);
        // (500 + 250) / (1000 + 500 + 250 + 4000) * 100 over open loans
        assertThat(new BigDecimal(v.get("value").asString())).isEqualByComparingTo("13.04");
        assertThat(v.get("source").asString()).isEqualTo("DATASET");
        JsonNode provided = post("/v1/analytics/kpis/" + kpi + "/evaluate", """
                {"rows":[{"status":"ACTIVE","outstanding_principal":"100","days_past_due":31},
                         {"status":"ACTIVE","outstanding_principal":"300","days_past_due":0}],
                 "filters":[{"field":"status","op":"=","value":"ACTIVE"}]}""", ALICE, 200);
        assertThat(new BigDecimal(provided.get("value").asString())).isEqualByComparingTo("25.00");
        assertThat(provided.get("source").asString()).isEqualTo("PROVIDED");

        JsonNode series = get("/v1/analytics/kpis/" + kpi + "/values?from=2026-01-01&to=2026-03-31&granularity=MONTH", TENANT, ALICE);
        assertThat(series.get("points")).hasSize(3);
        assertThat(series.get("points").get(0).get("period_start").asString()).isEqualTo("2026-01-01");
        assertThat(new BigDecimal(series.get("points").get(0).get("value").asString())).isEqualByComparingTo("33.33");
        assertThat(series.get("points").get(2).hasNonNull("value")).isFalse();
        assertThat(send("GET", "/v1/analytics/kpis/" + kpi + "/values?from=2026-03-01&to=2026-01-01", null, null, null, TENANT, ALICE)
                .statusCode()).isEqualTo(422);

        assertThat(code(post("/v1/analytics/kpis/" + kpi + "/certify", "{}", ALICE, 422))).isEqualTo("four_eyes_violation");
        JsonNode cert = post("/v1/analytics/kpis/" + kpi + "/certify", "{\"comment\":\"governance ok\"}", BOB, 200);
        assertThat(cert.get("is_certified").asBoolean()).isTrue();
        assertThat(cert.get("certified_by").asString()).isEqualTo(BOB);
        assertThat(code(post("/v1/analytics/kpis/" + kpi + "/certify", "{}", BOB, 409))).isEqualTo("already_certified");
        assertThat(outboxCount("reporting.analytics_kpi.certified")).isEqualTo(1);
        // Changing the definition clears the certification; cosmetic changes do not.
        HttpResponse<String> cosmetic = send("PATCH", "/v1/analytics/kpis/" + kpi, "{\"description\":\"Portfolio at risk 30+\"}",
                null, "\"" + cert.get("row_version").asInt() + "\"", TENANT, ALICE);
        assertThat(json.readTree(cosmetic.body()).get("is_certified").asBoolean()).isTrue();
        HttpResponse<String> changed = send("PATCH", "/v1/analytics/kpis/" + kpi,
                "{\"metric\":" + PAR30.replace("days_past_due > 30", "days_past_due > 60") + "}",
                null, "\"" + json.readTree(cosmetic.body()).get("row_version").asInt() + "\"", TENANT, ALICE);
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(json.readTree(changed.body()).get("is_certified").asBoolean()).isFalse();
    }

    @Test
    @Order(4)
    void dashboardsWidgetsAndEmbedTokens() throws Exception {
        JsonNode d = post("/v1/analytics/dashboards", """
                {"name":"Portfolio","visibility":"TENANT","tags":["lending"],
                 "filters":[{"field":"currency","op":"=","value":"NGN"}]}""", ALICE, 201);
        dashboard = d.get("id").asString();
        assertThat(d.get("owner").asString()).isEqualTo(ALICE);
        assertThat(code(post("/v1/analytics/dashboards", "{\"name\":\"x\",\"filters\":[{\"field\":\"a\",\"op\":\"LIKE\"}]}", ALICE, 422)))
                .isEqualTo("validation_failed");

        assertThat(code(post("/v1/analytics/widgets", widgetBody("BAR", portfolioQuery, kpi), ALICE, 422))).isEqualTo("validation_failed");
        assertThat(code(post("/v1/analytics/widgets", widgetBody("BAR", portfolioQuery, null), BOB, 403))).isEqualTo("not_owner");
        barWidget = post("/v1/analytics/widgets", widgetBody("BAR", portfolioQuery, null), ALICE, 201).get("id").asString();
        post("/v1/analytics/widgets", widgetBody("KPI", null, kpi), ALICE, 201);
        post("/v1/analytics/widgets", """
                {"analytics_dashboard_id":"%s","title":"Note","widget_type":"TEXT","config":{"text":"Figures in NGN"},
                 "position":{"x":0,"y":4,"w":12,"h":1}}""".formatted(dashboard), ALICE, 201);
        assertThat(get("/v1/analytics/dashboards/" + dashboard + "/widgets", TENANT, BOB).get("data")).hasSize(3);

        JsonNode data = get("/v1/analytics/widgets/" + barWidget + "/data", TENANT, BOB);
        // Dashboard filter currency = NGN removes the USD loan: ACTIVE = 1000 + 500.
        assertThat(data.get("series").get(0).get("name").asString()).isEqualTo("loans");
        assertThat(data.get("series").get(1).get("name").asString()).isEqualTo("outstanding");
        JsonNode first = data.get("series").get(1).get("points").get(0);
        assertThat(first.get("x").asString()).isEqualTo("ACTIVE");
        assertThat(new BigDecimal(first.get("y").asString())).isEqualByComparingTo("1500.00");

        JsonNode copy = post("/v1/analytics/dashboards/" + dashboard + "/duplicate", "{\"name\":\"My portfolio\"}", BOB, 201);
        assertThat(copy.get("owner").asString()).isEqualTo(BOB);
        assertThat(copy.get("visibility").asString()).isEqualTo("PRIVATE");
        assertThat(get("/v1/analytics/dashboards/" + copy.get("id").asString() + "/widgets", TENANT, BOB).get("data")).hasSize(3);
        assertThat(send("GET", "/v1/analytics/dashboards/" + copy.get("id").asString(), null, null, null, TENANT, ALICE)
                .statusCode()).isEqualTo(404);

        JsonNode token = get("/v1/analytics/dashboards/" + dashboard + "/embed-token?ttl_seconds=120", TENANT, ALICE);
        assertThat(token.get("token").asString()).contains(".");
        JsonNode verified = post("/v1/analytics/dashboards/embed-tokens/verify",
                "{\"token\":\"" + token.get("token").asString() + "\"}", ALICE, 200);
        assertThat(verified.get("valid").asBoolean()).isTrue();
        assertThat(verified.get("dashboard_id").asString()).isEqualTo(dashboard);
        String tampered = token.get("token").asString().substring(0, token.get("token").asString().length() - 2) + "xx";
        assertThat(post("/v1/analytics/dashboards/embed-tokens/verify", "{\"token\":\"" + tampered + "\"}", ALICE, 200)
                .get("valid").asBoolean()).isFalse();
        HttpResponse<String> otherTenant = send("POST", "/v1/analytics/dashboards/embed-tokens/verify",
                "{\"token\":\"" + token.get("token").asString() + "\"}", key(), null, OTHER_TENANT, ALICE);
        assertThat(json.readTree(otherTenant.body()).get("reason").asString()).isEqualTo("wrong_tenant");
        assertThat(send("GET", "/v1/analytics/dashboards/" + dashboard + "/embed-token?ttl_seconds=5", null, null, null, TENANT, ALICE)
                .statusCode()).isEqualTo(422);
    }

    @Test
    @Order(5)
    void exportsRunAsynchronously() throws Exception {
        assertThat(code(post("/v1/analytics/saved-queries/" + portfolioQuery + "/export", "{\"format\":\"PARQUET\"}", ALICE, 422)))
                .isEqualTo("validation_failed");
        JsonNode job = post("/v1/analytics/saved-queries/" + portfolioQuery + "/export",
                "{\"format\":\"CSV\",\"parameters\":{\"min_dpd\":0}}", ALICE, 201);
        assertThat(job.get("status").asString()).isIn("QUEUED", "RUNNING", "SUCCEEDED");
        String id = job.get("id").asString();
        JsonNode done = awaitExport(id);
        assertThat(done.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.get("row_count").asInt()).isEqualTo(2);
        assertThat(done.hasNonNull("expires_at")).isTrue();
        assertThat(done.has("content")).isFalse();
        HttpResponse<String> csv = send("GET", "/v1/analytics/exports/" + id + "/csv", null, null, null, TENANT, ALICE);
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(csv.body()).startsWith("status,loans,outstanding\r\nACTIVE,3,5500");
        assertThat(csv.headers().firstValue("X-Content-SHA256")).contains(done.get("sha256").asString());
        assertThat(send("GET", "/v1/analytics/exports/" + id + "/json", null, null, null, TENANT, ALICE).statusCode()).isEqualTo(404);
        assertThat(code(post("/v1/analytics/exports/" + id + "/cancel", "{}", ALICE, 409))).isEqualTo("invalid_transition");

        JsonNode jsonJob = post("/v1/analytics/exports", "{\"analytics_saved_query_id\":\"%s\",\"format\":\"JSON\"}"
                .formatted(portfolioQuery), BOB, 201);
        JsonNode jsonDone = awaitExport(jsonJob.get("id").asString());
        assertThat(jsonDone.get("requested_by").asString()).isEqualTo(BOB);
        HttpResponse<String> body = send("GET", "/v1/analytics/exports/" + jsonJob.get("id").asString() + "/json", null, null, null,
                TENANT, BOB);
        assertThat(json.readTree(body.body()).size()).isEqualTo(2);
        assertThat(outboxCount("reporting.analytics_export.completed")).isEqualTo(2);
        assertThat(post("/v1/analytics/exports/process-queued", null, ALICE, 200).get("processed").asInt()).isZero();
    }

    @Test
    @Order(6)
    void dataSharesKeepSecretsAndDeliverOncePerBusinessDate() throws Exception {
        JsonNode invalid = post("/v1/analytics/data-shares", shareBody("S3", "{\"bucket\":\"lake\"}", "[\"loans.loans\"]"), ALICE, 422);
        assertThat(code(invalid)).isEqualTo("invalid_target_config");
        assertThat(code(post("/v1/analytics/data-shares", shareBody("SFTP",
                "{\"host\":\"10.1.2.3\",\"port\":22,\"path\":\"/in\",\"username\":\"u\",\"credentials_ref\":\"vault:sftp\"}",
                "[\"loans.loans\"]"), ALICE, 422))).isEqualTo("invalid_target_config");
        assertThat(code(post("/v1/analytics/data-shares", shareBody("S3", S3, "[\"secret.table\"]"), ALICE, 422)))
                .isEqualTo("validation_failed");
        JsonNode share = post("/v1/analytics/data-shares", shareBody("S3", S3, "[\"loans.loans\"]"), ALICE, 201);
        String id = share.get("id").asString();
        assertThat(share.has("target_config")).isFalse();
        assertThat(share.get("target_summary").asString()).contains("meridian-lake").doesNotContain("SECRET");
        assertThat(get("/v1/analytics/data-shares/" + id, TENANT, ALICE).toString()).doesNotContain("SECRET");
        JsonNode test = post("/v1/analytics/data-shares/" + id + "/test", null, ALICE, 200);
        assertThat(test.get("ok").asBoolean()).isTrue();

        assertThat(post("/v1/analytics/data-shares/" + id + "/pause", "{\"reason\":\"bucket migration\"}", ALICE, 200)
                .get("status").asString()).isEqualTo("PAUSED");
        assertThat(code(post("/v1/analytics/data-shares/" + id + "/pause", "{}", ALICE, 409))).isEqualTo("invalid_transition");
        assertThat(post("/v1/analytics/data-shares/" + id + "/resume", null, ALICE, 200).get("status").asString()).isEqualTo("ACTIVE");

        String failing = post("/v1/analytics/data-shares", shareBody("SNOWFLAKE",
                "{\"account\":\"acme\",\"database\":\"BANK\",\"schema\":\"RAW\",\"user\":\"svc\",\"credentials_ref\":\"vault:sf\"}",
                "[\"payments.payments\"]"), ALICE, 201).get("id").asString();
        // Friday 6 March 2026: both are due (no cron = every business day).
        JsonNode job = post("/v1/analytics/data-shares/deliver?business_date=2026-03-06", null, ALICE, 200);
        assertThat(job.get("counts").get("due").asInt()).isEqualTo(2);
        assertThat(job.get("counts").get("delivered").asInt()).isEqualTo(1);
        assertThat(job.get("status").asString()).isEqualTo("COMPLETED_WITH_ERRORS");
        JsonNode delivered = get("/v1/analytics/data-shares/" + id, TENANT, ALICE);
        assertThat(delivered.get("last_delivery_rows").asInt()).isEqualTo(5);
        assertThat(get("/v1/analytics/data-shares/" + failing, TENANT, ALICE).get("status").asString()).isEqualTo("FAILED");
        assertThat(outboxCount("reporting.data_share.failed")).isEqualTo(1);
        assertThat(outboxCount("reporting.data_share.delivered")).isEqualTo(1);
        String payload = asTenant(() -> jdbc.sql("select payload::text from outbox where event_type = 'reporting.data_share.delivered'")
                .query(String.class).single());
        assertThat(payload).doesNotContain("SECRET").doesNotContain("target_config");

        // Weekend: nothing due; a COMPLETED date is replayed, not re-delivered.
        JsonNode sunday = post("/v1/analytics/data-shares/deliver?business_date=2026-03-08", null, ALICE, 200);
        assertThat(sunday.get("counts").get("due").asInt()).isZero();
        assertThat(post("/v1/analytics/data-shares/deliver?business_date=2026-03-08", null, ALICE, 200).get("replayed").asBoolean())
                .isTrue();
        assertThat(get("/v1/analytics/job-runs?job=DATA_SHARE_DELIVERY", TENANT, ALICE).get("data")).hasSize(2);
    }

    @Test
    @Order(7)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/analytics/saved-queries/" + portfolioQuery, null, null, null, OTHER_TENANT, ALICE).statusCode())
                .isEqualTo(404);
        assertThat(get("/v1/analytics/dashboards", OTHER_TENANT, ALICE).get("data")).isEmpty();
        assertThat(get("/v1/analytics/kpis", OTHER_TENANT, ALICE).get("data")).isEmpty();
        assertThat(send("GET", "/v1/analytics/widgets/" + barWidget + "/data", null, null, null, OTHER_TENANT, ALICE).statusCode())
                .isEqualTo(404);
        HttpResponse<String> replay = send("POST", "/v1/analytics/kpis/" + kpi + "/certify", "{}", key(), null, OTHER_TENANT, BOB);
        assertThat(replay.statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ fixtures

    static final String PORTFOLIO = "SELECT status, COUNT(*) AS loans, SUM(outstanding_principal) AS outstanding "
            + "FROM loans.loans WHERE status <> 'CLOSED' AND days_past_due >= :min_dpd GROUP BY status ORDER BY outstanding DESC";

    static final String PAR30 = """
            {"aggregate":"RATIO","multiplier":100,"scale":2,"filter":"status IN ('ACTIVE', 'ARREARS')",
             "numerator":{"aggregate":"SUM","value":"outstanding_principal","filter":"days_past_due > 30"},
             "denominator":{"aggregate":"SUM","value":"outstanding_principal"}}""";

    static final String S3 = """
            {"bucket":"meridian-lake","region":"eu-west-1","role_arn":"arn:aws:iam::123:role/share","prefix":"exports/",
             "secret_access_key":"SECRET-DO-NOT-LEAK"}""";

    private String queryBody(String query, String params, String visibility) {
        return "{\"name\":\"Portfolio by status\",\"query\":\"%s\",\"parameters\":%s,\"visibility\":\"%s\"}"
                .formatted(query, params, visibility);
    }

    private String kpiBody(String metric) {
        return "{\"code\":\"PAR30_%s\",\"name\":\"Portfolio at risk 30\",\"dataset\":\"loans.loans\",\"unit\":\"PERCENT\",\"metric\":%s}"
                .formatted(UUID.randomUUID().toString().substring(0, 8), metric);
    }

    private String widgetBody(String type, String query, String kpiId) {
        return """
                {"analytics_dashboard_id":"%s","title":"%s","widget_type":"%s"%s%s,"config":{"x":"status"},
                 "position":{"x":0,"y":0,"w":6,"h":4}}""".formatted(dashboard, type, type,
                query == null ? "" : ",\"analytics_saved_query_id\":\"" + query + "\"",
                kpiId == null ? "" : ",\"analytics_kpi_id\":\"" + kpiId + "\"");
    }

    private String shareBody(String target, String config, String datasets) {
        return "{\"name\":\"Lake %s\",\"target\":\"%s\",\"target_config\":%s,\"datasets\":%s}".formatted(target, target, config, datasets);
    }

    private JsonNode awaitExport(String id) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        JsonNode j = get("/v1/analytics/exports/" + id, TENANT, ALICE);
        while (Thread.currentThread().isAlive() && System.currentTimeMillis() < deadline
                && java.util.Set.of("QUEUED", "RUNNING").contains(j.get("status").asString())) {
            Thread.onSpinWait();
            java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
            j = get("/v1/analytics/exports/" + id, TENANT, ALICE);
        }
        return j;
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

    private JsonNode get(String path, String tenant, String user) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, null, tenant, user);
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

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
