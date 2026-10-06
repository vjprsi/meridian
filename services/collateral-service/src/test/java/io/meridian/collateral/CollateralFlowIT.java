package io.meridian.collateral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
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
 * collateral-service end to end over HTTP against PostgreSQL 18 (RLS enforced) with loan-service stubbed behind a
 * mocked {@link ServiceClient} ({@link FakeLoanService}): registration with type-specific validation, valuations,
 * four-eyes verification, pledge, links with LTV and alerts, insurance expiry monitoring (EOD), liquidation through
 * loan repayments and recoveries, release, idempotency and tenant isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CollateralFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000c101";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-00000000c102";
    static final String OWNER = "0199a000-0000-7000-8000-00000000c201";
    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakeLoanService loans;
    UUID loanA;
    UUID loanB;
    String property;
    String linkA;
    String linkB;
    String insurance;

    @BeforeAll
    void setUp() {
        loans = new FakeLoanService(json);
        loanA = loans.loan("EUR", "100000.00", "ACTIVE");
        loanB = loans.loan("EUR", "50000.00", "ACTIVE");
    }

    @BeforeEach
    void stub() {
        when(serviceClient.get(anyString(), anyString()))
                .thenAnswer(i -> loans.get(i.getArgument(0), i.getArgument(1)));
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> loans.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
    }

    @Test
    @Order(1)
    void registerValidatesTypeSpecificAttributesAndIsIdempotent() throws Exception {
        HttpResponse<String> bad = send("POST", "/v1/collaterals", propertyBody(null), key(), "maker", TENANT);
        assertThat(bad.statusCode()).isEqualTo(422);
        JsonNode problem = json.readTree(bad.body());
        assertThat(problem.get("code").asString()).isEqualTo("invalid_asset_details");
        assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("asset_details.title_number");

        String k = key();
        HttpResponse<String> created = send("POST", "/v1/collaterals", propertyBody("TN-12345"), k, "maker", TENANT);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode c = json.readTree(created.body());
        property = c.get("id").asString();
        assertThat(c.get("collateral_number").asString()).matches("COL-\\d{4}-[A-Z2-9]{8}");
        assertThat(c.get("status").asString()).isEqualTo("PROPOSED");
        assertThat(dec(c, "haircut_pct")).isEqualByComparingTo("0.30");
        assertThat(dec(c, "lending_value")).isEqualByComparingTo("210000");

        HttpResponse<String> replay = send("POST", "/v1/collaterals", propertyBody("TN-12345"), k, "maker", TENANT);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(property);
        assertThat(events("lending.collateral.registered", property)).isEqualTo(1);

        HttpResponse<String> vin = send("POST", "/v1/collaterals", """
                {"owner_customer_id":"%s","collateral_type":"VEHICLE","description":"Car","currency":"EUR",
                 "market_value":"20000","asset_details":{"vin":"1HGCM82633A00435O","registration_number":"AB12"}}"""
                .formatted(OWNER), key(), "maker", TENANT);
        assertThat(vin.statusCode()).isEqualTo(422);
        assertThat(vin.body()).contains("invalid_vin");
    }

    @Test
    @Order(2)
    void valuationThenFourEyesVerificationAndPledge() throws Exception {
        assertThat(code(send("POST", "/v1/collaterals/" + property + "/verify", null, key(), "checker", TENANT)))
                .isEqualTo("valuation_required");
        JsonNode v = post("/v1/collaterals/" + property + "/valuations", """
                {"collateral_id":"%s","valuation_date":"%s","market_value":"320000.00","forced_sale_value":"250000.00","currency":"EUR",
                 "valuer":"Acme Surveyors","method":"FULL_SURVEY"}""".formatted(property, YESTERDAY), "valuer", 201);
        assertThat(dec(v, "lending_value")).isEqualByComparingTo("224000");
        assertThat(dec(v, "haircut_pct")).isEqualByComparingTo("0.30");
        JsonNode c = get("/v1/collaterals/" + property);
        assertThat(dec(c, "market_value")).isEqualByComparingTo("320000");
        assertThat(c.get("next_valuation_date").asString()).isEqualTo(YESTERDAY.plusMonths(12).toString());
        assertThat(events("lending.collateral.revalued", null)).isEqualTo(1);

        assertThat(code(send("POST", "/v1/collaterals/" + property + "/valuations", """
                {"collateral_id":"%s","valuation_date":"%s","market_value":"1","currency":"USD","method":"DESKTOP"}""".formatted(property, YESTERDAY),
                key(), "valuer", TENANT))).isEqualTo("currency_mismatch");
        assertThat(code(send("POST", "/v1/collaterals/" + property + "/valuations", """
                {"collateral_id":"%s","valuation_date":"%s","market_value":"1","currency":"EUR","method":"DESKTOP"}"""
                .formatted(UUID.randomUUID(), YESTERDAY), key(), "valuer", TENANT))).isEqualTo("collateral_id_mismatch");

        assertThat(code(send("POST", "/v1/collaterals/" + property + "/verify", null, key(), "maker", TENANT)))
                .isEqualTo("four_eyes_violation");
        JsonNode verified = post("/v1/collaterals/" + property + "/verify", null, "checker", 200);
        assertThat(verified.get("status").asString()).isEqualTo("VERIFIED");
        assertThat(verified.get("verified_by").asString()).isEqualTo("checker");
        HttpResponse<String> again = send("POST", "/v1/collaterals/" + property + "/verify", null, key(), "checker", TENANT);
        assertThat(again.statusCode()).isEqualTo(409);

        assertThat(code(send("POST", "/v1/collaterals/" + property + "/pledge", """
                {"registration_reference":"LR-1","lien_registered_at":"%s"}""".formatted(TODAY.plusDays(5)),
                key(), "ops", TENANT))).isEqualTo("date_in_future");
        JsonNode pledged = post("/v1/collaterals/" + property + "/pledge", """
                {"registration_reference":"LR-2026-77","lien_registered_at":"%s"}""".formatted(YESTERDAY), "ops", 200);
        assertThat(pledged.get("status").asString()).isEqualTo("PLEDGED");
        assertThat(pledged.get("registration_reference").asString()).isEqualTo("LR-2026-77");
    }

    @Test
    @Order(3)
    void linksComputeLtvRejectOverAllocationAndRaiseAlerts() throws Exception {
        JsonNode a = post("/v1/collateral-links", linkBody(property, loanA, "150000.00", "0.80"), "ops", 201);
        linkA = a.get("id").asString();
        assertThat(dec(a, "ltv")).isEqualByComparingTo("0.666667");
        assertThat(a.get("ltv_status").asString()).isEqualTo("OK");
        assertThat(dec(a, "exposure_amount")).isEqualByComparingTo("100000");

        assertThat(code(send("POST", "/v1/collateral-links", linkBody(property, loanB, "100000.00", "0.80"),
                key(), "ops", TENANT))).isEqualTo("over_allocated");
        assertThat(code(send("POST", "/v1/collateral-links", linkBody(property, UUID.randomUUID(), "1000.00", null),
                key(), "ops", TENANT))).isEqualTo("exposure_not_found");
        assertThat(code(send("POST", "/v1/collateral-links", linkBody(property, loanA, "1000.00", null),
                key(), "ops", TENANT))).isEqualTo("link_exists");

        JsonNode b = post("/v1/collateral-links", linkBody(property, loanB, "60000.00", "0.80"), "ops", 201);
        linkB = b.get("id").asString();
        assertThat(b.get("ltv_status").asString()).isEqualTo("BREACH"); // 50000 / 60000 = 0.833 > 0.80
        assertThat(events("lending.collateral_link.ltv_breached", linkB)).isEqualTo(1);
        assertThat(dec(get("/v1/collaterals/" + property), "allocated_value")).isEqualByComparingTo("210000");

        loans.setOutstanding(loanA, "130000.00");
        JsonNode recalculated = post("/v1/collateral-links/" + linkA + "/recalculate-ltv", null, "ops", 200);
        assertThat(dec(recalculated, "ltv")).isEqualByComparingTo("0.866667");
        assertThat(recalculated.get("ltv_status").asString()).isEqualTo("BREACH");
        assertThat(events("lending.collateral_link.ltv_breached", linkA)).isEqualTo(1);

        JsonNode report = get("/v1/collaterals/" + property + "/ltv");
        assertThat(report.get("exposures")).hasSize(2);
        assertThat(report.get("worst_ltv_status").asString()).isEqualTo("BREACH");
        assertThat(dec(report, "unallocated_value")).isEqualByComparingTo("14000");

        // PATCH: identity / values from valuations are protected, haircut recomputes the lending value
        HttpResponse<String> patch = send("PATCH", "/v1/collaterals/" + property, "{\"market_value\":\"1\"}", null, "ops", TENANT);
        assertThat(patch.statusCode()).isEqualTo(422);
        int version = get("/v1/collaterals/" + property).get("row_version").asInt();
        HttpResponse<String> hair = send("PATCH", "/v1/collaterals/" + property, "{\"haircut_pct\":\"0.25\"}", null, "ops",
                TENANT, version);
        assertThat(hair.statusCode()).as(hair.body()).isEqualTo(200);
        assertThat(dec(json.readTree(hair.body()), "lending_value")).isEqualByComparingTo("240000");
        HttpResponse<String> stale = send("PATCH", "/v1/collaterals/" + property, "{\"description\":\"x\"}", null, "ops",
                TENANT, version);
        assertThat(stale.statusCode()).isEqualTo(409);

        int linkVersion = get("/v1/collateral-links/" + linkB).get("row_version").asInt();
        assertThat(send("PATCH", "/v1/collateral-links/" + linkB, "{\"exposure_id\":\"" + loanA + "\"}", null, "ops",
                TENANT, linkVersion).statusCode()).isEqualTo(422);
        HttpResponse<String> raise = send("PATCH", "/v1/collateral-links/" + linkB, "{\"allocated_value\":\"90000.00\"}",
                null, "ops", TENANT, linkVersion);
        assertThat(raise.statusCode()).as(raise.body()).isEqualTo(200);
        assertThat(dec(json.readTree(raise.body()), "ltv")).isEqualByComparingTo("0.555556");
        assertThat(json.readTree(raise.body()).get("ltv_status").asString()).isEqualTo("OK");
    }

    @Test
    @Order(4)
    void insuranceValidationAndEndOfDayMonitoringIsIdempotent() throws Exception {
        assertThat(code(send("POST", "/v1/collateral-insurances", insuranceBody(TODAY, TODAY.minusDays(1)), key(), "ops",
                TENANT))).isEqualTo("invalid_period");
        insurance = post("/v1/collateral-insurances", insuranceBody(TODAY.minusDays(355), TODAY.plusDays(10)), "ops", 201)
                .get("id").asString();

        JsonNode run = post("/v1/collaterals/eod/monitor?business_date=" + TODAY, null, "business-day", 200);
        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(run.get("replayed").asBoolean()).isFalse();
        assertThat(run.get("counts").get("insurance_expiring").asInt()).isEqualTo(1);
        assertThat(run.get("counts").get("links_recalculated").asInt()).isEqualTo(2);
        assertThat(run.get("counts").get("exposures").asInt()).isEqualTo(2);
        assertThat(get("/v1/collateral-insurances/" + insurance).hasNonNull("expiry_notified_at")).isTrue();

        JsonNode replay = post("/v1/collaterals/eod/monitor?business_date=" + TODAY, null, "business-day", 200);
        assertThat(replay.get("replayed").asBoolean()).isTrue();
        assertThat(replay.get("run_id").asString()).isEqualTo(run.get("run_id").asString());
        JsonNode next = post("/v1/collaterals/eod/monitor?business_date=" + TODAY.plusDays(1), null, "business-day", 200);
        assertThat(next.get("counts").get("insurance_expiring").asInt()).isZero(); // warned once per end date
        assertThat(events("lending.collateral_insurance.expiring", insurance)).isEqualTo(1);
        assertThat(get("/v1/collateral-eod-runs?business_date=" + TODAY).get("data")).hasSize(1);

        JsonNode reval = post("/v1/collaterals/eod/monitor?business_date=" + YESTERDAY.plusMonths(12), null, "business-day", 200);
        assertThat(reval.get("counts").get("revaluations_due").asInt()).isEqualTo(1);
        assertThat(events("lending.collateral.revaluation_due", property)).isEqualTo(1);
    }

    @Test
    @Order(5)
    void liquidationAppliesProceedsByChargeRankAndIsReplayedIdempotently() throws Exception {
        loans.setOutstanding(loanB, "50000.00");
        loans.setStatus(loanB, "WRITTEN_OFF");
        String k = key();
        String body = """
                {"proceeds":"200000.00","sale_date":"%s","costs":"5000.00","reference":"AUCTION-9"}""".formatted(YESTERDAY);
        HttpResponse<String> r = send("POST", "/v1/collaterals/" + property + "/liquidate", body, k, "ops", TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode result = json.readTree(r.body());
        assertThat(dec(result, "net_proceeds")).isEqualByComparingTo("195000");
        assertThat(result.get("allocations")).hasSize(2);
        assertThat(result.get("allocations").get(0).get("applied_as").asString()).isEqualTo("LOAN_REPAYMENT");
        assertThat(dec(result.get("allocations").get(0), "amount")).isEqualByComparingTo("130000");
        assertThat(result.get("allocations").get(1).get("applied_as").asString()).isEqualTo("LOAN_RECOVERY");
        assertThat(dec(result.get("allocations").get(1), "amount")).isEqualByComparingTo("50000");
        assertThat(dec(result, "surplus")).isEqualByComparingTo("15000");
        assertThat(loans.outstanding(loanA)).isEqualByComparingTo("0");

        List<FakeLoanService.Call> repay = loans.calls("POST", "/v1/loans/" + loanA + "/repay");
        assertThat(repay).hasSize(1);
        assertThat(repay.getFirst().key()).isEqualTo("collateral-liquidation:" + property + ":" + linkA);
        assertThat(repay.getFirst().body().get("source").asString()).isEqualTo("COLLATERAL_LIQUIDATION");
        List<FakeLoanService.Call> recovery = loans.calls("POST", "/v1/loan-recoveries");
        assertThat(recovery).hasSize(1);
        assertThat(recovery.getFirst().body().get("source").asString()).isEqualTo("COLLATERAL_SALE");
        assertThat(recovery.getFirst().body().get("received_at").asString()).endsWith("Z");

        HttpResponse<String> replay = send("POST", "/v1/collaterals/" + property + "/liquidate", body, k, "ops", TENANT);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body())).isEqualTo(result);
        assertThat(loans.calls("POST", "/v1/loans/" + loanA + "/repay")).hasSize(1);

        JsonNode c = get("/v1/collaterals/" + property);
        assertThat(c.get("status").asString()).isEqualTo("LIQUIDATED");
        assertThat(dec(c, "liquidation_proceeds")).isEqualByComparingTo("195000");
        assertThat(dec(c, "allocated_value")).isEqualByComparingTo("0");
        assertThat(get("/v1/collateral-links/" + linkA).get("is_active").asBoolean()).isFalse();
        assertThat(get("/v1/collateral-links?collateral_id=" + property + "&is_active=true").get("data")).isEmpty();
        assertThat(send("POST", "/v1/collaterals/" + property + "/liquidate", body, key(), "ops", TENANT).statusCode())
                .isEqualTo(409);
    }

    @Test
    @Order(6)
    void releaseNeedsForceWhileLinkedAndRejectEndsTheLifecycle() throws Exception {
        String gold = post("/v1/collaterals", """
                {"owner_customer_id":"%s","collateral_type":"GOLD","description":"Gold bar","currency":"EUR",
                 "market_value":"10000.00","asset_details":{"carat":24,"weight_grams":"100"}}""".formatted(OWNER),
                "maker", 201).get("id").asString();
        assertThat(dec(get("/v1/collaterals/" + gold), "lending_value")).isEqualByComparingTo("8000");
        post("/v1/collaterals/" + gold + "/valuations", """
                {"collateral_id":"%s","valuation_date":"%s","market_value":"10000.00","currency":"EUR","method":"MARKET_PRICE"}"""
                .formatted(gold, YESTERDAY), "valuer", 201);
        post("/v1/collaterals/" + gold + "/verify", null, "checker", 200);
        UUID line = loans.creditLine("EUR", "3000.00");
        String link = post("/v1/collateral-links", """
                {"collateral_id":"%s","exposure_type":"CREDIT_LINE","exposure_id":"%s","allocated_value":"5000.00"}"""
                .formatted(gold, line), "ops", 201).get("id").asString();
        assertThat(dec(get("/v1/collateral-links/" + link), "ltv")).isEqualByComparingTo("0.6");

        assertThat(code(send("POST", "/v1/collaterals/" + gold + "/release", "{\"reason\":\"repaid\"}", key(), "ops",
                TENANT))).isEqualTo("active_links_exist");
        JsonNode released = post("/v1/collaterals/" + gold + "/release", "{\"reason\":\"repaid\",\"force\":true}", "ops", 200);
        assertThat(released.get("status").asString()).isEqualTo("RELEASED");
        assertThat(released.hasNonNull("released_at")).isTrue();
        assertThat(get("/v1/collateral-links/" + link).get("release_reason").asString()).contains("repaid");
        assertThat(events("lending.collateral_link.released", link)).isEqualTo(1);

        String other = post("/v1/collaterals", propertyBody("TN-2"), "maker", 201).get("id").asString();
        assertThat(post("/v1/collaterals/" + other + "/reject", "{\"reason\":\"title defect\"}", "checker", 200)
                .get("status").asString()).isEqualTo("REJECTED");
        assertThat(send("POST", "/v1/collaterals/" + other + "/verify", null, key(), "checker", TENANT).statusCode())
                .isEqualTo(409);
        assertThat(send("POST", "/v1/collaterals/" + other + "/reject", "{}", key(), "checker", TENANT).statusCode())
                .isEqualTo(400);
    }

    @Test
    @Order(7)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/collaterals/" + property, null, null, "ops", OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/v1/collaterals/" + property + "/verify", null, key(), "ops", OTHER_TENANT).statusCode())
                .isEqualTo(404);
        assertThat(json.readTree(send("GET", "/v1/collateral-links", null, null, "ops", OTHER_TENANT).body())
                .get("data")).isEmpty();
        assertThat(json.readTree(send("GET", "/v1/collaterals", null, null, "ops", OTHER_TENANT).body())
                .get("data")).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private String propertyBody(String titleNumber) {
        return """
                {"owner_customer_id":"%s","collateral_type":"PROPERTY","description":"Flat 4, 12 High St","currency":"EUR",
                 "market_value":"300000.00","asset_details":%s,"location":{"city":"Dublin"}}"""
                .formatted(OWNER, titleNumber == null ? "{}" : "{\"title_number\":\"" + titleNumber + "\"}");
    }

    private static String linkBody(String collateral, UUID loan, String allocated, String maxLtv) {
        return """
                {"collateral_id":"%s","exposure_type":"LOAN","exposure_id":"%s","allocated_value":"%s"%s}"""
                .formatted(collateral, loan, allocated, maxLtv == null ? "" : ",\"max_ltv\":\"" + maxLtv + "\"");
    }

    private String insuranceBody(LocalDate start, LocalDate end) {
        return """
                {"collateral_id":"%s","insurer":"Allianz","policy_number":"P-1","sum_insured":"300000.00","currency":"EUR",
                 "start_date":"%s","end_date":"%s"}""".formatted(property, start, end);
    }

    private int events(String type, String aggregateId) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", () -> jdbc.sql("""
                select count(*) from outbox where tenant_id = ?::uuid and event_type = ?
                  and (?::uuid is null or aggregate_id = ?::uuid)""")
                .params(TENANT, type, aggregateId, aggregateId).query(Integer.class).single());
    }

    private static BigDecimal dec(JsonNode n, String f) {
        return new BigDecimal(n.get(f).asString());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private String code(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isGreaterThanOrEqualTo(400);
        return json.readTree(r.body()).get("code").asString();
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, key(), user, TENANT);
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
        return send(method, path, body, idemKey, user, tenant, 1);
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String user, String tenant,
                                      int version) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        if ("PATCH".equals(method)) {
            b.header("If-Match", "\"" + version + "\"");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
