package io.meridian.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * insights-service end to end over HTTP against PostgreSQL 18 with row-level security. notification-service
 * (alerts), deposit-service (forecast balances) and card-service (merchant blocks) are stubbed through ServiceClient.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InsightsFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000e1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000e2";
    static final String ALICE = "0199a000-0000-7000-8000-00000000a101";
    static final String BOB = "0199a000-0000-7000-8000-00000000a102";
    static final String ACCOUNT = "0199a000-0000-7000-8000-00000000ac11";
    static final String BOB_ACCOUNT = "0199a000-0000-7000-8000-00000000ac12";
    static final String CARD = "0199a000-0000-7000-8000-00000000cc01";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final AtomicBoolean notificationsDown = new AtomicBoolean(false);
    final List<JsonNode> notifications = Collections.synchronizedList(new ArrayList<>());
    final List<JsonNode> cardControls = Collections.synchronizedList(new ArrayList<>());

    String groceries;
    String shopping;
    String streaming;
    String income;
    String business;
    String uncategorised;
    String tescoEnrichment;
    String budget;
    String netflixSeries;
    String salarySeries;

    @BeforeEach
    void stubOtherServices() {
        when(serviceClient.post(eq("notification-service"), eq("/v1/notifications/send-templated"), any(), anyString()))
                .thenAnswer(inv -> {
                    if (notificationsDown.get()) {
                        throw new ApiException(502, "upstream_error", "notification-service is not reachable");
                    }
                    notifications.add(json.valueToTree(inv.getArgument(2)));
                    return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"QUEUED\"}");
                });
        when(serviceClient.get("deposit-service", "/v1/deposit-accounts/" + ACCOUNT)).thenAnswer(inv ->
                json.readTree("{\"id\":\"" + ACCOUNT + "\",\"customer_id\":\"" + ALICE + "\",\"currency\":\"GBP\"}"));
        when(serviceClient.get("deposit-service", "/v1/deposit-accounts/" + ACCOUNT + "/balance")).thenAnswer(inv ->
                json.readTree("{\"available_balance\":\"300.00\",\"posted_balance\":\"300.00\",\"currency\":\"GBP\"}"));
        when(serviceClient.get("deposit-service", "/v1/deposit-accounts/" + BOB_ACCOUNT)).thenAnswer(inv ->
                json.readTree("{\"id\":\"" + BOB_ACCOUNT + "\",\"customer_id\":\"" + BOB + "\",\"currency\":\"GBP\"}"));
        when(serviceClient.post(eq("card-service"), eq("/v1/card-controls"), any(), anyString())).thenAnswer(inv -> {
            cardControls.add(json.valueToTree(inv.getArgument(2)));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"control_type\":\"MERCHANT_DENY\"}");
        });
    }

    // ------------------------------------------------------------------ taxonomy and merchant rules

    @Test
    @Order(1)
    void categoriesAndMerchantRules() throws Exception {
        shopping = post("/v1/insights/categories", """
                {"code":"shopping","name":"Shopping","level":"GROUP"}""", 201).get("id").asString();
        assertThat(get("/v1/insights/categories/" + shopping).get("code").asString()).isEqualTo("SHOPPING");
        groceries = post("/v1/insights/categories", """
                {"code":"SHOPPING.GROCERIES","name":"Groceries","level":"CATEGORY","parent_id":"%s","mcc_codes":["5411","5499"]}"""
                .formatted(shopping), 201).get("id").asString();
        String ent = post("/v1/insights/categories", """
                {"code":"ENTERTAINMENT","name":"Entertainment","level":"GROUP"}""", 201).get("id").asString();
        streaming = post("/v1/insights/categories", """
                {"code":"ENTERTAINMENT.STREAMING","name":"Streaming","level":"CATEGORY","parent_id":"%s","mcc_codes":["4899"]}"""
                .formatted(ent), 201).get("id").asString();
        income = post("/v1/insights/categories", """
                {"code":"INCOME","name":"Income","level":"GROUP","is_income":true}""", 201).get("id").asString();
        business = post("/v1/insights/categories", """
                {"code":"BUSINESS","name":"Business expenses","level":"GROUP"}""", 201).get("id").asString();
        uncategorised = post("/v1/insights/categories", """
                {"code":"UNCATEGORISED","name":"Uncategorised","level":"GROUP"}""", 201).get("id").asString();

        assertThat(code(post("/v1/insights/categories", """
                {"code":"FOOD.X","name":"X","level":"CATEGORY"}""", 422))).isEqualTo("invalid_category");
        assertThat(code(post("/v1/insights/categories", """
                {"code":"FOOD","name":"X","level":"GROUP","mcc_codes":["54"]}""", 422))).isEqualTo("invalid_category");
        assertThat(code(post("/v1/insights/categories", """
                {"code":"SHOPPING.GROCERIES.FRESH","name":"Fresh","level":"SUBCATEGORY","parent_id":"%s"}""".formatted(shopping), 422)))
                .isEqualTo("invalid_category");
        assertThat(code(post("/v1/insights/categories", """
                {"code":"SHOPPING","name":"Again","level":"GROUP"}""", 409))).isEqualTo("duplicate_code");
        assertThat(code(patch("/v1/insights/categories/" + groceries, "{\"code\":\"OTHER\"}", 1, 422))).isEqualTo("immutable_field");
        JsonNode renamed = patch("/v1/insights/categories/" + groceries, "{\"name\":\"Groceries & food\"}", 1, 200);
        assertThat(renamed.get("row_version").asInt()).isEqualTo(2);

        assertThat(code(post("/v1/insights/merchant-rules", """
                {"match_type":"REGEX","pattern":"([","clean_name":"Broken"}""", 422))).isEqualTo("invalid_merchant_rule");
        post("/v1/insights/merchant-rules", """
                {"match_type":"CONTAINS","pattern":"netflix","clean_name":"Netflix","logo_url":"https://logo/netflix.png",
                 "website":"netflix.com","spend_category_id":"%s","priority":10}""".formatted(streaming), 201);
        JsonNode test = post("/v1/insights/merchant-rules/test", "{\"raw_description\":\"CRV*NETFLIX.COM 8899\"}", 200);
        assertThat(test.get("merchant_clean_name").asString()).isEqualTo("Netflix");
        assertThat(test.get("category_source").asString()).isEqualTo("MERCHANT_RULE");
        assertThat(test.get("category_code").asString()).isEqualTo("ENTERTAINMENT.STREAMING");
        assertThat(test.get("matching_rules")).hasSize(1);
        JsonNode cafe = post("/v1/insights/merchant-rules/test", "{\"raw_description\":\"SQ *JOES CAFE 123\",\"mcc\":\"5814\"}", 200);
        assertThat(cafe.get("merchant_clean_name").asString()).isEqualTo("Joes Cafe");
        assertThat(cafe.get("category_source").asString()).isEqualTo("DEFAULT");
        assertThat(cafe.get("spend_category_id").asString()).isEqualTo(uncategorised);
    }

    // ------------------------------------------------------------------ enrichment, overrides, re-categorisation

    @Test
    @Order(2)
    void enrichmentIsIdempotentPerSourceAndHonoursOverrides() throws Exception {
        String source = UUID.randomUUID().toString();
        String body = """
                {"customer_id":"%s","source_type":"CARD_CLEARING","source_id":"%s","amount":"-45.20","currency":"GBP",
                 "raw_description":"POS TESCO STORES 3012","mcc":"5411","account_ref":"%s",
                 "transaction_at":"%s"}""".formatted(ALICE, source, CARD, ago(3));
        JsonNode e = post("/v1/insights/enrichments/enrich", body, 200);
        tescoEnrichment = e.get("id").asString();
        assertThat(e.get("merchant_clean_name").asString()).isEqualTo("Tesco Stores");
        assertThat(e.get("spend_category_id").asString()).isEqualTo(groceries);
        assertThat(e.get("category_source").asString()).isEqualTo("MCC");
        assertThat(dec(e.get("category_confidence"))).isEqualByComparingTo("0.8");
        assertThat(post("/v1/insights/enrichments/enrich", body, 200).get("id").asString()).isEqualTo(tescoEnrichment);
        assertThat(code(post("/v1/insights/enrichments/enrich", body.replace(ALICE, BOB), 409))).isEqualTo("source_customer_mismatch");
        assertThat(outbox("engagement.transaction.enriched", tescoEnrichment)).isEqualTo(1);
        assertThat(code(post("/v1/insights/enrichments/enrich", """
                {"customer_id":"%s","source_type":"PAYMENT","source_id":"%s","amount":"0","currency":"GBP"}"""
                .formatted(ALICE, UUID.randomUUID()), 422))).isEqualTo("invalid_transaction");
        assertThat(get("/v1/insights/enrichments/current?source_type=CARD_CLEARING&source_id=" + source).get("id").asString())
                .isEqualTo(tescoEnrichment);

        JsonNode salary = post("/v1/insights/enrichments/enrich", enrichBody(ALICE, "+2500.00", "ACME PAYROLL", null, ago(105)), 200);
        assertThat(salary.get("spend_category_id").asString()).isEqualTo(income);
        assertThat(salary.get("category_source").asString()).isEqualTo("INCOME");

        // Customer override rule: Tesco is a business expense, applied to the past.
        assertThat(code(post("/v1/insights/category-rules", """
                {"customer_id":"%s","spend_category_id":"%s"}""".formatted(ALICE, business), 422))).isEqualTo("invalid_category_rule");
        JsonNode rule = post("/v1/insights/category-rules", """
                {"customer_id":"%s","match_merchant_name":"tesco stores","spend_category_id":"%s"}""".formatted(ALICE, business), 201);
        assertThat(rule.get("applied_count").asInt()).isEqualTo(1);
        JsonNode current = get("/v1/insights/enrichments/current?source_type=CARD_CLEARING&source_id=" + source);
        assertThat(current.get("supersedes_id").asString()).isEqualTo(tescoEnrichment);
        assertThat(current.get("category_source").asString()).isEqualTo("OVERRIDE_RULE");
        assertThat(current.get("spend_category_id").asString()).isEqualTo(business);
        JsonNode next = post("/v1/insights/enrichments/enrich", enrichBody(ALICE, "-12.00", "TESCO STORES 3012", "5411", ago(1)), 200);
        assertThat(next.get("category_source").asString()).isEqualTo("OVERRIDE_RULE");
        assertThat(next.get("category_override_rule_id").asString()).isEqualTo(rule.get("id").asString());

        // Re-categorisation by the customer supersedes the current row; the old one can no longer be changed.
        assertThat(code(post("/v1/insights/enrichments/" + tescoEnrichment + "/recategorise",
                "{\"spend_category_id\":\"" + groceries + "\"}", 409))).isEqualTo("enrichment_superseded");
        JsonNode moved = post("/v1/insights/enrichments/" + next.get("id").asString() + "/recategorise",
                "{\"spend_category_id\":\"" + groceries + "\"}", 200);
        assertThat(moved.get("category_source").asString()).isEqualTo("CUSTOMER");
        assertThat(moved.get("enrichment_provider").asString()).isEqualTo("CUSTOMER");
        assertThat(outbox("engagement.transaction.recategorised", moved.get("id").asString())).isEqualTo(1);
        tescoEnrichment = current.get("id").asString();
    }

    // ------------------------------------------------------------------ budgets

    @Test
    @Order(3)
    void budgetThresholdAndExceededAlerts() throws Exception {
        assertThat(code(post("/v1/insights/budgets", """
                {"customer_id":"%s","name":"Bad","amount":"-1","currency":"GBP","starts_on":"%s"}"""
                .formatted(ALICE, today()), 422))).isEqualTo("invalid_budget");
        JsonNode b = post("/v1/insights/budgets", """
                {"customer_id":"%s","name":"Shopping","spend_category_id":"%s","amount":"100.00","currency":"GBP",
                 "starts_on":"%s","alert_threshold_pct":"0.8"}""".formatted(ALICE, shopping, today()), 201);
        budget = b.get("id").asString();
        assertThat(b.get("status").asString()).isEqualTo("ACTIVE");
        int before = notifications.size();
        post("/v1/insights/enrichments/enrich", enrichBody(ALICE, "-50.00", "SAINSBURYS 1234", "5411", null), 200);
        JsonNode p = get("/v1/insights/budgets/" + budget + "/current-period");
        assertThat(dec(p.get("spent_amount"))).isEqualByComparingTo("50");
        assertThat(dec(p.get("remaining_amount"))).isEqualByComparingTo("50");
        assertThat(p.hasNonNull("alert_sent_at")).isFalse();
        post("/v1/insights/enrichments/enrich", enrichBody(ALICE, "-35.00", "SAINSBURYS 1234", "5411", null), 200);
        p = get("/v1/insights/budgets/" + budget + "/current-period");
        assertThat(p.get("transaction_count").asInt()).isEqualTo(2);
        assertThat(p.hasNonNull("alert_sent_at")).isTrue();
        assertThat(p.hasNonNull("exceeded_at")).isFalse();
        assertThat(outbox("engagement.budget.threshold_reached", p.get("id").asString())).isEqualTo(1);
        assertThat(notifications.subList(before, notifications.size())).anySatisfy(n -> {
            assertThat(n.get("template_code").asString()).isEqualTo("INSIGHT_BUDGET_NEAR_LIMIT");
            assertThat(n.get("recipient_id").asString()).isEqualTo(ALICE);
        });
        post("/v1/insights/enrichments/enrich", enrichBody(ALICE, "-20.00", "SAINSBURYS 1234", "5411", null), 200);
        p = get("/v1/insights/budgets/" + budget + "/current-period");
        assertThat(dec(p.get("spent_amount"))).isEqualByComparingTo("105");
        assertThat(dec(p.get("remaining_amount"))).isEqualByComparingTo("-5");
        assertThat(p.hasNonNull("exceeded_at")).isTrue();
        assertThat(outbox("engagement.budget.exceeded", p.get("id").asString())).isEqualTo(1);
        assertThat(outbox("engagement.budget.threshold_reached", p.get("id").asString())).isEqualTo(1);
        JsonNode cards = get("/v1/insights/cards?customer_id=" + ALICE + "&insight_type=BUDGET_EXCEEDED").get("data");
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).get("delivery_status").asString()).isEqualTo("DELIVERED");
        assertThat(cards.get(0).hasNonNull("notification_id")).isTrue();
        assertThat(get("/v1/insights/budgets/" + budget + "/periods").get("data")).hasSize(1);

        assertThat(post("/v1/insights/budgets/" + budget + "/pause", null, 200).get("status").asString()).isEqualTo("PAUSED");
        assertThat(code(post("/v1/insights/budgets/" + budget + "/pause", null, 409))).isEqualTo("invalid_transition");
        assertThat(post("/v1/insights/budgets/" + budget + "/resume", null, 200).get("status").asString()).isEqualTo("ACTIVE");
        JsonNode suggest = post("/v1/insights/budgets/suggest", "{\"customer_id\":\"" + ALICE + "\",\"months\":3}", 200);
        assertThat(suggest.get("months").asInt()).isEqualTo(3);
        assertThat(code(post("/v1/insights/budgets/suggest", "{\"customer_id\":\"" + ALICE + "\",\"months\":13}", 422)))
                .isEqualTo("invalid_months");
    }

    // ------------------------------------------------------------------ recurring payments

    @Test
    @Order(4)
    void recurringDetectionPriceChangeAndCardBlock() throws Exception {
        for (int i = 4; i >= 1; i--) {
            post("/v1/insights/enrichments/enrich", enrichBody(ALICE, i == 1 ? "-12.49" : "-10.99", "CRV*NETFLIX.COM", "4899",
                    ago(i * 30)), 200);
        }
        for (int days : new int[] {75, 45, 15}) {
            post("/v1/insights/enrichments/enrich", enrichBody(ALICE, "+2500.00", "ACME PAYROLL", null, ago(days)), 200);
        }
        JsonNode result = post("/v1/insights/recurring-payments/detect", "{\"customer_id\":\"" + ALICE + "\"}", 200);
        assertThat(result.get("detected_new").asInt()).isEqualTo(2);
        JsonNode netflix = null;
        JsonNode salary = null;
        for (JsonNode s : result.get("series")) {
            if ("Netflix".equals(s.get("merchant_clean_name").asString())) {
                netflix = s;
            } else if ("Acme Payroll".equals(s.get("merchant_clean_name").asString())) {
                salary = s;
            }
        }
        assertThat(netflix).isNotNull();
        assertThat(salary).isNotNull();
        netflixSeries = netflix.get("id").asString();
        salarySeries = salary.get("id").asString();
        assertThat(netflix.get("frequency").asString()).isEqualTo("MONTHLY");
        assertThat(dec(netflix.get("previous_amount"))).isEqualByComparingTo("10.99");
        assertThat(dec(netflix.get("last_amount"))).isEqualByComparingTo("12.49");
        assertThat(netflix.get("spend_category_id").asString()).isEqualTo(streaming);
        assertThat(netflix.get("card_id").asString()).isEqualTo(CARD);
        assertThat(salary.get("is_income").asBoolean()).isTrue();
        assertThat(outbox("engagement.recurring_payment.detected", netflixSeries)).isEqualTo(1);
        assertThat(outbox("engagement.recurring_payment.price_changed", netflixSeries)).isEqualTo(1);
        JsonNode types = get("/v1/insights/cards?customer_id=" + ALICE + "&related_id=" + netflixSeries).get("data");
        assertThat(types).extracting(n -> n.get("insight_type").asString())
                .contains("NEW_SUBSCRIPTION", "SUBSCRIPTION_PRICE_CHANGE");
        assertThat(get("/v1/insights/cards?customer_id=" + ALICE + "&insight_type=SALARY_RECEIVED").get("data")).hasSize(1);
        // Re-detection is idempotent: nothing new.
        assertThat(post("/v1/insights/recurring-payments/detect", "{\"customer_id\":\"" + ALICE + "\"}", 200)
                .get("detected_new").asInt()).isZero();

        // A new charge links to the series.
        JsonNode linked = post("/v1/insights/enrichments/enrich", enrichBody(ALICE, "-12.49", "CRV*NETFLIX.COM", "4899", null), 200);
        assertThat(linked.get("is_recurring").asBoolean()).isTrue();
        assertThat(linked.get("recurring_payment_id").asString()).isEqualTo(netflixSeries);
        assertThat(get("/v1/insights/recurring-payments/" + netflixSeries).get("occurrences").asInt()).isEqualTo(5);

        assertThat(post("/v1/insights/recurring-payments/" + salarySeries + "/confirm", null, 200).get("status").asString())
                .isEqualTo("CONFIRMED");
        assertThat(code(post("/v1/insights/recurring-payments/" + netflixSeries + "/block-card-charges", "{}", 422)))
                .isEqualTo("merchant_ref_required");
        JsonNode blocked = post("/v1/insights/recurring-payments/" + netflixSeries + "/block-card-charges",
                "{\"merchant_ref\":\"NETFLIX-ACQ-01\"}", 200);
        assertThat(blocked.get("status").asString()).isEqualTo("CANCELLED_BY_CUSTOMER");
        assertThat(blocked.hasNonNull("card_control_id")).isTrue();
        assertThat(cardControls.getLast().get("card_id").asString()).isEqualTo(CARD);
        assertThat(cardControls.getLast().get("control_type").asString()).isEqualTo("MERCHANT_DENY");
        assertThat(code(post("/v1/insights/recurring-payments/" + netflixSeries + "/confirm", null, 409))).isEqualTo("invalid_transition");
        assertThat(outbox("engagement.recurring_payment.cancelled", netflixSeries)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ forecasts and insights

    @Test
    @Order(5)
    void forecastLowBalanceAndInsightLifecycle() throws Exception {
        assertThat(code(post("/v1/insights/forecasts", """
                {"customer_id":"%s","deposit_account_id":"%s","horizon_days":5}""".formatted(ALICE, ACCOUNT), 422)))
                .isEqualTo("invalid_horizon");
        assertThat(code(post("/v1/insights/forecasts", """
                {"customer_id":"%s","deposit_account_id":"%s"}""".formatted(ALICE, BOB_ACCOUNT), 422))).isEqualTo("account_not_owned");
        notificationsDown.set(true);
        JsonNode f;
        try {
            f = post("/v1/insights/forecasts", """
                    {"customer_id":"%s","deposit_account_id":"%s","horizon_days":30,"low_balance_threshold":"1000"}"""
                    .formatted(ALICE, ACCOUNT), 201);
        } finally {
            notificationsDown.set(false);
        }
        assertThat(dec(f.get("starting_balance"))).isEqualByComparingTo("300");
        assertThat(f.get("currency").asString()).isEqualTo("GBP");
        assertThat(f.get("projected_points")).hasSize(30);
        assertThat(dec(f.get("expected_income"))).isEqualByComparingTo("2500");
        assertThat(f.get("model_version").asString()).isEqualTo("cff-deterministic-1");
        assertThat(dec(f.get("lowest_balance"))).isLessThan(new java.math.BigDecimal("1000"));
        assertThat(outbox("engagement.cash_flow.low_balance_forecast", f.get("id").asString())).isEqualTo(1);
        JsonNode low = get("/v1/insights/cards?customer_id=" + ALICE + "&insight_type=LOW_BALANCE_FORECAST").get("data");
        assertThat(low).hasSize(1);
        assertThat(low.get(0).get("delivery_status").asString()).isEqualTo("PENDING");

        JsonNode gen = post("/v1/insights/cards/generate", "{\"customer_id\":\"" + ALICE + "\"}", 200);
        JsonNode again = post("/v1/insights/cards/generate", "{\"customer_id\":\"" + ALICE + "\"}", 200);
        assertThat(again.get("generated").asInt()).isZero();
        assertThat(again.get("duplicates_skipped").asInt()).isEqualTo(again.get("evaluated").asInt());
        assertThat(gen.get("evaluated").asInt()).isGreaterThanOrEqualTo(1);

        String card = low.get(0).get("id").asString();
        assertThat(post("/v1/insights/cards/" + card + "/seen", null, 200).get("delivery_status").asString()).isEqualTo("SEEN");
        assertThat(post("/v1/insights/cards/" + card + "/feedback", "{\"feedback_useful\":true}", 200)
                .get("feedback_useful").asBoolean()).isTrue();
        assertThat(post("/v1/insights/cards/" + card + "/dismiss", null, 200).get("delivery_status").asString()).isEqualTo("DISMISSED");
        assertThat(code(post("/v1/insights/cards/" + card + "/seen", null, 409))).isEqualTo("invalid_transition");
    }

    // ------------------------------------------------------------------ analytics, jobs, isolation

    @Test
    @Order(6)
    void spendingSummaries() throws Exception {
        String range = "customer_id=" + ALICE + "&from=" + LocalDate.now(ZoneOffset.UTC).minusDays(200) + "&to=" + today();
        JsonNode byCat = get("/v1/insights/spending/by-category?" + range + "&currency=GBP");
        assertThat(byCat.get("categories")).anySatisfy(c -> {
            assertThat(c.get("category_code").asString()).isEqualTo("SHOPPING.GROCERIES");
            assertThat(dec(c.get("spend"))).isEqualByComparingTo("117.00");
        });
        assertThat(dec(byCat.get("totals").get(0).get("income"))).isEqualByComparingTo("10000.00");
        JsonNode byMerchant = get("/v1/insights/spending/by-merchant?" + range + "&limit=2");
        assertThat(byMerchant.get("merchants")).hasSize(2);
        assertThat(byMerchant.get("merchants").get(0).get("merchant").asString()).isEqualTo("Sainsburys");
        JsonNode trend = get("/v1/insights/spending/trend?customer_id=" + ALICE + "&months=7");
        assertThat(trend.get("series").get(0).get("months")).hasSize(7);
        HttpResponse<String> bad = send("GET", "/v1/insights/spending/by-category?customer_id=" + ALICE + "&from=" + today()
                + "&to=" + LocalDate.now(ZoneOffset.UTC).minusDays(1), null, null, TENANT);
        assertThat(bad.statusCode()).isEqualTo(400);
    }

    @Test
    @Order(7)
    void jobRunsAreIdempotentPerBusinessDate() throws Exception {
        String date = LocalDate.now(ZoneOffset.UTC).plusDays(40).toString();
        JsonNode run = post("/v1/insights/job-runs/insight-expiry?business_date=" + date, null, 200);
        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(run.get("replayed").asBoolean()).isFalse();
        assertThat(run.get("counts").get("expired").asInt()).isPositive();
        JsonNode replay = post("/v1/insights/job-runs/insight-expiry?business_date=" + date, null, 200);
        assertThat(replay.get("replayed").asBoolean()).isTrue();
        assertThat(replay.get("run_id").asString()).isEqualTo(run.get("run_id").asString());
        JsonNode detection = post("/v1/insights/job-runs/recurring-detection?business_date=" + today(), null, 200);
        assertThat(detection.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(detection.get("counts").get("customers").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode rollover = post("/v1/insights/job-runs/budget-rollover?business_date=" + today(), null, 200);
        assertThat(rollover.get("status").asString()).isEqualTo("COMPLETED");
        JsonNode generation = post("/v1/insights/job-runs/insight-generation?business_date=" + today(), null, 200);
        assertThat(generation.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(get("/v1/insights/job-runs?job=INSIGHT_EXPIRY").get("data")).hasSize(1);
        HttpResponse<String> missing = send("POST", "/v1/insights/job-runs/insight-expiry", null, key(), TENANT);
        assertThat(missing.statusCode()).isEqualTo(400);
    }

    @Test
    @Order(8)
    void tenantIsolationAndIdempotentReplay() throws Exception {
        HttpResponse<String> other = send("GET", "/v1/insights/enrichments/" + tescoEnrichment, null, null, OTHER_TENANT);
        assertThat(other.statusCode()).isEqualTo(404);
        HttpResponse<String> budgets = send("GET", "/v1/insights/budgets?customer_id=" + ALICE, null, null, OTHER_TENANT);
        assertThat(json.readTree(budgets.body()).get("data")).isEmpty();
        // The category taxonomy is global.
        HttpResponse<String> cats = send("GET", "/v1/insights/categories/" + groceries, null, null, OTHER_TENANT);
        assertThat(cats.statusCode()).isEqualTo(200);

        String k = key();
        String body = "{\"customer_id\":\"" + ALICE + "\"}";
        HttpResponse<String> first = send("POST", "/v1/insights/recurring-payments/detect", body, k, TENANT);
        HttpResponse<String> second = send("POST", "/v1/insights/recurring-payments/detect", body, k, TENANT);
        assertThat(second.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(second.body())).isEqualTo(json.readTree(first.body()));
        HttpResponse<String> reused = send("POST", "/v1/insights/recurring-payments/detect", "{\"customer_id\":\"" + BOB + "\"}", k, TENANT);
        assertThat(reused.statusCode()).isEqualTo(422);
    }

    // ------------------------------------------------------------------ helpers

    private String enrichBody(String customer, String amount, String raw, String mcc, String at) {
        return """
                {"customer_id":"%s","source_type":"CARD_CLEARING","source_id":"%s","amount":"%s","currency":"GBP",
                 "raw_description":"%s","account_ref":"%s"%s%s}""".formatted(customer, UUID.randomUUID(), amount, raw, CARD,
                mcc == null ? "" : ",\"mcc\":\"" + mcc + "\"", at == null ? "" : ",\"transaction_at\":\"" + at + "\"");
    }

    private static java.math.BigDecimal dec(JsonNode n) {
        return new java.math.BigDecimal(n.asString());
    }

    private static String ago(int days) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusDays(days).toString();
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private String code(JsonNode problem) {
        return problem.path("code").asString();
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, key(), TENANT);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode patch(String path, String body, int version, int expected) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "maker").header("Content-Type", "application/json")
                .header("If-Match", "\"" + version + "\"")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, TENANT);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", "analyst").header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int outbox(String type, String aggregateId) {
        return jdbc.sql("select count(*) from outbox where event_type = ? and aggregate_id = ?::uuid")
                .params(type, aggregateId).query(Integer.class).single();
    }
}
