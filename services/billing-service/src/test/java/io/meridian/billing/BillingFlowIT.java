package io.meridian.billing;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.billing.application.InvoiceService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
 * Billing end to end over HTTP: plans, subscription, metered usage, tiered invoicing, payments, prorated plan
 * change, overdue handling, cancellation and tenant isolation. The subscription starts on the first day of the
 * month three months ago, so billing periods are calendar months fully in the past.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.billing.overdue-sweep-enabled=false"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BillingFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000b111a";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000b111b";
    static final String SUFFIX = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    InvoiceService invoiceService;

    final HttpClient http = HttpClient.newHttpClient();
    final LocalDate today = LocalDate.now(ZoneOffset.UTC);
    final LocalDate p0 = today.withDayOfMonth(1).minusMonths(3);
    final LocalDate p1 = p0.plusMonths(1);

    String basic;
    String pro;
    String subscription;
    String invoice0;
    String invoice1;

    @BeforeAll
    void cleanOutbox() {
        jdbc.sql("delete from outbox").update();
    }

    @Test
    @Order(1)
    void pricePlanLifecycleAndValidation() throws Exception {
        basic = createPlan("BASIC", "USD", "1000.00", """
                {"API_CALL":{"tiers":[{"up_to":1000,"unit_price":"0"},{"up_to":null,"unit_price":"0.01"}]},
                 "PAYMENT":{"unit_price":"0.10"}}""").get("id").asString();
        pro = createPlan("PRO", "USD", "3000.00", "{\"API_CALL\":{\"unit_price\":\"0.005\"}}").get("id").asString();

        JsonNode badTiers = expect(send("POST", "/v1/billing/price-plans", planBody("BAD1", "USD", "1", """
                {"API_CALL":{"tiers":[{"up_to":1000,"unit_price":"0"},{"up_to":500,"unit_price":"1"},{"unit_price":"1"}]}}"""),
                key(), TENANT), 422);
        assertThat(badTiers.get("code").asString()).isEqualTo("invalid_metric_prices");
        JsonNode badMetric = expect(send("POST", "/v1/billing/price-plans",
                planBody("BAD2", "USD", "1", "{\"COFFEE\":{\"unit_price\":\"1\"}}"), key(), TENANT), 422);
        assertThat(badMetric.get("detail").asString()).contains("unknown usage metric");
        JsonNode missing = expect(send("POST", "/v1/billing/price-plans", "{\"code\":\"X\"}", key(), TENANT), 400);
        assertThat(missing.get("code").asString()).isEqualTo("validation_failed");

        JsonNode active = patch("/v1/billing/price-plans/" + basic, "{\"status\":\"ACTIVE\"}", 1, 200);
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        patch("/v1/billing/price-plans/" + pro, "{\"status\":\"ACTIVE\"}", 1, 200);
        JsonNode locked = patch("/v1/billing/price-plans/" + basic, "{\"platform_fee\":\"1.00\"}", 2, 409);
        assertThat(locked.get("code").asString()).isEqualTo("plan_not_editable");

        String retired = createPlan("OLD", "USD", "5.00", "{}").get("id").asString();
        patch("/v1/billing/price-plans/" + retired, "{\"status\":\"RETIRED\"}", 1, 200);
        JsonNode back = patch("/v1/billing/price-plans/" + retired, "{\"status\":\"ACTIVE\"}", 2, 409);
        assertThat(back.get("code").asString()).isEqualTo("invalid_transition");
    }

    @Test
    @Order(2)
    void subscriptionRequiresActivePlanAndIsUnique() throws Exception {
        String draft = createPlan("DRAFT", "USD", "1.00", "{}").get("id").asString();
        String eur = createPlan("EUR", "EUR", "1.00", "{}").get("id").asString();
        patch("/v1/billing/price-plans/" + eur, "{\"status\":\"ACTIVE\"}", 1, 200);

        assertThat(expect(send("POST", "/v1/billing/subscriptions", subBody(draft, "USD"), key(), TENANT), 422)
                .get("code").asString()).isEqualTo("plan_not_active");
        assertThat(expect(send("POST", "/v1/billing/subscriptions", subBody(eur, "USD"), key(), TENANT), 422)
                .get("code").asString()).isEqualTo("currency_mismatch");

        JsonNode sub = expect(send("POST", "/v1/billing/subscriptions", subBody(basic, "USD"), key(), TENANT), 201);
        subscription = sub.get("id").asString();
        assertThat(sub.get("status").asString()).isEqualTo("ACTIVE");

        assertThat(expect(send("POST", "/v1/billing/subscriptions", subBody(basic, "USD"), key(), TENANT), 409)
                .get("code").asString()).isEqualTo("subscription_exists");
        JsonNode immutable = patch("/v1/billing/subscriptions/" + subscription,
                "{\"price_plan_id\":\"" + pro + "\"}", 1, 422);
        assertThat(immutable.get("code").asString()).isEqualTo("immutable_field");
        assertThat(events("platform.tenant_subscription.changed")).isEqualTo(1);
    }

    @Test
    @Order(3)
    void usageIsDedupedAndSummarised() throws Exception {
        usage("u-1", "API_CALL", p0.plusDays(1), 1500);
        usage("u-2", "API_CALL", p0.plusDays(2), 500);
        usage("u-3", "PAYMENT", p0.plusDays(2), 30);
        usage("u-4", "ACTIVE_ACCOUNT", p0.plusDays(1), 120);
        usage("u-5", "ACTIVE_ACCOUNT", p0.plusDays(2), 150);

        HttpResponse<String> replay = send("POST", "/v1/billing/usage", usageBody("API_CALL", p0.plusDays(1), 1500),
                "u-1", TENANT);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(expect(send("POST", "/v1/billing/usage", usageBody("API_CALL", p0, -5), key(), TENANT), 422)
                .get("code").asString()).isEqualTo("invalid_quantity");
        assertThat(expect(send("POST", "/v1/billing/usage", usageBody("API_CALL", today.plusDays(10), 5), key(), TENANT), 422)
                .get("code").asString()).isEqualTo("future_usage");

        JsonNode summary = get("/v1/billing/usage/summary?from=" + p0 + "&to=" + p0.plusMonths(1).minusDays(1), TENANT);
        assertThat(metric(summary, "API_CALL").get("total_quantity").asLong()).isEqualTo(2000);
        assertThat(metric(summary, "ACTIVE_ACCOUNT").get("billable_quantity").asLong()).isEqualTo(150);
        assertThat(metric(summary, "PAYMENT").get("total_quantity").asLong()).isEqualTo(30);
        assertThat(expect(send("GET", "/v1/billing/usage/summary?to=" + p0, null, null, TENANT), 400)
                .get("code").asString()).isEqualTo("missing_parameter");
        assertThat(events("platform.usage_record.recorded")).isEqualTo(5);
    }

    @Test
    @Order(4)
    void generatesTieredInvoiceOncePerPeriod() throws Exception {
        String body = periodBody(p0, p0.plusMonths(1).minusDays(1));
        HttpResponse<String> r = send("POST", "/v1/billing/invoices/generate", body, "gen-0", TENANT);
        JsonNode gen = expect(r, 201);
        assertThat(gen.get("data")).hasSize(1);
        JsonNode inv = gen.get("data").get(0);
        invoice0 = inv.get("id").asString();
        assertThat(inv.get("status").asString()).isEqualTo("DRAFT");
        assertThat(inv.get("invoice_number").asString()).isEqualTo("INV-" + p0.toString().substring(0, 7).replace("-", "") + "-000001");
        // 1000 fee + 50 extra accounts x 0.50 + API 1000 x 0 + 1000 x 0.01 + 30 payments x 0.10 = 1038; -10% = 934.20
        assertThat(new BigDecimal(inv.get("subtotal").asString())).isEqualByComparingTo("934.20");
        assertThat(new BigDecimal(inv.get("total").asString())).isEqualByComparingTo("934.20");

        JsonNode lines = get("/v1/billing/invoices/" + invoice0 + "/lines", TENANT).get("data");
        assertThat(lines).hasSize(6);
        List<String> tiers = new ArrayList<>();
        lines.forEach(l -> {
            if (l.hasNonNull("tier")) {
                tiers.add(l.get("tier").asString());
            }
        });
        assertThat(tiers).contains("tier 1 (1-1000)", "tier 2 (1001+)");

        HttpResponse<String> replay = send("POST", "/v1/billing/invoices/generate", body, "gen-0", TENANT);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(expect(send("POST", "/v1/billing/invoices/generate", body, key(), TENANT), 409)
                .get("code").asString()).isEqualTo("invoice_exists");
        assertThat(expect(send("POST", "/v1/billing/invoices/generate", periodBody(p1, p0), key(), TENANT), 422)
                .get("code").asString()).isEqualTo("invalid_period");
        assertThat(get("/v1/billing/invoices", TENANT).get("data")).hasSize(1);
    }

    @Test
    @Order(5)
    void issueAndPayInvoice() throws Exception {
        String base = "/v1/billing/invoices/" + invoice0;
        assertThat(expect(send("POST", base + "/record-payment", payment("10.00"), key(), TENANT), 409)
                .get("code").asString()).isEqualTo("invoice_not_payable");
        JsonNode issued = post(base + "/issue", null, 200);
        assertThat(issued.get("status").asString()).isEqualTo("ISSUED");
        assertThat(issued.get("due_date").asString()).isEqualTo(today.plusDays(15).toString());
        assertThat(expect(send("POST", base + "/issue", null, key(), TENANT), 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(expect(send("POST", base + "/record-payment", payment("1000.00"), key(), TENANT), 422)
                .get("code").asString()).isEqualTo("overpayment");

        JsonNode partial = expect(send("POST", base + "/record-payment", payment("500.00"), "pay-1", TENANT), 200);
        assertThat(partial.get("status").asString()).isEqualTo("PARTIALLY_PAID");
        HttpResponse<String> replay = send("POST", base + "/record-payment", payment("500.00"), "pay-1", TENANT);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(new BigDecimal(get(base, TENANT).get("amount_paid").asString())).isEqualByComparingTo("500");

        assertThat(expect(send("POST", base + "/void", "{\"reason\":\"oops\"}", key(), TENANT), 409)
                .get("code").asString()).isEqualTo("invoice_has_payments");
        JsonNode paid = post(base + "/record-payment", payment("434.20"), 200);
        assertThat(paid.get("status").asString()).isEqualTo("PAID");
        assertThat(paid.get("paid_at").isNull()).isFalse();
        assertThat(expect(send("POST", base + "/record-payment", payment("1.00"), key(), TENANT), 409)
                .get("code").asString()).isEqualTo("invoice_not_payable");
        assertThat(events("platform.tenant_invoice.issued")).isEqualTo(1);
        assertThat(events("platform.tenant_invoice.paid")).isEqualTo(1);
    }

    @Test
    @Order(6)
    void changePlanProratesIntoNextInvoice() throws Exception {
        String base = "/v1/billing/subscriptions/" + subscription;
        JsonNode invoiced = expect(send("POST", base + "/change-plan", changeBody(pro, p0.plusDays(5)), key(), TENANT), 409);
        assertThat(invoiced.get("code").asString()).isEqualTo("period_already_invoiced");

        LocalDate effective = p1.plusDays(10);
        JsonNode changed = post(base + "/change-plan", changeBody(pro, effective), 200);
        assertThat(changed.get("price_plan_id").asString()).isEqualTo(pro);
        JsonNode adj = changed.get("custom_prices").get("proration_adjustments").get(0);
        long periodDays = ChronoUnit.DAYS.between(p1, p1.plusMonths(1));
        long remaining = ChronoUnit.DAYS.between(effective, p1.plusMonths(1));
        BigDecimal expected = new BigDecimal("2000").multiply(BigDecimal.valueOf(remaining))
                .divide(BigDecimal.valueOf(periodDays), MathContext.DECIMAL128).setScale(2, RoundingMode.HALF_EVEN);
        assertThat(new BigDecimal(adj.get("amount").asString())).isEqualByComparingTo(expected);
        assertThat(expect(send("POST", base + "/change-plan", changeBody(pro, effective.plusDays(1)), key(), TENANT), 422)
                .get("code").asString()).isEqualTo("same_plan");

        JsonNode gen = post("/v1/billing/invoices/generate", periodBody(p1, p1.plusMonths(1).minusDays(1)), 201);
        JsonNode inv = gen.get("data").get(0);
        invoice1 = inv.get("id").asString();
        // BASIC fee 1000 for the period start, -10% discount, plus the undiscounted proration charge
        assertThat(new BigDecimal(inv.get("total").asString())).isEqualByComparingTo(new BigDecimal("900.00").add(expected));
        assertThat(inv.get("invoice_number").asString()).endsWith("-000001");
        boolean hasProration = false;
        for (JsonNode l : get("/v1/billing/invoices/" + invoice1 + "/lines", TENANT).get("data")) {
            hasProration |= l.get("description").asString().startsWith("Proration charge");
        }
        assertThat(hasProration).isTrue();
    }

    @Test
    @Order(7)
    void overdueInvoiceMakesSubscriptionPastDueUntilPaid() throws Exception {
        String base = "/v1/billing/invoices/" + invoice1;
        post(base + "/issue", null, 200);
        int n = TenantContext.callAs(UUID.fromString(TENANT), "system", () -> invoiceService.markOverdue(today.plusDays(60)));
        assertThat(n).isEqualTo(1);
        assertThat(get(base, TENANT).get("status").asString()).isEqualTo("OVERDUE");
        assertThat(get("/v1/billing/subscriptions/" + subscription, TENANT).get("status").asString()).isEqualTo("PAST_DUE");

        String total = get(base, TENANT).get("total").asString();
        assertThat(post(base + "/record-payment", payment(total), 200).get("status").asString()).isEqualTo("PAID");
        assertThat(get("/v1/billing/subscriptions/" + subscription, TENANT).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    @Order(8)
    void voidDraftAndCancelSubscription() throws Exception {
        LocalDate p2 = p1.plusMonths(1);
        JsonNode inv = post("/v1/billing/invoices/generate", periodBody(p2, p2.plusMonths(1).minusDays(1)), 201)
                .get("data").get(0);
        String base = "/v1/billing/invoices/" + inv.get("id").asString();
        assertThat(post(base + "/void", "{\"reason\":\"wrong period\"}", 200).get("status").asString()).isEqualTo("VOID");
        assertThat(expect(send("POST", base + "/issue", null, key(), TENANT), 409).get("code").asString())
                .isEqualTo("invalid_transition");

        String sub = "/v1/billing/subscriptions/" + subscription;
        JsonNode cancelled = post(sub + "/cancel", "{\"reason\":\"moving to competitor\"}", 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("end_date").asString())
                .isEqualTo(today.withDayOfMonth(1).plusMonths(1).minusDays(1).toString());
        assertThat(expect(send("POST", sub + "/cancel", "{\"reason\":\"again\"}", key(), TENANT), 409)
                .get("code").asString()).isEqualTo("invalid_transition");
        assertThat(expect(send("POST", sub + "/change-plan", changeBody(basic, today), key(), TENANT), 409)
                .get("code").asString()).isEqualTo("subscription_cancelled");
        int version = get(sub, TENANT).get("row_version").asInt();
        assertThat(patch(sub, "{\"billing_email\":\"x@example.com\"}", version, 409).get("code").asString())
                .isEqualTo("subscription_cancelled");
    }

    @Test
    @Order(9)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/billing/subscriptions/" + subscription, null, null, OTHER_TENANT).statusCode())
                .isEqualTo(404);
        assertThat(send("GET", "/v1/billing/invoices/" + invoice0 + "/lines", null, null, OTHER_TENANT).statusCode())
                .isEqualTo(404);
        assertThat(get("/v1/billing/invoices", OTHER_TENANT).get("data")).isEmpty();
        assertThat(get("/v1/billing/usage/summary?from=" + p0 + "&to=" + today, OTHER_TENANT).get("metrics")).isEmpty();
        JsonNode none = expect(send("POST", "/v1/billing/invoices/generate", periodBody(p0, p0.plusDays(5)), key(),
                OTHER_TENANT), 422);
        assertThat(none.get("code").asString()).isEqualTo("no_billable_subscription");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode createPlan(String code, String ccy, String fee, String metricPrices) throws Exception {
        JsonNode p = post("/v1/billing/price-plans", planBody(code, ccy, fee, metricPrices), 201);
        assertThat(p.get("status").asString()).isEqualTo("DRAFT");
        return p;
    }

    private String planBody(String code, String ccy, String fee, String metricPrices) {
        return """
                {"code":"%s-%s","name":"%s plan","currency":"%s","platform_fee":"%s","included_active_accounts":100,
                 "price_per_active_account":"0.50","metric_prices":%s,"valid_from":"2020-01-01"}"""
                .formatted(code, SUFFIX, code, ccy, fee, metricPrices);
    }

    private String subBody(String plan, String ccy) {
        return """
                {"price_plan_id":"%s","status":"ACTIVE","start_date":"%s","currency":"%s","discount_rate":"0.1",
                 "billing_email":"billing@tenant.example","payment_terms_days":15}""".formatted(plan, p0, ccy);
    }

    private void usage(String key, String metric, LocalDate day, long qty) throws Exception {
        expect(send("POST", "/v1/billing/usage", usageBody(metric, day, qty), key, TENANT), 201);
    }

    private static String usageBody(String metric, LocalDate day, long qty) {
        return """
                {"metric":"%s","period_date":"%s","quantity":%d,"service_name":"ledger-service"}"""
                .formatted(metric, day, qty);
    }

    private static String periodBody(LocalDate from, LocalDate to) {
        return "{\"period_start\":\"%s\",\"period_end\":\"%s\"}".formatted(from, to);
    }

    private static String changeBody(String plan, LocalDate effective) {
        return "{\"price_plan_id\":\"%s\",\"effective_date\":\"%s\"}".formatted(plan, effective);
    }

    private String payment(String amount) {
        return "{\"amount\":\"%s\",\"paid_at\":\"%sT10:00:00Z\",\"reference\":\"WIRE-1\"}".formatted(amount, today);
    }

    private static JsonNode metric(JsonNode summary, String metric) {
        for (JsonNode m : summary.get("metrics")) {
            if (metric.equals(m.get("metric").asString())) {
                return m;
            }
        }
        throw new AssertionError("no metric " + metric + " in " + summary);
    }

    private long events(String type) {
        return jdbc.sql("select count(*) from outbox where event_type = ? and tenant_id = ?::uuid")
                .params(type, TENANT).query(Long.class).single();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        return expect(send("POST", path, body, key(), TENANT), expected);
    }

    private JsonNode patch(String path, String body, int version, int expected) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("Content-Type", "application/json")
                .header("If-Match", "\"" + version + "\"")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build();
        return expect(http.send(req, HttpResponse.BodyHandlers.ofString()), expected);
    }

    private JsonNode get(String path, String tenant) throws Exception {
        return expect(send("GET", path, null, null, tenant), 200);
    }

    private JsonNode expect(HttpResponse<String> r, int expected) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", "billing-ops").header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
