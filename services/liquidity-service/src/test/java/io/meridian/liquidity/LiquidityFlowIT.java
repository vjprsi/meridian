package io.meridian.liquidity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * liquidity-service end to end over HTTP against PostgreSQL 18 (RLS enforced). treasury-deal-service (projected deal
 * flows) and payment-service (scheduled payments) are stubbed behind a mocked {@link ServiceClient}; the end-of-day
 * snapshot is called over HTTP the way business-day-service calls it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LiquidityFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000011a1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000011a2";
    static final String ENTITY = "0199a000-0000-7000-8000-0000000011e1";
    static final LocalDate FROM = LocalDate.of(2026, 11, 2);

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    volatile boolean treasuryDown;
    String lcrId;
    String scenarioId;

    @BeforeEach
    void stubServiceClient() {
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> {
            String service = i.getArgument(0);
            String path = i.getArgument(1);
            calls.add(service + " " + path);
            if ("treasury-deal-service".equals(service)) {
                if (treasuryDown) {
                    throw new ApiException(502, "upstream_error", "treasury-deal-service is not reachable");
                }
                return json.readTree("""
                        {"from":"%s","flows":[
                          {"value_date":"%s","currency":"EUR","direction":"OUT","amount":"300.00","category":"TREASURY_DEALS",
                           "deal_type":"MONEY_MARKET","flow_type":"PRINCIPAL_OUT"},
                          {"value_date":"%s","currency":"USD","direction":"OUT","amount":"4000.00","category":"TREASURY_DEALS",
                           "deal_type":"FX","flow_type":"FX_PAY"},
                          {"value_date":"%s","currency":"USD","direction":"IN","amount":"2500.00","category":"SECURITIES",
                           "deal_type":"SECURITY","flow_type":"SECURITY_CASH_RECEIVE"}]}"""
                        .formatted(FROM, FROM, FROM.plusDays(1), FROM.plusDays(60)));
            }
            if ("payment-service".equals(service)) {
                if (path.contains("requested_execution_date=" + FROM)) {
                    return json.readTree("""
                            {"data":[{"id":"p1","direction":"INBOUND","amount":"100.00","currency":"EUR","status":"INITIATED"},
                                     {"id":"p2","direction":"OUTBOUND","amount":"50.00","currency":"EUR","status":"SETTLED"},
                                     {"id":"p3","direction":"INTERNAL","amount":"75.00","currency":"EUR","status":"APPROVED"}],
                             "has_more":false,"next_cursor":null}""");
                }
                return json.readTree("{\"data\":[],\"has_more\":false,\"next_cursor\":null}");
            }
            throw new ApiException(404, "not_found", path);
        });
    }

    // ------------------------------------------------------------------ cash positions

    @Test
    @Order(1)
    void recordedPositionsComputeClosingAndShortfall() throws Exception {
        JsonNode eur = post("/v1/treasury/liquidity/cash-positions", position("EUR", "ACTUAL", FROM.minusDays(1), "900.00",
                "300.00", "200.00", "500.00"), "ops", 201);
        assertThat(dec(eur, "closing_balance")).isEqualByComparingTo("1000.00");
        assertThat(eur.hasNonNull("shortfall")).isFalse();

        JsonNode low = post("/v1/treasury/liquidity/cash-positions", position("GBP", "FORECAST", FROM, "100.00",
                "0", "50.00", "200.00"), "ops", 201);
        assertThat(dec(low, "shortfall")).isEqualByComparingTo("150.00");
        assertThat(events("treasury.liquidity_position.shortfall", low.get("id").asString())).isEqualTo(1);

        assertThat(post("/v1/treasury/liquidity/cash-positions", position("EUR", "ACTUAL", FROM.minusDays(1), "1", "0",
                "0", null), "ops", 409).get("code").asString()).isEqualTo("position_exists");
        assertThat(post("/v1/treasury/liquidity/cash-positions", position("CHF", "PROJECTED", FROM, "1", "0", "0", null),
                "ops", 422).get("code").asString()).isEqualTo("projected_positions_are_computed");
        assertThat(post("/v1/treasury/liquidity/cash-positions", position("CHF", "ACTUAL", FROM, "1", "-5", "0", null),
                "ops", 422).get("code").asString()).isEqualTo("invalid_flows");
        HttpResponse<String> noKey = send("POST", "/v1/treasury/liquidity/cash-positions",
                position("CHF", "ACTUAL", FROM, "1", "0", "0", null), null, "ops", TENANT);
        assertThat(noKey.statusCode()).isEqualTo(400);
    }

    @Test
    @Order(2)
    void projectionRollsForwardFromDealAndPaymentFlows() throws Exception {
        String body = """
                {"legal_entity_id":"%s","from_date":"%s","days":3,
                 "opening_balances":[{"currency":"USD","amount":"5000.00","minimum_buffer":"2000.00"}]}"""
                .formatted(ENTITY, FROM);
        HttpResponse<String> r = send("POST", "/v1/treasury/liquidity/cash-positions/project", body, "project-1", "ops", TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode summary = json.readTree(r.body());
        assertThat(summary.get("positions")).hasSize(6);
        assertThat(summary.get("shortfalls").asInt()).isEqualTo(2);
        assertThat(summary.get("warnings")).isEmpty();
        assertThat(calls).anyMatch(c -> c.startsWith("treasury-deal-service /v1/treasury/settlements/projected-flows?from="
                + FROM + "&to=" + FROM.plusDays(2) + "&legal_entity_id=" + ENTITY));
        assertThat(calls).anyMatch(c -> c.startsWith("payment-service /v1/payments?requested_execution_date=" + FROM));

        JsonNode eurDay1 = position(summary, "EUR", FROM);
        assertThat(dec(eurDay1, "opening_balance")).isEqualByComparingTo("1000.00");   // previous ACTUAL closing
        assertThat(dec(eurDay1, "expected_inflows")).isEqualByComparingTo("100.00");   // settled / internal payments skipped
        assertThat(dec(eurDay1, "expected_outflows")).isEqualByComparingTo("300.00");
        assertThat(dec(eurDay1, "closing_balance")).isEqualByComparingTo("800.00");
        assertThat(dec(eurDay1, "minimum_buffer")).isEqualByComparingTo("500.00");     // carried from the previous day
        assertThat(eurDay1.get("flows_by_category").get("PAYMENTS").get("inflows").asString()).isEqualTo("100.00");
        JsonNode usdDay2 = position(summary, "USD", FROM.plusDays(1));
        assertThat(dec(usdDay2, "opening_balance")).isEqualByComparingTo("5000.00");
        assertThat(dec(usdDay2, "closing_balance")).isEqualByComparingTo("1000.00");
        assertThat(dec(usdDay2, "shortfall")).isEqualByComparingTo("1000.00");

        HttpResponse<String> replay = send("POST", "/v1/treasury/liquidity/cash-positions/project", body, "project-1",
                "ops", TENANT);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        post("/v1/treasury/liquidity/cash-positions/project", body, "ops", 200);   // recompute replaces, not duplicates
        JsonNode projected = get("/v1/treasury/liquidity/cash-positions?position_type=PROJECTED&legal_entity_id=" + ENTITY);
        assertThat(projected.get("data")).hasSize(6);
        assertThat(events("treasury.liquidity_position.projected", null)).isEqualTo(12);

        assertThat(post("/v1/treasury/liquidity/cash-positions/project", body.replace("\"days\":3", "\"days\":120"),
                "ops", 422).get("code").asString()).isEqualTo("invalid_days");
        treasuryDown = true;
        JsonNode degraded = post("/v1/treasury/liquidity/cash-positions/project", """
                {"legal_entity_id":"%s","from_date":"%s","days":1,"opening_balances":[{"currency":"JPY","amount":"1"}]}"""
                .formatted(ENTITY, FROM.plusDays(30)), "ops", 200);
        treasuryDown = false;
        assertThat(degraded.get("warnings").get(0).asString()).contains("treasury-deal-service");
    }

    @Test
    @Order(3)
    void endOfDaySnapshotIsIdempotentPerBusinessDate() throws Exception {
        HttpResponse<String> r = send("POST", "/v1/treasury/liquidity/eod-runs/position-snapshot?business_date=" + FROM,
                null, "eod-1", "system:end-of-day", TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode run = json.readTree(r.body());
        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(run.get("replayed").asBoolean()).isFalse();
        assertThat(run.get("result").get("counts").get("actual_positions").asInt()).isEqualTo(2);

        JsonNode actual = get("/v1/treasury/liquidity/cash-positions?position_type=ACTUAL&position_date=" + FROM
                + "&currency=EUR");
        assertThat(actual.get("data")).hasSize(1);
        assertThat(dec(actual.get("data").get(0), "opening_balance")).isEqualByComparingTo("1000.00");
        assertThat(dec(actual.get("data").get(0), "closing_balance")).isEqualByComparingTo("800.00");

        JsonNode again = post("/v1/treasury/liquidity/eod-runs/position-snapshot?business_date=" + FROM, null,
                "system:end-of-day", 200);
        assertThat(again.get("replayed").asBoolean()).isTrue();
        assertThat(again.get("run_id").asString()).isEqualTo(run.get("run_id").asString());
        assertThat(get("/v1/treasury/liquidity/eod-runs?job=LIQUIDITY_POSITION_SNAPSHOT").get("data")).hasSize(1);
        assertThat(events("treasury.liquidity_eod_run.completed", null)).isEqualTo(1);
        HttpResponse<String> missing = send("POST", "/v1/treasury/liquidity/eod-runs/position-snapshot", null, "eod-x",
                "ops", TENANT);
        assertThat(missing.statusCode()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ maturity ladder

    @Test
    @Order(4)
    void ladderRunsBucketItemsAndTreasuryFlows() throws Exception {
        JsonNode run = post("/v1/treasury/liquidity/ladders", """
                {"legal_entity_id":"%s","as_of_date":"%s","currency":"EUR","items":[
                  {"category":"LOANS","direction":"IN","amount":"500","currency":"EUR","maturity_date":"%s"},
                  {"category":"TREASURY_DEALS","direction":"OUT","amount":"300","currency":"EUR","days":5},
                  {"category":"DEPOSITS","direction":"OUT","amount":"1000","currency":"EUR"},
                  {"category":"LOANS","direction":"IN","amount":"999","currency":"USD","days":1}]}"""
                .formatted(ENTITY, FROM, FROM.plusDays(45)), "alm", 201);
        assertThat(run.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(dec(run, "total_inflows")).isEqualByComparingTo("500");
        assertThat(dec(run, "total_outflows")).isEqualByComparingTo("1300");
        assertThat(run.get("negative_gap_bucket").asString()).isEqualTo("O/N");
        JsonNode buckets = get("/v1/treasury/liquidity/ladders/" + run.get("id").asString() + "/buckets");
        assertThat(buckets.get("data")).hasSize(8);
        assertThat(buckets.get("data").get(0).get("bucket_code").asString()).isEqualTo("O/N");
        assertThat(dec(buckets.get("data").get(1), "outflows")).isEqualByComparingTo("350");
        assertThat(dec(buckets.get("data").get(7), "cumulative_gap")).isEqualByComparingTo("-800");
        assertThat(buckets.get("data").get(7).hasNonNull("end_day")).isFalse();
        JsonNode page = get("/v1/treasury/liquidity/ladders/" + run.get("id").asString() + "/buckets?limit=3");
        assertThat(page.get("has_more").asBoolean()).isTrue();
        JsonNode next = get("/v1/treasury/liquidity/ladders/" + run.get("id").asString() + "/buckets?limit=3&cursor="
                + page.get("next_cursor").asString());
        assertThat(next.get("data").get(0).get("bucket_code").asString()).isEqualTo("1-3M");

        JsonNode fromDeals = post("/v1/treasury/liquidity/ladders", """
                {"legal_entity_id":"%s","as_of_date":"%s"}""".formatted(ENTITY, FROM), "alm", 201);
        assertThat(fromDeals.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(dec(fromDeals, "total_inflows")).isEqualByComparingTo("2500");
        assertThat(dec(fromDeals, "total_outflows")).isEqualByComparingTo("4300");
        assertThat(events("treasury.liquidity_ladder.completed", null)).isEqualTo(2);

        treasuryDown = true;
        JsonNode failed = post("/v1/treasury/liquidity/ladders", """
                {"legal_entity_id":"%s","as_of_date":"%s"}""".formatted(ENTITY, FROM.plusDays(1)), "alm", 201);
        treasuryDown = false;
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("error_message").asString()).contains("treasury flows unavailable");

        assertThat(post("/v1/treasury/liquidity/ladders", """
                {"legal_entity_id":"%s","as_of_date":"%s","items":[{"category":"DEPOSITS","direction":"OUT","amount":"10",
                 "behavioural_profile":[{"bucket_code":"O/N","share":"0.5"}]}]}""".formatted(ENTITY, FROM), "alm", 422)
                .get("code").asString()).isEqualTo("invalid_behavioural_profile");
        assertThat(post("/v1/treasury/liquidity/ladders", """
                {"legal_entity_id":"%s","as_of_date":"%s","items":[{"direction":"SIDEWAYS","amount":"10"}]}"""
                .formatted(ENTITY, FROM), "alm", 422).get("code").asString()).isEqualTo("invalid_direction");
    }

    // ------------------------------------------------------------------ LCR / NSFR

    @Test
    @Order(5)
    void lcrCalculateRecalculateAndFourEyesApproval() throws Exception {
        String body = """
                {"legal_entity_id":"%s","as_of_date":"%s","currency":"EUR","positions":[
                  {"line":"govies","code":"L1","category":"HQLA","amount":"100"},
                  {"line":"covered","code":"L2A","amount":"100"},
                  {"line":"rmbs","code":"L2B","amount":"100"},
                  {"line":"retail","code":"RETAIL_STABLE","category":"OUTFLOW","amount":"1000"},
                  {"line":"interbank","code":"FINANCIAL_INSTITUTION","amount":"100"},
                  {"line":"loans","code":"CORPORATE_LOANS","amount":"400"}]}""".formatted(ENTITY, FROM);
        JsonNode lcr = post("/v1/treasury/liquidity/lcr/calculate", body, "risk-maker", 200);
        lcrId = lcr.get("id").asString();
        assertThat(dec(lcr, "total_hqla")).isEqualByComparingTo("166.67");
        assertThat(dec(lcr, "cap_adjustment")).isEqualByComparingTo("68.33");
        assertThat(dec(lcr, "inflows_30d")).isEqualByComparingTo("112.50");
        assertThat(dec(lcr, "uncapped_inflows")).isEqualByComparingTo("200");
        assertThat(dec(lcr, "lcr_ratio")).isEqualByComparingTo("4.4445333333");
        assertThat(lcr.get("breach").asBoolean()).isFalse();
        assertThat(lcr.get("status").asString()).isEqualTo("CALCULATED");
        assertThat(lcr.get("components")).hasSize(6);
        assertThat(lcr.get("components").get(3).get("factor").asString()).isEqualTo("0.05");

        // recalculation with a breaching mix replaces the same snapshot
        JsonNode breached = post("/v1/treasury/liquidity/lcr/calculate", """
                {"legal_entity_id":"%s","as_of_date":"%s","currency":"EUR","regulatory_minimum":"1.0","positions":[
                  {"code":"L1","amount":"80"},{"code":"NON_OPERATIONAL_CORPORATE","amount":"200","factor":"0.5"}]}"""
                .formatted(ENTITY, FROM), "risk-maker", 200);
        assertThat(breached.get("id").asString()).isEqualTo(lcrId);
        assertThat(dec(breached, "lcr_ratio")).isEqualByComparingTo("0.8");
        assertThat(breached.get("breach").asBoolean()).isTrue();
        assertThat(events("treasury.lcr.breached", lcrId)).isEqualTo(1);
        assertThat(events("treasury.lcr.calculated", lcrId)).isEqualTo(2);

        assertThat(post("/v1/treasury/liquidity/lcr/" + lcrId + "/approve", "{}", "risk-maker", 422)
                .get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode approved = post("/v1/treasury/liquidity/lcr/" + lcrId + "/approve", "{\"comment\":\"ok\"}", "risk-checker", 200);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("approved_by").asString()).isEqualTo("risk-checker");
        assertThat(approved.hasNonNull("approved_at")).isTrue();
        assertThat(post("/v1/treasury/liquidity/lcr/" + lcrId + "/approve", "{}", "risk-boss", 409)
                .get("code").asString()).isEqualTo("invalid_transition");
        assertThat(post("/v1/treasury/liquidity/lcr/calculate", body, "risk-maker", 409).get("code").asString())
                .isEqualTo("calculation_locked");
        assertThat(post("/v1/treasury/liquidity/lcr/calculate", body.replace("RETAIL_STABLE", "MYSTERY")
                .replace(FROM.toString(), FROM.plusDays(1).toString()), "risk-maker", 422).get("code").asString())
                .isEqualTo("unknown_code");
        assertThat(post("/v1/treasury/liquidity/lcr/calculate", body.replace("\"category\":\"HQLA\"", "\"category\":\"INFLOW\"")
                .replace(FROM.toString(), FROM.plusDays(1).toString()), "risk-maker", 422).get("code").asString())
                .isEqualTo("category_mismatch");
        assertThat(events("treasury.lcr.approved", lcrId)).isEqualTo(1);
    }

    @Test
    @Order(6)
    void nsfrCalculateAndApprove() throws Exception {
        JsonNode nsfr = post("/v1/treasury/liquidity/nsfr/calculate", """
                {"legal_entity_id":"%s","as_of_date":"%s","currency":"EUR","positions":[
                  {"code":"REGULATORY_CAPITAL","side":"ASF","amount":"100"},{"code":"RETAIL_STABLE","amount":"1000"},
                  {"code":"FINANCIAL_SHORT","amount":"200"},{"code":"L1_UNENCUMBERED","side":"RSF","amount":"300"},
                  {"code":"LOANS_LONG","amount":"800"},{"code":"RESIDENTIAL_MORTGAGES_LOW_RW","amount":"400"},
                  {"code":"OFF_BALANCE_COMMITTED","amount":"1000"}]}""".formatted(ENTITY, FROM), "risk-maker", 200);
        assertThat(dec(nsfr, "available_stable_funding")).isEqualByComparingTo("1050");
        assertThat(dec(nsfr, "required_stable_funding")).isEqualByComparingTo("1005");
        assertThat(dec(nsfr, "nsfr_ratio")).isEqualByComparingTo("1.0447761194");
        assertThat(nsfr.get("breach").asBoolean()).isFalse();
        String id = nsfr.get("id").asString();
        assertThat(post("/v1/treasury/liquidity/nsfr/" + id + "/approve", "{}", "risk-maker", 422).get("code").asString())
                .isEqualTo("four_eyes_violation");
        assertThat(post("/v1/treasury/liquidity/nsfr/" + id + "/approve", "{}", "risk-checker", 200)
                .get("status").asString()).isEqualTo("APPROVED");
        assertThat(post("/v1/treasury/liquidity/nsfr/calculate", """
                {"legal_entity_id":"%s","as_of_date":"%s","currency":"EUR","positions":[
                  {"code":"LOANS_SHORT","side":"ASF","amount":"1"}]}""".formatted(ENTITY, FROM.plusDays(1)),
                "risk-maker", 422).get("code").asString()).isEqualTo("side_mismatch");
        assertThat(events("treasury.nsfr.calculated", id)).isEqualTo(1);
        assertThat(events("treasury.nsfr.approved", id)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ stress testing

    @Test
    @Order(7)
    void stressScenarioMakerCheckerAndSurvivalHorizon() throws Exception {
        assertThat(post("/v1/treasury/liquidity/stress-scenarios", """
                {"code":"BAD","name":"Bad","severity":"SEVERE","assumptions":{"runoff_rates":{"RETAIL_LESS_STABLE":"1.5"}}}""",
                "risk-maker", 422).get("code").asString()).isEqualTo("invalid_assumptions");
        String create = """
                {"code":"IDIO-30","name":"Idiosyncratic 30 days","severity":"IDIOSYNCRATIC","horizon_days":30,
                 "assumptions":{"runoff_rates":{"RETAIL_LESS_STABLE":"0.30"},"runoff_profile":"LINEAR"}}""";
        JsonNode scenario = post("/v1/treasury/liquidity/stress-scenarios", create, "risk-maker", 201);
        scenarioId = scenario.get("id").asString();
        assertThat(post("/v1/treasury/liquidity/stress-scenarios", create, "risk-maker", 409).get("code").asString())
                .isEqualTo("scenario_code_exists");
        String run = """
                {"legal_entity_id":"%s","as_of_date":"%s","currency":"EUR","positions":[
                  {"line":"govies","code":"L1","amount":"1000"},{"line":"covered","code":"L2A","amount":"200"},
                  {"line":"retail","code":"RETAIL_LESS_STABLE","amount":"3000"},
                  {"line":"interbank","code":"FINANCIAL_INSTITUTION","amount":"600","days":10},
                  {"line":"loan","code":"CORPORATE_LOANS","amount":"400","days":5}]}""".formatted(ENTITY, FROM);
        String runPath = "/v1/treasury/liquidity/stress-scenarios/" + scenarioId + "/run";
        assertThat(post(runPath, run, "risk-maker", 422).get("code").asString()).isEqualTo("scenario_not_approved");
        assertThat(post("/v1/treasury/liquidity/stress-scenarios/" + scenarioId + "/approve", "{}", "risk-maker", 422)
                .get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode approved = post("/v1/treasury/liquidity/stress-scenarios/" + scenarioId + "/approve", "{}", "alco", 200);
        assertThat(approved.get("approved_by").asString()).isEqualTo("alco");

        JsonNode result = post(runPath, run, "risk-maker", 200);
        assertThat(dec(result, "counterbalancing_capacity")).isEqualByComparingTo("1170");
        assertThat(dec(result, "stressed_outflows")).isEqualByComparingTo("1500");
        assertThat(result.get("survival_days").asInt()).isEqualTo(25);
        assertThat(result.get("breach").asBoolean()).isTrue();
        assertThat(result.get("daily_profile")).hasSize(30);
        assertThat(events("treasury.liquidity_stress.breached", result.get("id").asString())).isEqualTo(1);
        assertThat(get("/v1/treasury/liquidity/stress-results?liquidity_stress_scenario_id=" + scenarioId + "&breach=true")
                .get("data")).hasSize(1);

        // a material change withdraws the approval
        HttpResponse<String> patched = send("PATCH", "/v1/treasury/liquidity/stress-scenarios/" + scenarioId,
                "{\"assumptions\":{\"runoff_profile\":\"UPFRONT\"}}", null, "risk-maker", TENANT, "\"2\"");
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        assertThat(json.readTree(patched.body()).hasNonNull("approved_by")).isFalse();
        assertThat(patched.headers().firstValue("ETag")).contains("\"3\"");
        HttpResponse<String> stale = send("PATCH", "/v1/treasury/liquidity/stress-scenarios/" + scenarioId,
                "{\"name\":\"x\"}", null, "risk-maker", TENANT, "\"2\"");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(post(runPath, run, "risk-maker", 422).get("code").asString()).isEqualTo("scenario_not_approved");
    }

    // ------------------------------------------------------------------ intraday

    @Test
    @Order(8)
    void intradaySnapshotLargestNegativePosition() throws Exception {
        JsonNode snap = post("/v1/treasury/liquidity/intraday", """
                {"legal_entity_id":"%s","currency":"EUR","snapshot_at":"2026-11-02T12:00:00Z","available_liquidity":"1000",
                 "expected_daily_payments":"600","movements":[
                   {"at":"2026-11-02T11:00:00Z","direction":"RECEIVED","amount":"300"},
                   {"at":"2026-11-02T09:00:00Z","direction":"SENT","amount":"100"},
                   {"at":"2026-11-02T09:30:00Z","direction":"RECEIVED","amount":"50"},
                   {"at":"2026-11-02T10:00:00Z","direction":"SENT","amount":"200"}]}""".formatted(ENTITY), "ops", 201);
        assertThat(dec(snap, "largest_negative_position")).isEqualByComparingTo("250");
        assertThat(dec(snap, "used_liquidity")).isEqualByComparingTo("250");
        assertThat(dec(snap, "largest_positive_position")).isEqualByComparingTo("50");
        assertThat(dec(snap, "payments_sent")).isEqualByComparingTo("300");
        assertThat(dec(snap, "payments_received")).isEqualByComparingTo("350");
        assertThat(dec(snap, "throughput_pct")).isEqualByComparingTo("0.5");
        assertThat(snap.get("profile")).hasSize(4);
        assertThat(events("treasury.intraday_liquidity.recorded", snap.get("id").asString())).isEqualTo(1);
        assertThat(post("/v1/treasury/liquidity/intraday", """
                {"legal_entity_id":"%s","currency":"EUR","snapshot_at":"2026-11-02T08:00:00Z","available_liquidity":"1000",
                 "movements":[{"at":"2026-11-02T09:00:00Z","direction":"SENT","amount":"100"}]}""".formatted(ENTITY),
                "ops", 422).get("code").asString()).isEqualTo("movement_after_snapshot");
    }

    // ------------------------------------------------------------------ tenancy

    @Test
    @Order(9)
    void tenantsAreIsolated() throws Exception {
        HttpResponse<String> foreign = send("GET", "/v1/treasury/liquidity/lcr/" + lcrId, null, null, "ops", OTHER_TENANT);
        assertThat(foreign.statusCode()).isEqualTo(404);
        HttpResponse<String> list = send("GET", "/v1/treasury/liquidity/cash-positions", null, null, "ops", OTHER_TENANT);
        assertThat(json.readTree(list.body()).get("data")).isEmpty();
        HttpResponse<String> approve = send("POST", "/v1/treasury/liquidity/stress-scenarios/" + scenarioId + "/approve",
                "{}", UUID.randomUUID().toString(), "alco", OTHER_TENANT);
        assertThat(approve.statusCode()).isEqualTo(404);
        JsonNode mine = get("/v1/treasury/liquidity/lcr/" + lcrId);
        assertThat(mine.get("status").asString()).isEqualTo("APPROVED");
    }

    // ------------------------------------------------------------------ helpers

    private String position(String ccy, String type, LocalDate date, String opening, String in, String out, String buffer) {
        return """
                {"legal_entity_id":"%s","position_date":"%s","currency":"%s","position_type":"%s","opening_balance":"%s",
                 "expected_inflows":"%s","expected_outflows":"%s"%s}""".formatted(ENTITY, date, ccy, type, opening, in, out,
                buffer == null ? "" : ",\"minimum_buffer\":\"" + buffer + "\"");
    }

    private static JsonNode position(JsonNode summary, String ccy, LocalDate date) {
        for (JsonNode p : summary.get("positions")) {
            if (ccy.equals(p.get("currency").asString()) && date.toString().equals(p.get("position_date").asString())) {
                return p;
            }
        }
        throw new AssertionError("no " + ccy + " position on " + date + " in " + summary);
    }

    private <T> T asTenant(Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", body);
    }

    private int events(String type, String aggregateId) throws Exception {
        return asTenant(() -> jdbc.sql("""
                select count(*) from outbox where tenant_id = ?::uuid and event_type = ?
                  and (?::uuid is null or aggregate_id = ?::uuid)""")
                .params(TENANT, type, aggregateId, aggregateId).query(Integer.class).single());
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
        HttpResponse<String> r = send("GET", path, null, null, "ops", TENANT);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String user, String tenant)
            throws Exception {
        return send(method, path, body, idemKey, user, tenant, null);
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String user, String tenant,
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
