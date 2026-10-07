package io.meridian.productcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Product Studio end to end over HTTP against PostgreSQL 18 with RLS: template -> product -> draft content ->
 * maker-checker -> publish -> new draft -> diff -> republish (supersede) -> pricing (rates, fees, eligibility,
 * quote) -> index fixings -> scripts -> simulation -> promotions -> suspend/retire/clone, plus invalid transitions,
 * validation errors, idempotent replay and tenant isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductCatalogFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000d1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000d2";
    static final String MAKER = "alice";
    static final String CHECKER = "bob";
    static final String RUST = "#[no_mangle]\\npub extern \\\"C\\\" fn run(balance: i64, days: i64) -> i64 {\\n    balance * days / 36500\\n}";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient services;

    @Autowired
    io.meridian.web.time.BusinessDates businessDates;

    final HttpClient http = HttpClient.newHttpClient();
    String template;
    String product;
    String v1;
    String v2;
    String v3;
    String rateConfig;
    String fee;

    @BeforeAll
    void setUp() throws Exception {
        template = post("/v1/product-templates", """
                {"code":"TPL_SAVINGS_TIERED","name":"Tiered savings","family":"SAVINGS",
                 "editable_fields":["interest_rates","fees"],
                 "default_definition":{
                   "parameters":{"dormancy.inactive_days":365,"statement.frequency":"MONTHLY"},
                   "interest_rates":[{"rate_kind":"CREDIT_INTEREST","rate_type":"TIERED","effective_from":"2020-01-01",
                     "day_count":"ACT_365","capitalisation_frequency":"MONTHLY",
                     "tiers":[{"from_amount":0,"to_amount":1000,"rate":"0.01"},{"from_amount":1000,"to_amount":10000,"rate":"0.02"},
                              {"from_amount":10000,"rate":"0.03"}]}],
                   "fees":[{"code":"MONTHLY_MAINT","name":"Monthly maintenance","trigger":"MONTHLY_MAINTENANCE","frequency":"MONTHLY",
                     "calculation_method":"FLAT","flat_amount":"5.00",
                     "waiver_rules":[{"condition":"MIN_BALANCE","threshold_amount":"2500","discount_percentage":"1"}]}],
                   "limits":[{"limit_code":"ATM_DAILY","period":"DAILY","metric":"AMOUNT","max_value":"1000"}]}}""",
                201, MAKER).get("id").asString();
    }

    @Test
    @Order(1)
    void productFromTemplateMaterialisesDraftVersion() throws Exception {
        String key = "ft-" + UUID.randomUUID();
        String body = """
                {"template_id":"%s","code":"SAV-GOLD-USD","name":"Gold Saver","currency":"USD","legal_entity_id":"%s"}"""
                .formatted(template, UUID.randomUUID());
        HttpResponse<String> r = send("POST", "/v1/products/from-template", body, key, TENANT, MAKER, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode p = json.readTree(r.body());
        product = p.get("id").asString();
        assertThat(p.get("status").asString()).isEqualTo("DRAFT");
        assertThat(p.get("family").asString()).isEqualTo("SAVINGS");
        HttpResponse<String> replay = send("POST", "/v1/products/from-template", body, key, TENANT, MAKER, null);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(product);

        JsonNode versions = get("/v1/products/" + product + "/versions", TENANT).get("data");
        assertThat(versions).hasSize(1);
        v1 = versions.get(0).get("id").asString();
        assertThat(get("/v1/product-versions/" + v1 + "/parameters", TENANT).get("data")).hasSize(2);
        JsonNode rates = get("/v1/product-versions/" + v1 + "/interest-rates", TENANT).get("data");
        assertThat(rates).hasSize(1);
        rateConfig = rates.get(0).get("id").asString();
        assertThat(rates.get(0).get("currency").asString().trim()).isEqualTo("USD");
        assertThat(get("/v1/interest-rate-configs/" + rateConfig + "/tiers", TENANT).get("data")).hasSize(3);
        JsonNode fees = get("/v1/product-versions/" + v1 + "/fees", TENANT).get("data");
        assertThat(fees).hasSize(1);
        assertThat(get("/v1/fee-definitions/" + fees.get(0).get("id").asString() + "/waiver-rules", TENANT).get("data")).hasSize(1);
        assertThat(get("/v1/product-versions/" + v1 + "/limits", TENANT).get("data")).hasSize(1);
        assertThat(outbox("product.product.created")).isEqualTo(1);
    }

    @Test
    @Order(2)
    void draftContentIsValidated() throws Exception {
        HttpResponse<String> noRate = send("POST", "/v1/interest-rate-configs", """
                {"product_version_id":"%s","rate_kind":"BONUS_INTEREST","rate_type":"FIXED","currency":"USD","effective_from":"2020-01-01"}"""
                .formatted(v1), UUID.randomUUID().toString(), TENANT, MAKER, null);
        assertThat(noRate.statusCode()).isEqualTo(422);
        assertThat(json.readTree(noRate.body()).get("code").asString()).isEqualTo("invalid_configuration");
        HttpResponse<String> wrongCcy = send("POST", "/v1/fee-definitions", """
                {"product_version_id":"%s","code":"ATM","name":"ATM","trigger":"ATM_WITHDRAWAL","calculation_method":"FLAT",
                 "flat_amount":"1.00","currency":"EUR"}""".formatted(v1), UUID.randomUUID().toString(), TENANT, MAKER, null);
        assertThat(wrongCcy.statusCode()).isEqualTo(422);

        String tax = post("/v1/tax-rules", """
                {"code":"VAT_75","name":"VAT 7.5%","tax_type":"VAT","tax_base":"FEE_AMOUNT","country":"NG","rate":"0.075",
                 "valid_from":"2020-01-01"}""", 201, MAKER).get("id").asString();
        fee = post("/v1/fee-definitions", """
                {"product_version_id":"%s","code":"STATEMENT_COPY","name":"Statement copy","trigger":"STATEMENT_COPY",
                 "calculation_method":"FLAT","flat_amount":"5.00","currency":"USD","tax_rule_id":"%s","free_count":1}"""
                .formatted(v1, tax), 201, MAKER).get("id").asString();
        post("/v1/fee-waiver-rules", """
                {"fee_definition_id":"%s","condition":"MIN_BALANCE","threshold_amount":"2500"}""".formatted(fee), 201, MAKER);
    }

    @Test
    @Order(3)
    void effectiveRateAndFeeCalculation() throws Exception {
        String key = "er-" + UUID.randomUUID();
        HttpResponse<String> r = send("POST", "/v1/interest-rate-configs/" + rateConfig + "/effective-rate",
                "{\"balance\":\"5000\",\"as_of\":\"2026-06-01\"}", key, TENANT, MAKER, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode rate = json.readTree(r.body());
        assertThat(new BigDecimal(rate.get("effective_rate").asString())).isEqualByComparingTo("0.02");
        assertThat(new BigDecimal(rate.get("annual_interest").asString())).isEqualByComparingTo("100.00");
        assertThat(rate.get("applied_tier").asInt()).isEqualTo(2);
        HttpResponse<String> replay = send("POST", "/v1/interest-rate-configs/" + rateConfig + "/effective-rate",
                "{\"balance\":\"5000\",\"as_of\":\"2026-06-01\"}", key, TENANT, MAKER, null);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("effective_rate").asString()).isEqualTo(rate.get("effective_rate").asString());

        JsonNode charged = post("/v1/fee-definitions/" + fee + "/calculate",
                "{\"base_amount\":\"0\",\"occurrence_no\":2,\"context\":{\"balance\":\"100\"}}", 200, MAKER);
        assertThat(new BigDecimal(charged.get("net_amount").asString())).isEqualByComparingTo("5.00");
        assertThat(new BigDecimal(charged.get("tax").get("amount").asString())).isEqualByComparingTo("0.38");
        assertThat(new BigDecimal(charged.get("total_amount").asString())).isEqualByComparingTo("5.38");
        JsonNode free = post("/v1/fee-definitions/" + fee + "/calculate", "{\"base_amount\":\"0\",\"occurrence_no\":1}", 200, MAKER);
        assertThat(free.get("free_allowance_applied").asBoolean()).isTrue();
        JsonNode waived = post("/v1/fee-definitions/" + fee + "/calculate",
                "{\"base_amount\":\"0\",\"occurrence_no\":5,\"context\":{\"balance\":\"3000\"}}", 200, MAKER);
        assertThat(new BigDecimal(waived.get("total_amount").asString())).isEqualByComparingTo("0");
        assertThat(waived.get("waiver").get("condition").asString()).isEqualTo("MIN_BALANCE");
    }

    @Test
    @Order(4)
    void makerCheckerAndPublication() throws Exception {
        HttpResponse<String> early = send("POST", "/v1/product-versions/" + v1 + "/publish", "{}",
                UUID.randomUUID().toString(), TENANT, CHECKER, null);
        assertThat(early.statusCode()).isEqualTo(409);
        assertThat(json.readTree(early.body()).get("code").asString()).isEqualTo("invalid_transition");

        JsonNode submitted = post("/v1/product-versions/" + v1 + "/submit", "{\"comment\":\"launch\"}", 200, MAKER);
        assertThat(submitted.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(submitted.get("checksum").asString()).hasSize(64);
        assertThat(submitted.get("definition").get("interest_rates")).hasSize(1);

        HttpResponse<String> locked = send("POST", "/v1/product-parameters", """
                {"product_version_id":"%s","param_key":"x.y","param_type":"STRING","value_text":"z"}""".formatted(v1),
                UUID.randomUUID().toString(), TENANT, MAKER, null);
        assertThat(locked.statusCode()).isEqualTo(409);
        assertThat(json.readTree(locked.body()).get("code").asString()).isEqualTo("version_not_editable");

        HttpResponse<String> self = send("POST", "/v1/product-versions/" + v1 + "/approve", "{}",
                UUID.randomUUID().toString(), TENANT, MAKER, null);
        assertThat(self.statusCode()).isEqualTo(422);
        assertThat(json.readTree(self.body()).get("code").asString()).isEqualTo("four_eyes_violation");

        JsonNode approved = post("/v1/product-versions/" + v1 + "/approve", "{\"comment\":\"ok\"}", 200, CHECKER);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("approved_by").asString()).isEqualTo(CHECKER);
        JsonNode published = post("/v1/product-versions/" + v1 + "/publish", "{}", 200, CHECKER);
        assertThat(published.get("status").asString()).isEqualTo("PUBLISHED");
        JsonNode p = get("/v1/products/" + product, TENANT);
        assertThat(p.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(p.get("is_sellable").asBoolean()).isTrue();
        assertThat(p.get("current_version_id").asString()).isEqualTo(v1);

        HttpResponse<String> patch = send("PATCH", "/v1/product-versions/" + v1, "{\"change_summary\":\"x\"}", null, TENANT,
                MAKER, published.get("row_version").asString());
        assertThat(patch.statusCode()).isEqualTo(409);
        assertThat(outbox("product.product_version.submitted")).isEqualTo(1);
        assertThat(outbox("product.product_version.approved")).isEqualTo(1);
        assertThat(outbox("product.product_version.published")).isEqualTo(1);
        assertThat(outbox("product.product.status_changed")).isEqualTo(1);
    }

    @Test
    @Order(5)
    void newDraftDiffAndSupersede() throws Exception {
        JsonNode draft = post("/v1/product-versions/" + v1 + "/new-draft", null, 200, MAKER);
        v2 = draft.get("id").asString();
        assertThat(draft.get("version_no").asInt()).isEqualTo(2);
        HttpResponse<String> second = send("POST", "/v1/product-versions/" + v1 + "/new-draft", null,
                UUID.randomUUID().toString(), TENANT, MAKER, null);
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(json.readTree(second.body()).get("code").asString()).isEqualTo("version_in_progress");

        String cfg2 = get("/v1/product-versions/" + v2 + "/interest-rates", TENANT).get("data").get(0).get("id").asString();
        JsonNode tier2 = null;
        for (JsonNode t : get("/v1/interest-rate-configs/" + cfg2 + "/tiers", TENANT).get("data")) {
            if (t.get("tier_no").asInt() == 2) {
                tier2 = t;
            }
        }
        patch("/v1/interest-rate-tiers/" + tier2.get("id").asString(), "{\"rate\":\"0.022\"}", tier2.get("row_version").asInt());
        post("/v1/eligibility-rules", """
                {"product_version_id":"%s","rule_type":"AGE","operator":"BETWEEN","value":{"min":18,"max":70},
                 "rejection_code":"AGE_OUT_OF_RANGE"}""".formatted(v2), 201, MAKER);
        post("/v1/eligibility-rules", """
                {"product_version_id":"%s","rule_type":"RESIDENCY","operator":"IN","value":{"values":["NG","GH"]}}"""
                .formatted(v2), 201, MAKER);
        HttpResponse<String> badRule = send("POST", "/v1/eligibility-rules", """
                {"product_version_id":"%s","rule_type":"AGE","operator":"ABOUT","value":{"value":18}}""".formatted(v2),
                UUID.randomUUID().toString(), TENANT, MAKER, null);
        assertThat(badRule.statusCode()).isEqualTo(422);

        JsonNode diff = get("/v1/product-versions/" + v2 + "/diff?against=" + v1, TENANT);
        assertThat(diff.get("identical").asBoolean()).isFalse();
        assertThat(diff.get("sections").get("interest_rates").get("changed")).hasSize(1);
        assertThat(diff.get("sections").get("interest_rates").get("changed").get(0).get("changes").get(0).get("field").asString())
                .isEqualTo("tiers");
        assertThat(diff.get("sections").get("eligibility_rules").get("added")).hasSize(2);
        assertThat(send("GET", "/v1/product-versions/" + v2 + "/diff", null, null, TENANT, MAKER, null).statusCode()).isEqualTo(400);

        post("/v1/product-versions/" + v2 + "/submit", "{}", 200, MAKER);
        JsonNode rejected = post("/v1/product-versions/" + v2 + "/reject", "{\"reason\":\"rate too generous\"}", 200, CHECKER);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejection_reason").asString()).isEqualTo("rate too generous");
        assertThat(rejected.get("rejected_by").asString()).isEqualTo(CHECKER);
        JsonNode retry = post("/v1/product-versions/" + v2 + "/new-draft", null, 200, MAKER);
        v2 = retry.get("id").asString();
        assertThat(retry.get("version_no").asInt()).isEqualTo(3);
        post("/v1/product-versions/" + v2 + "/submit", "{}", 200, MAKER);
        post("/v1/product-versions/" + v2 + "/approve", "{}", 200, CHECKER);
        post("/v1/product-versions/" + v2 + "/publish", "{}", 200, CHECKER);
        assertThat(get("/v1/product-versions/" + v1, TENANT).get("status").asString()).isEqualTo("SUPERSEDED");
        assertThat(get("/v1/products/" + product, TENANT).get("current_version_id").asString()).isEqualTo(v2);
        assertThat(get("/v1/product-versions?product_id=" + product + "&status=PUBLISHED", TENANT).get("data")).hasSize(1);
        assertThat(outbox("product.interest_rate_config.changed")).isEqualTo(1);
        assertThat(outbox("product.product_version.rejected")).isEqualTo(1);
    }

    @Test
    @Order(6)
    void migrationRequestIsRecorded() throws Exception {
        post("/v1/product-versions/" + v2 + "/migrate-accounts", """
                {"from_version_id":"%s","effective_date":"%s"}""".formatted(v1, java.time.LocalDate.now().plusDays(30)), 200, MAKER);
        assertThat(outbox("product.product_version.migration_requested")).isEqualTo(1);
        HttpResponse<String> backwards = send("POST", "/v1/product-versions/" + v1 + "/migrate-accounts", """
                {"from_version_id":"%s","effective_date":"%s"}""".formatted(v2, java.time.LocalDate.now().plusDays(1)),
                UUID.randomUUID().toString(), TENANT, MAKER, null);
        assertThat(backwards.statusCode()).isEqualTo(409);
    }

    @Test
    @Order(7)
    void eligibilityCheck() throws Exception {
        JsonNode ok = get("/v1/products/" + product + "/eligibility-check?customer_id=" + UUID.randomUUID()
                + "&age=30&residency_country=NG", TENANT);
        assertThat(ok.get("outcome").asString()).isEqualTo("ELIGIBLE");
        assertThat(ok.get("rules")).hasSize(2);
        JsonNode young = get("/v1/products/" + product + "/eligibility-check?age=16&residency_country=NG", TENANT);
        assertThat(young.get("outcome").asString()).isEqualTo("INELIGIBLE");
        assertThat(young.get("reasons").get(0).asString()).isEqualTo("AGE_OUT_OF_RANGE");
        JsonNode unknown = get("/v1/products/" + product + "/eligibility-check?residency_country=NG", TENANT);
        assertThat(unknown.get("outcome").asString()).isEqualTo("REFER");

        JsonNode p = get("/v1/products/" + product, TENANT);
        JsonNode channels = patch("/v1/products/" + product, "{\"available_channels\":[\"MOBILE\",\"WEB\"]}",
                p.get("row_version").asInt());
        assertThat(channels.get("available_channels")).hasSize(2);
        JsonNode branch = get("/v1/products/" + product + "/eligibility-check?age=30&residency_country=NG&channel=BRANCH"
                + "&existing_products=CUR-01,%20SAV-02", TENANT);
        assertThat(branch.get("outcome").asString()).isEqualTo("INELIGIBLE");
        assertThat(branch.get("reasons").get(0).asString()).isEqualTo("CHANNEL_NOT_AVAILABLE");
        assertThat(get("/v1/products/" + product + "/eligibility-check?age=30&residency_country=NG&channel=MOBILE", TENANT)
                .get("outcome").asString()).isEqualTo("ELIGIBLE");
        assertThat(send("GET", "/v1/products/" + product + "/eligibility-check?age=old", null, null, TENANT, MAKER, null)
                .statusCode()).isEqualTo(400);
    }

    @Test
    @Order(8)
    void quoteCombinesRatePromotionRelationshipAndFees() throws Exception {
        JsonNode promo = post("/v1/promotional-rates", """
                {"product_id":"%s","code":"SPRING26","name":"Spring bonus","rate_kind":"CREDIT_INTEREST","bonus_rate":"0.005",
                 "starts_at":"%s","ends_at":"%s"}""".formatted(product, OffsetDateTime.now().minusHours(1),
                OffsetDateTime.now().plusDays(30)), 201, MAKER);
        assertThat(promo.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(outbox("product.promotional_rate.started")).isEqualTo(1);
        post("/v1/relationship-pricing-rules", """
                {"product_id":"%s","name":"Private banking","segment":"PRIVATE","min_relationship_balance":"100000",
                 "rate_adjustment":"0.0025","priority":10}""".formatted(product), 201, MAKER);

        JsonNode q = post("/v1/products/" + product + "/quote", """
                {"amount":"5000","term_months":12,"channel":"MOBILE",
                 "applicant":{"segment":"PRIVATE","relationship_balance":"250000"}}""", 200, MAKER);
        assertThat(new BigDecimal(q.get("interest_rate").asString())).isEqualByComparingTo("0.0295");
        assertThat(q.get("promotion").get("code").asString()).isEqualTo("SPRING26");
        assertThat(q.get("relationship_pricing").get("name").asString()).isEqualTo("Private banking");
        assertThat(new BigDecimal(q.get("total_fees").asString())).isEqualByComparingTo("0");
        assertThat(new BigDecimal(q.get("total_interest").asString())).isPositive();
        JsonNode retail = post("/v1/products/" + product + "/quote", "{\"amount\":\"500\",\"term_months\":12}", 200, MAKER);
        assertThat(new BigDecimal(retail.get("interest_rate").asString())).isEqualByComparingTo("0.015");
        assertThat(new BigDecimal(retail.get("total_fees").asString())).isEqualByComparingTo("60.00");

        JsonNode cancelled = post("/v1/promotional-rates/" + promo.get("id").asString() + "/cancel",
                "{\"reason\":\"budget\"}", 200, MAKER);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        HttpResponse<String> again = send("POST", "/v1/promotional-rates/" + promo.get("id").asString() + "/cancel",
                "{\"reason\":\"again\"}", UUID.randomUUID().toString(), TENANT, MAKER, null);
        assertThat(again.statusCode()).isEqualTo(409);
    }

    @Test
    @Order(9)
    void rateIndexValuesAreAppendOnly() throws Exception {
        String index = post("/v1/rate-indexes", """
                {"code":"SOFR","name":"Secured Overnight Financing Rate","currency":"USD","tenor":"ON","source":"FED"}""",
                201, MAKER).get("id").asString();
        JsonNode v = post("/v1/rate-indexes/" + index + "/values", "{\"value_date\":\"2026-01-02\",\"rate\":\"0.043\"}", 200, MAKER);
        assertThat(new BigDecimal(v.get("rate").asString())).isEqualByComparingTo("0.043");
        HttpResponse<String> dup = send("POST", "/v1/rate-indexes/" + index + "/values",
                "{\"value_date\":\"2026-01-02\",\"rate\":\"0.044\"}", UUID.randomUUID().toString(), TENANT, MAKER, null);
        assertThat(dup.statusCode()).isEqualTo(409);
        HttpResponse<String> future = send("POST", "/v1/rate-indexes/" + index + "/values",
                "{\"value_date\":\"2999-01-01\",\"rate\":\"0.044\"}", UUID.randomUUID().toString(), TENANT, MAKER, null);
        assertThat(future.statusCode()).isEqualTo(422);
        post("/v1/rate-indexes/" + index + "/values", "{\"value_date\":\"2025-12-31\",\"rate\":\"0.042\"}", 200, MAKER);
        assertThat(get("/v1/rate-indexes/" + index + "/values", TENANT).get("data")).hasSize(2);
        assertThat(get("/v1/rate-indexes/" + index + "/values?from=2026-01-01", TENANT).get("data")).hasSize(1);
        assertThat(outbox("product.rate_index_value.published")).isEqualTo(2);
    }

    @Test
    @Order(10)
    void scriptLifecycle() throws Exception {
        v3 = post("/v1/product-versions/" + v2 + "/new-draft", null, 200, MAKER).get("id").asString();
        JsonNode script = post("/v1/product-scripts", """
                {"product_version_id":"%s","name":"accrual-boost","hook":"ACCRUAL","language":"RUST","source_code":"%s"}"""
                .formatted(v3, RUST), 201, MAKER);
        String id = script.get("id").asString();
        assertThat(script.get("status").asString()).isEqualTo("DRAFT");
        assertThat(script.hasNonNull("wasm_sha256")).isFalse();
        assertThat(script.hasNonNull("wasm_module_uri")).isFalse();
        HttpResponse<String> untested = send("POST", "/v1/product-scripts/" + id + "/test",
                "{\"inputs\":{\"balance\":1000,\"rate\":\"0.05\",\"days\":30}}", UUID.randomUUID().toString(), TENANT, MAKER, null);
        assertThat(untested.statusCode()).isEqualTo(409);
        JsonNode compiled = post("/v1/product-scripts/" + id + "/compile", null, 200, MAKER);
        assertThat(compiled.get("status").asString()).isEqualTo("VALIDATED");
        assertThat(compiled.get("wasm_sha256").asString()).hasSize(64);
        assertThat(compiled.get("wasm_module_uri").asString()).endsWith(compiled.get("wasm_sha256").asString() + ".wasm");
        JsonNode recompiled = post("/v1/product-scripts/" + id + "/compile", null, 200, MAKER);
        assertThat(recompiled.get("wasm_sha256").asString()).isEqualTo(compiled.get("wasm_sha256").asString());
        JsonNode test = post("/v1/product-scripts/" + id + "/test",
                "{\"inputs\":{\"balance\":1000,\"rate\":\"0.05\",\"days\":30}}", 200, MAKER);
        assertThat(test.get("passed").asBoolean()).isTrue();
        post("/v1/product-scripts/" + id + "/submit", "{}", 200, MAKER);
        JsonNode rejected = post("/v1/product-scripts/" + id + "/reject", "{\"reason\":\"needs a cap\"}", 200, CHECKER);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejection_reason").asString()).isEqualTo("needs a cap");
        assertThat(outbox("product.product_script.rejected")).isEqualTo(1);
        post("/v1/product-scripts/" + id + "/compile", null, 200, MAKER);
        JsonNode resubmitted = post("/v1/product-scripts/" + id + "/submit", "{}", 200, MAKER);
        assertThat(resubmitted.get("submitted_by").asString()).isEqualTo(MAKER);
        assertThat(resubmitted.hasNonNull("rejection_reason")).isFalse();
        HttpResponse<String> self = send("POST", "/v1/product-scripts/" + id + "/approve", "{}",
                UUID.randomUUID().toString(), TENANT, MAKER, null);
        assertThat(self.statusCode()).isEqualTo(422);
        JsonNode active = post("/v1/product-scripts/" + id + "/approve", "{}", 200, CHECKER);
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(active.get("approved_by").asString()).isEqualTo(CHECKER);
        assertThat(outbox("product.product_script.activated")).isEqualTo(1);
        JsonNode deprecated = post("/v1/product-scripts/" + id + "/deprecate", "{\"reason\":\"replaced\"}", 200, MAKER);
        assertThat(deprecated.get("status").asString()).isEqualTo("DEPRECATED");
        assertThat(deprecated.get("deprecation_reason").asString()).isEqualTo("replaced");

        JsonNode bad = post("/v1/product-scripts", """
                {"product_version_id":"%s","name":"bad","hook":"ACCRUAL","language":"RUST","source_code":"use std::fs;"}"""
                .formatted(v3), 201, MAKER);
        JsonNode failed = post("/v1/product-scripts/" + bad.get("id").asString() + "/compile", null, 200, MAKER);
        assertThat(failed.get("status").asString()).isEqualTo("DRAFT");
        assertThat(failed.get("validation_report").get("compile").get("passed").asBoolean()).isFalse();
    }

    @Test
    @Order(11)
    void simulationRunsAndExports() throws Exception {
        JsonNode sim = post("/v1/product-simulations", """
                {"product_version_id":"%s","name":"Saver","horizon_months":12,
                 "scenario":{"opening_balance":"5000","monthly_deposit":"100","start_date":"2026-01-01"}}""".formatted(v2),
                201, MAKER);
        assertThat(sim.get("status").asString()).as(sim.toString()).isEqualTo("SUCCEEDED");
        assertThat(sim.get("results").isArray()).isTrue();
        assertThat(sim.get("results")).hasSize(12);
        assertThat(new BigDecimal(sim.get("summary").get("total_interest").asString())).isPositive();
        assertThat(new BigDecimal(sim.get("summary").get("total_fees").asString())).isEqualByComparingTo("0");
        assertThat(outbox("product.product_simulation.completed")).isEqualTo(1);
        HttpResponse<String> cancel = send("POST", "/v1/product-simulations/" + sim.get("id").asString() + "/cancel", null,
                UUID.randomUUID().toString(), TENANT, MAKER, null);
        assertThat(cancel.statusCode()).isEqualTo(409);
        HttpResponse<String> csv = send("GET", "/v1/product-simulations/" + sim.get("id").asString() + "/export", null, null,
                TENANT, MAKER, null);
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(csv.body().split("\r\n")).hasSize(13);
        assertThat(csv.body()).startsWith("month,period_start");
        HttpResponse<String> badScenario = send("POST", "/v1/product-simulations", """
                {"product_version_id":"%s","horizon_months":5000,"scenario":{}}""".formatted(v2),
                UUID.randomUUID().toString(), TENANT, MAKER, null);
        assertThat(badScenario.statusCode()).isEqualTo(422);
    }

    @Test
    @Order(12)
    void suspendReactivateCloneRetire() throws Exception {
        JsonNode suspended = post("/v1/products/" + product + "/suspend", "{\"reason\":\"pricing review\"}", 200, MAKER);
        assertThat(suspended.get("status").asString()).isEqualTo("SUSPENDED");
        HttpResponse<String> quote = send("POST", "/v1/products/" + product + "/quote", "{\"amount\":\"100\"}",
                UUID.randomUUID().toString(), TENANT, MAKER, null);
        assertThat(quote.statusCode()).isEqualTo(409);
        HttpResponse<String> twice = send("POST", "/v1/products/" + product + "/suspend", "{\"reason\":\"again\"}",
                UUID.randomUUID().toString(), TENANT, MAKER, null);
        assertThat(twice.statusCode()).isEqualTo(409);
        JsonNode active = patch("/v1/products/" + product, "{\"status\":\"ACTIVE\"}", suspended.get("row_version").asInt());
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");

        JsonNode clone = post("/v1/products/" + product + "/clone", "{\"code\":\"SAV-GOLD-EUR\",\"name\":\"Gold Saver EUR\"}",
                200, MAKER);
        assertThat(clone.get("status").asString()).isEqualTo("DRAFT");
        JsonNode cloneVersions = get("/v1/products/" + clone.get("id").asString() + "/versions", TENANT).get("data");
        assertThat(cloneVersions).hasSize(1);
        assertThat(get("/v1/product-versions/" + cloneVersions.get(0).get("id").asString() + "/eligibility-rules", TENANT)
                .get("data")).hasSize(2);

        JsonNode retired = post("/v1/products/" + product + "/retire",
                "{\"retire_date\":\"%s\",\"reason\":\"end of life\"}".formatted(java.time.LocalDate.now(java.time.ZoneOffset.UTC)), 200, MAKER);
        assertThat(retired.get("status").asString()).isEqualTo("RETIRED");
        HttpResponse<String> publish = send("POST", "/v1/product-versions/" + v3 + "/submit", "{}",
                UUID.randomUUID().toString(), TENANT, MAKER, null);
        assertThat(publish.statusCode()).isEqualTo(409);
        assertThat(outbox("product.product.status_changed")).isEqualTo(4);
    }

    @Test
    @Order(13)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/products/" + product, null, null, OTHER_TENANT, MAKER, null).statusCode()).isEqualTo(404);
        assertThat(get("/v1/products", OTHER_TENANT).get("data")).isEmpty();
        HttpResponse<String> submit = send("POST", "/v1/product-versions/" + v3 + "/submit", "{}",
                UUID.randomUUID().toString(), OTHER_TENANT, MAKER, null);
        assertThat(submit.statusCode()).isEqualTo(404);
    }

    @Test
    @Order(99)
    void pastAndFutureChecksUseTheBusinessDate() throws Exception {
        org.mockito.Mockito.when(services.get(org.mockito.ArgumentMatchers.eq("business-day-service"),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(json.readTree("{\"data\":[{\"business_date\":\"2031-01-10\"}]}"));
        businessDates.invalidate();
        try {
            // in the future by the calendar, in the past by the business date
            JsonNode r = post("/v1/products/" + UUID.randomUUID() + "/retire",
                    "{\"retire_date\":\"2031-01-09\",\"reason\":\"x\"}", 422, MAKER);
            assertThat(r.get("code").asString()).isEqualTo("retire_date_in_past");
        } finally {
            businessDates.invalidate();
        }
    }

    // ------------------------------------------------------------------ helpers

    private long outbox(String type) {
        return jdbc.sql("select count(*) from outbox where event_type = ?").param(type).query(Long.class).single();
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), TENANT, user, null);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode patch(String path, String body, int rowVersion) throws Exception {
        HttpResponse<String> r = send("PATCH", path, body, null, TENANT, MAKER, String.valueOf(rowVersion));
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant, MAKER, null);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
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
