package io.meridian.alm;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.Callable;
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
 * alm-service end to end over HTTP against PostgreSQL 18 (RLS enforced): behavioural assumptions with maker-checker,
 * repricing gap runs, FTP curves / rules / assignments, NII and EVE sensitivity with the Basel standard shocks,
 * idempotent replays and tenant isolation. alm-service calls no other service; ServiceClient is mocked to prove it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AlmFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000a1a1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-00000000a1a2";
    static final String ENTITY = "0199a000-0000-7000-8000-00000000e001";
    static final String MORTGAGE = "0199a000-0000-7000-8000-00000000f001";
    static final String LOAN_PRODUCT = "0199a000-0000-7000-8000-00000000f002";

    static final String POSITIONS = """
            [{"code":"MORT","product_id":"%s","segment":"RETAIL","side":"ASSET","amount":"1000000","currency":"EUR",
              "rate":"0.04","rate_type":"FIXED","maturity_date":"2028-01-01"},
             {"code":"FRN","side":"ASSET","amount":"500000","currency":"EUR","rate":"0.03","rate_type":"FLOATING",
              "repricing_date":"2026-03-15","maturity_date":"2030-01-01","payment_frequency_months":3},
             {"code":"SAV","segment":"RETAIL","side":"LIABILITY","amount":"800000","currency":"EUR","rate":"0.01",
              "rate_type":"FLOATING","non_maturity":true},
             {"code":"USD-LOAN","side":"ASSET","amount":"999","currency":"USD","maturity_date":"2027-01-01"}]"""
            .formatted(MORTGAGE);

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    String decayId;
    String baseCurve;

    // ------------------------------------------------------------------ 1. behavioural assumptions

    @Test
    @Order(1)
    void assumptionsAreValidatedApprovedWithFourEyesAndFrozen() throws Exception {
        assertThat(post("/v1/treasury/alm/assumptions", assumption("PREPAYMENT", "{\"cpr\":1.4}", MORTGAGE, null), "modeller", 422)
                .get("code").asString()).isEqualTo("invalid_parameters");
        assertThat(post("/v1/treasury/alm/assumptions",
                assumption("DEPOSIT_DECAY", "{\"core_ratio\":0.6,\"speed\":1}", null, "RETAIL"), "modeller", 422)
                .get("detail").asString()).contains("unknown parameter speed").contains("decay_rate is required");

        JsonNode cpr = post("/v1/treasury/alm/assumptions", assumption("PREPAYMENT", "{\"cpr\":0.10}", MORTGAGE, null),
                "modeller", 201);
        JsonNode decay = post("/v1/treasury/alm/assumptions",
                assumption("DEPOSIT_DECAY", "{\"core_ratio\":0.6,\"decay_rate\":0.2,\"pass_through\":0.5}", null, "RETAIL"),
                "modeller", 201);
        decayId = decay.get("id").asString();
        assertThat(decay.get("parameters").get("core_max_months").asInt()).isEqualTo(60);
        assertThat(decay.hasNonNull("approved_by")).isFalse();

        assertThat(post("/v1/treasury/alm/assumptions/" + decayId + "/approve", "{}", "modeller", 422)
                .get("code").asString()).isEqualTo("four_eyes_violation");
        HttpResponse<String> approved = send("POST", "/v1/treasury/alm/assumptions/" + decayId + "/approve",
                "{\"comment\":\"validated by MRM\"}", "approve-decay", "risk", TENANT);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(json.readTree(approved.body()).get("approved_by").asString()).isEqualTo("risk");
        HttpResponse<String> replay = send("POST", "/v1/treasury/alm/assumptions/" + decayId + "/approve",
                "{\"comment\":\"validated by MRM\"}", "approve-decay", "risk", TENANT);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(post("/v1/treasury/alm/assumptions/" + decayId + "/approve", "{}", "risk", 409)
                .get("code").asString()).isEqualTo("already_approved");
        post("/v1/treasury/alm/assumptions/" + cpr.get("id").asString() + "/approve", "{}", "risk", 200);

        HttpResponse<String> frozen = send("PATCH", "/v1/treasury/alm/assumptions/" + decayId,
                "{\"parameters\":{\"core_ratio\":0.9,\"decay_rate\":0.1}}", null, "modeller", TENANT);
        assertThat(frozen.statusCode()).isEqualTo(409);
        assertThat(json.readTree(frozen.body()).get("code").asString()).isEqualTo("assumption_approved");
        HttpResponse<String> retire = sendIfMatch("PATCH", "/v1/treasury/alm/assumptions/" + decayId,
                "{\"valid_to\":\"2030-12-31\"}", get("/v1/treasury/alm/assumptions/" + decayId).get("row_version").asString());
        assertThat(retire.statusCode()).as(retire.body()).isEqualTo(200);
        assertThat(events("treasury.alm_assumption.approved")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 2. repricing gap

    @Test
    @Order(2)
    void gapRunsContractualBehaviouralAndFailed() throws Exception {
        JsonNode contractual = post("/v1/treasury/alm/gap-runs", gapRun(false, POSITIONS), "alm", 201);
        assertThat(contractual.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(dec(contractual, "total_assets")).isEqualByComparingTo("1500000");
        assertThat(dec(contractual, "total_liabilities")).isEqualByComparingTo("800000");
        assertThat(contractual.get("finished_at").isNull()).isFalse();
        JsonNode buckets = get("/v1/treasury/alm/gap-runs/" + contractual.get("id").asString() + "/buckets").get("data");
        assertThat(buckets).hasSize(7);
        JsonNode first = bucket(buckets, "0-1M");
        assertThat(dec(first, "rate_sensitive_liabilities")).isEqualByComparingTo("800000");
        assertThat(dec(first, "gap")).isEqualByComparingTo("-800000");
        assertThat(dec(bucket(buckets, "1-3M"), "rate_sensitive_assets")).isEqualByComparingTo("500000");
        assertThat(dec(bucket(buckets, "1-2Y"), "rate_sensitive_assets")).isEqualByComparingTo("1000000");
        assertThat(dec(bucket(buckets, ">5Y"), "cumulative_gap")).isEqualByComparingTo("700000");

        JsonNode behavioural = post("/v1/treasury/alm/gap-runs", gapRun(true, POSITIONS), "alm", 201);
        assertThat(behavioural.get("assumptions_used")).hasSize(2);
        JsonNode bb = get("/v1/treasury/alm/gap-runs/" + behavioural.get("id").asString() + "/buckets").get("data");
        assertThat(dec(bucket(bb, "0-1M"), "rate_sensitive_liabilities")).isLessThan(new BigDecimal("800000"))
                .isGreaterThan(new BigDecimal("320000"));
        assertThat(dec(bucket(bb, "1-2Y"), "rate_sensitive_assets")).isLessThan(new BigDecimal("1000000"));

        String broken = "[{\"code\":\"ODD\",\"side\":\"ASSET\",\"amount\":\"10\",\"currency\":\"EUR\"}]";
        JsonNode failed = post("/v1/treasury/alm/gap-runs", gapRun(false, broken), "alm", 201);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("error_message").asString()).contains("ODD");
        assertThat(get("/v1/treasury/alm/gap-runs/" + failed.get("id").asString() + "/buckets").get("data")).isEmpty();

        assertThat(post("/v1/treasury/alm/gap-runs", gapRun(false, "[{\"amount\":\"10\"}]"), "alm", 422)
                .get("code").asString()).isEqualTo("invalid_position");
        assertThat(post("/v1/treasury/alm/gap-runs", gapRun(false, "[]"), "alm", 422).get("code").asString())
                .isEqualTo("positions_required");
        HttpResponse<String> once = send("POST", "/v1/treasury/alm/gap-runs", gapRun(false, POSITIONS), "gap-1", "alm", TENANT);
        HttpResponse<String> twice = send("POST", "/v1/treasury/alm/gap-runs", gapRun(false, POSITIONS), "gap-1", "alm", TENANT);
        assertThat(twice.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(twice.body()).get("id").asString()).isEqualTo(json.readTree(once.body()).get("id").asString());
        assertThat(events("treasury.alm_gap_run.completed")).isEqualTo(3);
        assertThat(events("treasury.alm_gap_run.failed")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 3. FTP curves

    @Test
    @Order(3)
    void ftpCurvesArePublishedWithFourEyesAndFrozen() throws Exception {
        baseCurve = curve("BASE-EUR", "BASE", new String[][] {{"30", "1M", "0.03"}, {"365", "1Y", "0.035"}, {"1825", "5Y", "0.04"}});
        String liq = curve("LIQ-EUR", "LIQUIDITY_PREMIUM", new String[][] {{"30", "1M", "0.001"}, {"365", "1Y", "0.004"}});
        assertThat(post("/v1/treasury/ftp/curve-points", point(baseCurve, "-1", "X", "0.01"), "alm", 422)
                .get("code").asString()).isEqualTo("invalid_tenor");
        assertThat(post("/v1/treasury/ftp/curve-points", point(baseCurve, "30", "1M", "0.02"), "alm", 409)
                .get("code").asString()).isEqualTo("duplicate");

        JsonNode rate = get("/v1/treasury/ftp/curves/" + baseCurve + "/rate?tenor_days=197");
        assertThat(dec(rate, "rate")).isEqualByComparingTo("0.0324925373");
        assertThat(rate.get("method").asString()).isEqualTo("LINEAR");
        assertThat(rate.get("lower").get("tenor_days").asInt()).isEqualTo(30);
        assertThat(get("/v1/treasury/ftp/curves/" + baseCurve + "/rate?tenor_days=4000").get("method").asString())
                .isEqualTo("FLAT_EXTRAPOLATION");
        HttpResponse<String> missing = send("GET", "/v1/treasury/ftp/curves/" + baseCurve + "/rate", null, null, "alm", TENANT);
        assertThat(missing.statusCode()).isEqualTo(400);

        assertThat(post("/v1/treasury/ftp/curves/" + baseCurve + "/publish", "{}", "alm", 422).get("code").asString())
                .isEqualTo("four_eyes_violation");
        JsonNode published = post("/v1/treasury/ftp/curves/" + baseCurve + "/publish", "{\"comment\":\"ALCO 12/2025\"}", "alco", 200);
        assertThat(published.get("is_published").asBoolean()).isTrue();
        assertThat(published.get("approved_by").asString()).isEqualTo("alco");
        post("/v1/treasury/ftp/curves/" + liq + "/publish", "{}", "alco", 200);
        assertThat(post("/v1/treasury/ftp/curves/" + baseCurve + "/publish", "{}", "alco", 409).get("code").asString())
                .isEqualTo("already_published");
        assertThat(post("/v1/treasury/ftp/curve-points", point(baseCurve, "3650", "10Y", "0.045"), "alm", 409)
                .get("code").asString()).isEqualTo("curve_published");
        String pointId = get("/v1/treasury/ftp/curves/" + baseCurve + "/points").get("data").get(0).get("id").asString();
        assertThat(send("DELETE", "/v1/treasury/ftp/curve-points/" + pointId, null, null, "alm", TENANT).statusCode())
                .isEqualTo(409);
        assertThat(sendIfMatch("PATCH", "/v1/treasury/ftp/curves/" + baseCurve, "{\"source\":\"other\"}", "1").statusCode())
                .isEqualTo(409);
        JsonNode empty = post("/v1/treasury/ftp/curves", """
                {"code":"EMPTY","currency":"EUR","curve_type":"BASE","effective_date":"2025-06-30"}""", "alm", 201);
        assertThat(post("/v1/treasury/ftp/curves/" + empty.get("id").asString() + "/publish", "{}", "alco", 422)
                .get("code").asString()).isEqualTo("curve_has_no_points");
        assertThat(events("treasury.ftp_curve.published")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 4. NII / EVE sensitivity

    @Test
    @Order(4)
    void sensitivityRunsAndStandardShocks() throws Exception {
        String book = """
                [{"code":"MORT","side":"ASSET","amount":"10000000","currency":"EUR","rate":"0.04","rate_type":"FIXED",
                  "maturity_date":"2036-01-01"},
                 {"code":"SAV","segment":"RETAIL","side":"LIABILITY","amount":"8000000","currency":"EUR","rate":"0.01",
                  "rate_type":"FLOATING","non_maturity":true}]""";
        JsonNode eve = post("/v1/treasury/alm/sensitivity-runs", sensitivity("EVE", "PARALLEL_UP", book, "1000000", ""), "alm", 201);
        assertThat(eve.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(eve.hasNonNull("shock_bps")).isFalse();
        assertThat(dec(eve, "delta")).isNegative();
        assertThat(dec(eve, "delta").abs()).isGreaterThan(new BigDecimal("150000"));
        assertThat(eve.get("outlier").asBoolean()).isTrue();
        assertThat(eve.get("results").get("positions")).hasSize(2);
        assertThat(events("treasury.alm_sensitivity.outlier_breached")).isEqualTo(1);

        JsonNode nii = post("/v1/treasury/alm/sensitivity-runs", sensitivity("NII", "PARALLEL_UP", book, null, ""), "alm", 201);
        // base 400,000 - 80,000; savings reprice at once with 50% pass-through (approved DEPOSIT_DECAY): -8,000,000 * 2% * 0.5
        assertThat(dec(nii, "base_value")).isEqualByComparingTo("320000");
        assertThat(dec(nii, "delta")).isEqualByComparingTo("-80000");
        assertThat(nii.get("outlier").asBoolean()).isFalse();

        assertThat(post("/v1/treasury/alm/sensitivity-runs", sensitivity("EVE", "CUSTOM", book, null, ""), "alm", 422)
                .get("code").asString()).isEqualTo("custom_shock_required");
        JsonNode custom = post("/v1/treasury/alm/sensitivity-runs", sensitivity("EVE", "CUSTOM", book, null,
                ",\"custom_shock\":[{\"tenor_days\":365,\"bps\":100},{\"tenor_days\":3650,\"bps\":300}]"), "alm", 201);
        assertThat(dec(custom, "delta")).isNegative();
        assertThat(post("/v1/treasury/alm/sensitivity-runs",
                sensitivity("EVE", "PARALLEL_UP", book, null, "").replace("\"currency\":\"EUR\",\"measure\"",
                        "\"currency\":\"USD\",\"measure\""), "alm", 422).get("code").asString()).isEqualTo("curve_required");

        JsonNode std = post("/v1/treasury/alm/sensitivity-runs/standard-shocks", """
                {"legal_entity_id":"%s","as_of_date":"2026-01-01","currency":"EUR","positions":%s,"tier1_capital":"50000000"}"""
                .formatted(ENTITY, book), "alm", 200);
        assertThat(std.get("runs")).hasSize(12);
        assertThat(std.get("outlier").asBoolean()).isFalse();
        assertThat(dec(std, "worst_eve_delta")).isNegative();
        assertThat(get("/v1/treasury/alm/sensitivity-runs?measure=EVE&shock_type=STEEPENER").get("data")).hasSize(1);
        assertThat(events("treasury.alm_sensitivity.completed")).isEqualTo(15);
    }

    // ------------------------------------------------------------------ 5. FTP rules and assignments

    @Test
    @Order(5)
    void ftpRulesAssignmentsAndQuotes() throws Exception {
        String spreadRule = """
                {"product_id":"%s","method":"FIXED_SPREAD","base_curve_code":"BASE-EUR","priority":50,"valid_from":"2025-01-01"}"""
                .formatted(LOAN_PRODUCT);
        assertThat(post("/v1/treasury/ftp/rules", spreadRule, "alm", 422).get("code").asString())
                .isEqualTo("fixed_spread_required");
        post("/v1/treasury/ftp/rules", spreadRule.replace("\"priority\"", "\"fixed_spread\":\"0.01\",\"priority\""), "alm", 201);
        post("/v1/treasury/ftp/rules", """
                {"currency":"EUR","method":"MATCHED_MATURITY","base_curve_code":"BASE-EUR","liquidity_curve_code":"LIQ-EUR",
                 "valid_from":"2025-01-01"}""", "alm", 201);

        String loanId = UUID.randomUUID().toString();
        String depositId = UUID.randomUUID().toString();
        String run = """
                {"period_start":"2026-01-01","period_end":"2026-01-31","exposures":[
                  {"exposure_type":"LOAN","exposure_id":"%s","loan_id":"%s","product_id":"%s","currency":"EUR",
                   "average_balance":"100000","customer_rate":"0.07","term_days":365},
                  {"exposure_type":"DEPOSIT_ACCOUNT","exposure_id":"%s","currency":"EUR","average_balance":"50000",
                   "customer_rate":"0.01","term_days":30},
                  {"exposure_type":"LOAN","exposure_id":"%s","currency":"USD","average_balance":"1000",
                   "customer_rate":"0.05","term_days":90}]}""".formatted(loanId, loanId, LOAN_PRODUCT, depositId,
                UUID.randomUUID());
        JsonNode result = post("/v1/treasury/ftp/assignments/run", run, "alm", 200);
        assertThat(result.get("computed").asInt()).isEqualTo(2);
        assertThat(result.get("failed").get(0).get("code").asString()).isEqualTo("no_ftp_rule");
        assertThat(dec(result, "total_ftp_charges")).isEqualByComparingTo("382.19");
        assertThat(dec(result, "total_ftp_credits")).isEqualByComparingTo("131.64");
        JsonNode loan = get("/v1/treasury/ftp/assignments?exposure_id=" + loanId).get("data").get(0);
        assertThat(dec(loan, "ftp_rate")).isEqualByComparingTo("0.045");
        assertThat(dec(loan, "net_margin")).isEqualByComparingTo("0.025");
        assertThat(loan.get("term_days").asInt()).isEqualTo(365);
        JsonNode deposit = get("/v1/treasury/ftp/assignments?exposure_id=" + depositId).get("data").get(0);
        assertThat(dec(deposit, "ftp_rate")).isEqualByComparingTo("0.031");
        assertThat(dec(deposit, "net_margin")).isEqualByComparingTo("0.021");

        JsonNode again = post("/v1/treasury/ftp/assignments/run", run, "alm", 200);
        assertThat(again.get("computed").asInt()).isZero();
        assertThat(again.get("existing").asInt()).isEqualTo(2);
        assertThat(post("/v1/treasury/ftp/assignments/run", run.replace("\"2026-01-31\"", "\"2025-12-31\""), "alm", 422)
                .get("code").asString()).isEqualTo("invalid_period");
        assertThat(post("/v1/treasury/ftp/assignments/run",
                run.replace("\"DEPOSIT_ACCOUNT\"", "\"SAFE\""), "alm", 422).get("code").asString()).isEqualTo("invalid_exposure");

        JsonNode quote = post("/v1/treasury/ftp/assignments/quote", """
                {"exposure_type":"LOAN","product_id":"%s","currency":"EUR","term_days":365,"customer_rate":"0.065",
                 "as_of_date":"2026-02-01","amount":"1000000"}""".formatted(LOAN_PRODUCT), "pricing", 200);
        assertThat(dec(quote, "ftp_rate")).isEqualByComparingTo("0.045");
        assertThat(dec(quote, "net_margin")).isEqualByComparingTo("0.02");
        assertThat(quote.get("method").asString()).isEqualTo("FIXED_SPREAD");
        assertThat(dec(quote, "annual_ftp_amount")).isEqualByComparingTo("45000");
        assertThat(post("/v1/treasury/ftp/assignments/quote", """
                {"exposure_type":"LOAN","currency":"EUR","term_days":365,"as_of_date":"2025-06-01"}""", "pricing", 422)
                .get("code").asString()).isEqualTo("curve_not_found");
        assertThat(events("treasury.ftp_assignment.computed")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 6. tenant isolation

    @Test
    @Order(6)
    void otherTenantsSeeNothing() throws Exception {
        assertThat(send("GET", "/v1/treasury/alm/assumptions/" + decayId, null, null, "x", OTHER_TENANT).statusCode())
                .isEqualTo(404);
        HttpResponse<String> list = send("GET", "/v1/treasury/ftp/curves", null, null, "x", OTHER_TENANT);
        assertThat(json.readTree(list.body()).get("data")).isEmpty();
        HttpResponse<String> publish = send("POST", "/v1/treasury/ftp/curves/" + baseCurve + "/publish", "{}",
                UUID.randomUUID().toString(), "x", OTHER_TENANT);
        assertThat(publish.statusCode()).isEqualTo(404);
        HttpResponse<String> sens = send("POST", "/v1/treasury/alm/sensitivity-runs",
                sensitivity("EVE", "PARALLEL_UP", POSITIONS.replace("\"USD\"", "\"EUR\""), null, ""),
                UUID.randomUUID().toString(), "x", OTHER_TENANT);
        assertThat(sens.statusCode()).as(sens.body()).isEqualTo(422);
        assertThat(json.readTree(sens.body()).get("code").asString()).isEqualTo("curve_required");
        assertThat(send("GET", "/v1/treasury/alm/assumptions", null, null, "x", null).statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ helpers

    private String curve(String code, String type, String[][] points) throws Exception {
        String id = post("/v1/treasury/ftp/curves", """
                {"code":"%s","currency":"EUR","curve_type":"%s","effective_date":"2025-12-31","source":"OIS bootstrap"}"""
                .formatted(code, type), "alm", 201).get("id").asString();
        for (String[] p : points) {
            post("/v1/treasury/ftp/curve-points", point(id, p[0], p[1], p[2]), "alm", 201);
        }
        return id;
    }

    private static String point(String curve, String days, String label, String rate) {
        return "{\"ftp_curve_id\":\"%s\",\"tenor_days\":%s,\"tenor_label\":\"%s\",\"rate\":\"%s\"}".formatted(curve, days, label, rate);
    }

    private static String assumption(String type, String params, String product, String segment) {
        return "{\"assumption_type\":\"%s\",\"parameters\":%s,%s%s\"valid_from\":\"2025-01-01\",\"model_version\":\"v1\"}"
                .formatted(type, params, product == null ? "" : "\"product_id\":\"" + product + "\",",
                        segment == null ? "" : "\"segment\":\"" + segment + "\",");
    }

    private static String gapRun(boolean behavioural, String positions) {
        return "{\"legal_entity_id\":\"%s\",\"as_of_date\":\"2026-01-01\",\"currency\":\"EUR\",\"behavioural\":%s,\"positions\":%s}"
                .formatted(ENTITY, behavioural, positions);
    }

    private static String sensitivity(String measure, String shock, String positions, String tier1, String extra) {
        return ("{\"legal_entity_id\":\"%s\",\"as_of_date\":\"2026-01-01\",\"currency\":\"EUR\",\"measure\":\"%s\","
                + "\"shock_type\":\"%s\",\"positions\":%s%s%s}").formatted(ENTITY, measure, shock, positions,
                tier1 == null ? "" : ",\"tier1_capital\":\"" + tier1 + "\"", extra);
    }

    private static JsonNode bucket(JsonNode buckets, String code) {
        for (JsonNode b : buckets) {
            if (code.equals(b.get("bucket_code").asString())) {
                return b;
            }
        }
        throw new AssertionError("no bucket " + code);
    }

    private <T> T asTenant(Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", body);
    }

    private int events(String type) throws Exception {
        return asTenant(() -> jdbc.sql("select count(*) from outbox where tenant_id = ?::uuid and event_type = ?")
                .params(TENANT, type).query(Integer.class).single());
    }

    private static BigDecimal dec(JsonNode n, String field) {
        return new BigDecimal(n.get(field).asString());
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), user, TENANT);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, "alm", TENANT);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> sendIfMatch(String method, String path, String body, String version) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "modeller").header("Content-Type", "application/json")
                .header("If-Match", "\"" + version + "\"");
        b.method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String user, String tenant)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-User", user).header("Content-Type", "application/json");
        if (tenant != null) {
            b.header("X-Tenant-Id", tenant);
        }
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        if ("PATCH".equals(method)) {
            b.header("If-Match", "\"1\"");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
