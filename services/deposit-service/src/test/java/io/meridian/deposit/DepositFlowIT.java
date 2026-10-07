package io.meridian.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.domain.AccountNumbers;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * deposit-service end to end over HTTP against PostgreSQL 18 (RLS enforced) with ledger-service and the product
 * catalog stubbed behind a mocked {@link ServiceClient} ({@link FakeLedger}). A controllable clock moves business
 * time forward; the end-of-day jobs (accrual, product changes, maturities, standing orders, dormancy) are called over
 * HTTP the way business-day-service calls them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.deposit.ledger-id=0199a000-0000-7000-8000-0000000000aa",
        "meridian.deposit.country=GB", "meridian.deposit.withholding-tax-rate=0.20", "meridian.deposit.bank-code=MERI", "meridian.deposit.branch-code=000001",
        "meridian.deposit.gl.interest-expense=0199a000-0000-7000-8000-0000000000e1",
        "meridian.deposit.gl.interest-income=0199a000-0000-7000-8000-0000000000e2",
        "meridian.deposit.gl.fee-income=0199a000-0000-7000-8000-0000000000e3",
        "meridian.deposit.gl.tax-payable=0199a000-0000-7000-8000-0000000000e4",
        "meridian.deposit.gl.clearing=0199a000-0000-7000-8000-0000000000e5"})
@Import({MeridianTestContainers.class, DepositFlowIT.ClockConfig.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DepositFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000d001";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-00000000d002";
    static final String INTEREST_EXPENSE = "0199a000-0000-7000-8000-0000000000e1";
    static final String FEE_INCOME = "0199a000-0000-7000-8000-0000000000e3";
    static final String TAX = "0199a000-0000-7000-8000-0000000000e4";
    static final String CLEARING = "0199a000-0000-7000-8000-0000000000e5";
    static final String CUSTOMER = "0199a000-0000-7000-8000-00000000c001";
    static final String PRODUCT = "0199a000-0000-7000-8000-00000000f001";
    static final String VERSION = "0199a000-0000-7000-8000-00000000f002";
    static final String VERSION_2 = "0199a000-0000-7000-8000-00000000f003";
    /** Catalog terms of VERSION (pinned as product_terms on every account and term deposit opened in the test). */
    static final String RATES = """
            {"data":[
              {"rate_kind":"CREDIT_INTEREST","rate_type":"FIXED","fixed_rate":"0.0365","day_count":"ACT_365","capitalisation_frequency":"MONTHLY"},
              {"rate_kind":"OVERDRAFT_INTEREST","rate_type":"FIXED","fixed_rate":"0.1825","day_count":"ACT_365"}],"has_more":false}""";
    static final String FEES = """
            {"data":[{"code":"MONTHLY_MAINTENANCE","calculation_method":"FLAT","flat_amount":"5.00","tax_rate":"0.20"},
                     {"code":"ACCOUNT_CLOSURE","calculation_method":"FLAT","flat_amount":"10.00"}],"has_more":false}""";

    /** A clock tests can move forward. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advanceDays(int days) {
            now = now.plus(Duration.ofDays(days));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-05T10:00:00Z"));

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakeLedger ledger;
    String a;
    UUID aLedger;
    String b;
    UUID bLedger;

    @BeforeAll
    void createLedger() {
        ledger = new FakeLedger(json, CLOCK);
    }

    /** Mockito beans are reset after every test, so the stubs are re-attached to the same fake each time. */
    @org.junit.jupiter.api.BeforeEach
    void stubServiceClient() {
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> ledger.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> {
            String service = i.getArgument(0);
            String path = i.getArgument(1);
            if ("tenant-service".equals(service) && path.startsWith("/v1/legal-entities/")) {
                // a GB legal entity without numbering settings: the deployment's GB bank / sort code apply
                return json.readTree("{\"id\":\"" + path.substring(19) + "\",\"code\":\"MAIN\",\"country\":\"GB\"}");
            }
            return "product-catalog-service".equals(service) ? catalog(path) : ledger.get(service, path);
        });
    }

    private JsonNode catalog(String path) {
        if (path.equals("/v1/product-versions/" + VERSION)) {
            return json.readTree("{\"id\":\"" + VERSION + "\",\"product_id\":\"" + PRODUCT + "\",\"status\":\"PUBLISHED\"}");
        }
        if (path.startsWith("/v1/product-versions/" + VERSION + "/interest-rates")) {
            return json.readTree(RATES);
        }
        if (path.startsWith("/v1/product-versions/" + VERSION + "/fees")) {
            return json.readTree(FEES);
        }
        if (path.equals("/v1/product-versions/" + VERSION_2)) {
            return json.readTree("{\"id\":\"" + VERSION_2 + "\",\"product_id\":\"" + PRODUCT + "\",\"status\":\"PUBLISHED\"}");
        }
        if (path.startsWith("/v1/product-versions/" + VERSION_2 + "/interest-rates")) {
            return json.readTree("""
                    {"data":[{"id":"0199a000-0000-7000-8000-00000000f010","rate_kind":"CREDIT_INTEREST","rate_type":"TIERED",
                      "day_count":"ACT_360","currency":"EUR"}],"has_more":false}""");
        }
        if (path.startsWith("/v1/interest-rate-configs/0199a000-0000-7000-8000-00000000f010/tiers")) {
            return json.readTree("""
                    {"data":[{"tier_no":1,"from_amount":"0","to_amount":"10000","rate":"0.01"},
                             {"tier_no":2,"from_amount":"10000","rate":"0.02"}],"has_more":false}""");
        }
        if (path.startsWith("/v1/product-versions/" + VERSION_2 + "/fees")) {
            return json.readTree("{\"data\":[],\"has_more\":false}");
        }
        throw new io.meridian.common.error.ApiException(404, "not_found", path);
    }

    // ------------------------------------------------------------------ 1. open / approve / activate

    @Test
    @Order(1)
    void openApproveActivateWithFourEyesAndIdempotency() throws Exception {
        JsonNode opened = post("/v1/deposit-accounts", accountBody(), "maker", 201);
        a = opened.get("id").asString();
        assertThat(opened.get("product_terms").get("interest_rates")).hasSize(2);
        assertThat(opened.get("product_terms").get("fees").get(0).get("code").asString()).isEqualTo("MONTHLY_MAINTENANCE");
        assertThat(opened.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        String number = opened.get("account_number").asString();
        assertThat(number).hasSize(8);
        assertThat(AccountNumbers.isValidLuhn(number)).isTrue();
        assertThat(opened.get("iban").asString()).startsWith("GB").hasSize(22);
        assertThat(AccountNumbers.isValidIban(opened.get("iban").asString())).isTrue();
        assertThat(get("/v1/deposit-accounts/" + a + "/holders").get("data")).hasSize(1);

        assertThat(post("/v1/deposit-accounts/" + a + "/activate", null, "checker", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(post("/v1/deposit-accounts/" + a + "/approve", "{\"comment\":\"ok\"}", "maker", 422)
                .get("code").asString()).isEqualTo("four_eyes_violation");

        HttpResponse<String> approved = send("POST", "/v1/deposit-accounts/" + a + "/approve", "{\"comment\":\"KYC ok\"}",
                "approve-a", "checker", TENANT);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        JsonNode acc = json.readTree(approved.body());
        assertThat(acc.get("status").asString()).isEqualTo("PENDING_FUNDING");
        aLedger = UUID.fromString(acc.get("ledger_account_id").asString());
        FakeLedger.Call open = ledger.calls("POST", "/v1/ledger-accounts").getFirst();
        assertThat(open.key()).isEqualTo("open-ledger-account:" + a);
        assertThat(open.body().get("account_type").asString()).isEqualTo("LIABILITY");
        assertThat(open.body().get("normal_balance").asString()).isEqualTo("CREDIT");
        assertThat(open.body().get("currency").asString()).isEqualTo("EUR");
        assertThat(open.body().get("code").asString()).isEqualTo(number);

        HttpResponse<String> replay = send("POST", "/v1/deposit-accounts/" + a + "/approve", "{\"comment\":\"KYC ok\"}",
                "approve-a", "checker", TENANT);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(ledger.calls("POST", "/v1/ledger-accounts")).hasSize(1);
        HttpResponse<String> reused = send("POST", "/v1/deposit-accounts/" + a + "/approve", "{\"comment\":\"other\"}",
                "approve-a", "checker", TENANT);
        assertThat(reused.statusCode()).isEqualTo(422);

        JsonNode active = post("/v1/deposit-accounts/" + a + "/activate", null, "checker", 200);
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(active.get("opened_at").isNull()).isFalse();
        assertThat(get("/v1/deposit-accounts/" + a + "/status-history").get("data")).hasSize(3);
        assertThat(events("accounts.deposit_account.opened", a)).isEqualTo(1);
        assertThat(events("accounts.deposit_account.activated", a)).isEqualTo(1);

        b = openActive("B");
        bLedger = UUID.fromString(get("/v1/deposit-accounts/" + b).get("ledger_account_id").asString());
    }

    @Test
    @Order(2)
    void balanceAndTransactionsComeFromTheLedger() throws Exception {
        ledger.credit(aLedger, "1000.00", null);
        JsonNode bal = get("/v1/deposit-accounts/" + a + "/balance");
        assertThat(dec(bal, "posted_balance")).isEqualByComparingTo("1000.00");
        assertThat(get("/v1/deposit-accounts/" + a + "/transactions").get("data")).hasSize(1);
        assertThat(get("/v1/deposit-accounts/" + a + "/transactions?from=2026-10-05T00:00:00Z&to=2026-10-06T00:00:00Z")
                .get("data")).hasSize(1);
        assertThat(get("/v1/deposit-accounts/" + a + "/transactions?from=2026-10-06T00:00:00Z").get("data")).isEmpty();
        assertThat(post("/v1/deposit-accounts", accountBody().replace("\"EUR\"", "\"EUX\""), "maker", 422)
                .get("code").asString()).isEqualTo("invalid_currency");
        HttpResponse<String> noKey = send("POST", "/v1/deposit-accounts", accountBody(), null, "maker", TENANT);
        assertThat(noKey.statusCode()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ 2. restrictions

    @Test
    @Order(3)
    void freezeAndUnfreezeDriveTheLedgerRestriction() throws Exception {
        assertThat(post("/v1/deposit-accounts/" + a + "/freeze", "{\"reason\":\"fraud check\"}", "ops", 200)
                .get("status").asString()).isEqualTo("FROZEN");
        assertThat(ledger.statusOf(aLedger)).isEqualTo("FROZEN");
        assertThat(post("/v1/deposit-accounts/" + a + "/freeze", "{\"reason\":\"again\"}", "ops", 409)
                .get("code").asString()).isEqualTo("already_frozen");
        assertThat(post("/v1/deposit-accounts/" + a + "/unfreeze", "{\"reason\":\"cleared\"}", "ops", 200)
                .get("status").asString()).isEqualTo("ACTIVE");
        assertThat(ledger.statusOf(aLedger)).isEqualTo("ACTIVE");
        assertThat(post("/v1/deposit-accounts/" + a + "/unfreeze", "{\"reason\":\"x\"}", "ops", 409)
                .get("code").asString()).isEqualTo("not_frozen");
    }

    @Test
    @Order(4)
    void blocksPlaceHoldsAndRestrictions() throws Exception {
        String legal = """
                {"deposit_account_id":"%s","block_type":"GARNISHMENT","scope":"DEBIT","reason":"court order"}""".formatted(a);
        assertThat(post("/v1/account-blocks", legal, "ops", 422).get("code").asString()).isEqualTo("reference_required");
        JsonNode debit = post("/v1/account-blocks", legal.replace("}", ",\"reference\":\"CASE-1\"}"), "ops", 201);
        assertThat(get("/v1/deposit-accounts/" + a).get("status").asString()).isEqualTo("BLOCKED_DEBIT");
        assertThat(ledger.statusOf(aLedger)).isEqualTo("FROZEN_DEBIT");

        JsonNode amount = post("/v1/account-blocks", """
                {"deposit_account_id":"%s","block_type":"LIEN","scope":"AMOUNT","amount":"200.00","reason":"lien"}"""
                .formatted(a), "ops", 201);
        assertThat(amount.get("hold_id").isNull()).isFalse();
        assertThat(ledger.pendingOf(aLedger)).isEqualByComparingTo("200.00");
        FakeLedger.Call hold = ledger.calls("POST", "/v1/holds").getLast();
        assertThat(hold.body().get("hold_type").asString()).isEqualTo("LEGAL");
        assertThat(hold.key()).startsWith("block-hold:" + a + ":");

        post("/v1/account-blocks/" + debit.get("id").asString() + "/lift", "{\"reason\":\"released by court\"}", "ops", 200);
        assertThat(get("/v1/deposit-accounts/" + a).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(ledger.statusOf(aLedger)).isEqualTo("ACTIVE");
        JsonNode lifted = post("/v1/account-blocks/" + amount.get("id").asString() + "/lift", "{\"reason\":\"paid\"}", "ops", 200);
        assertThat(lifted.get("status").asString()).isEqualTo("LIFTED");
        assertThat(ledger.pendingOf(aLedger)).isZero();
        assertThat(post("/v1/account-blocks/" + amount.get("id").asString() + "/lift", "{\"reason\":\"x\"}", "ops", 409)
                .get("code").asString()).isEqualTo("invalid_transition");
        assertThat(events("accounts.account_block.placed", null)).isEqualTo(2);
        assertThat(events("accounts.account_block.lifted", null)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 3. fees

    @Test
    @Order(5)
    void feesChargeAndRefundWithTax() throws Exception {
        HttpResponse<String> r = send("POST", "/v1/deposit-accounts/" + a + "/charge-fee",
                "{\"fee_code\":\"MONTHLY_MAINTENANCE\",\"reason\":\"October maintenance\"}", "fee-oct", "ops", TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode fee = json.readTree(r.body());
        assertThat(dec(fee, "amount")).isEqualByComparingTo("5.00");
        assertThat(dec(fee, "tax_amount")).isEqualByComparingTo("1.00");
        FakeLedger.Call posting = ledger.lastPosting();
        assertThat(posting.key()).isEqualTo("fee:" + a + ":MONTHLY_MAINTENANCE:fee-oct");
        assertThat(posting.body().get("entry_type").asString()).isEqualTo("FEE");
        assertLeg(posting, aLedger.toString(), "DEBIT", "6.00");
        assertLeg(posting, FEE_INCOME, "CREDIT", "5.00");
        assertLeg(posting, TAX, "CREDIT", "1.00");
        assertThat(ledger.balanceOf(aLedger)).isEqualByComparingTo("994.00");

        String feeId = fee.get("id").asString();
        JsonNode partial = post("/v1/fee-charges/" + feeId + "/refund", "{\"amount\":\"2.00\",\"reason\":\"goodwill\"}", "ops", 200);
        assertThat(dec(partial, "amount")).isEqualByComparingTo("-2.00");
        assertThat(dec(partial, "tax_amount")).isEqualByComparingTo("-0.40");
        JsonNode rest = post("/v1/fee-charges/" + feeId + "/refund", "{\"reason\":\"waived\"}", "ops", 200);
        assertThat(dec(rest, "amount")).isEqualByComparingTo("-3.00");
        assertThat(dec(rest, "tax_amount")).isEqualByComparingTo("-0.60");
        assertThat(post("/v1/fee-charges/" + feeId + "/refund", "{\"reason\":\"again\"}", "ops", 409).get("code").asString())
                .isEqualTo("already_refunded");
        assertThat(ledger.balanceOf(aLedger)).isEqualByComparingTo("1000.00");

        JsonNode manual = post("/v1/deposit-accounts/" + a + "/charge-fee",
                "{\"fee_code\":\"COURIER\",\"amount\":\"2.50\",\"reason\":\"card courier\"}", "ops", 200);
        post("/v1/fee-charges/" + manual.get("id").asString() + "/refund", "{\"reason\":\"error\"}", "ops", 200);
        assertThat(ledger.calls("POST", "/v1/journal-entries/").stream().anyMatch(c -> c.path().endsWith("/reverse"))).isTrue();
        assertThat(ledger.balanceOf(aLedger)).isEqualByComparingTo("1000.00");
        assertThat(post("/v1/deposit-accounts/" + a + "/charge-fee", "{\"fee_code\":\"UNKNOWN\",\"reason\":\"x\"}", "ops", 422)
                .get("code").asString()).isEqualTo("fee_not_defined");
        assertThat(get("/v1/deposit-accounts/" + a + "/fees").get("data")).hasSize(5);
        assertThat(events("accounts.fee.charged", null)).isEqualTo(2);
        assertThat(events("accounts.fee.refunded", null)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ 4. interest

    @Test
    @Order(6)
    void dailyAccrualCapitalisationAndBackValuedRecalculation() throws Exception {
        CLOCK.advanceDays(31); // 2026-11-05
        JsonNode acc = post("/v1/deposit-accounts/" + a + "/capitalise-interest", null, "ops", 200);
        assertThat(get("/v1/deposit-accounts/" + a + "/accruals?limit=200").get("data")).hasSize(31);
        JsonNode cap = get("/v1/deposit-accounts/" + a + "/capitalisations").get("data").get(0);
        assertThat(dec(cap, "gross_amount")).isEqualByComparingTo("3.10");
        assertThat(dec(cap, "tax_amount")).isEqualByComparingTo("0.62");
        assertThat(dec(cap, "net_amount")).isEqualByComparingTo("2.48");
        assertThat(cap.get("period_start").asString()).isEqualTo("2026-10-05");
        assertThat(cap.get("period_end").asString()).isEqualTo("2026-11-04");
        FakeLedger.Call posting = ledger.lastPosting();
        assertThat(posting.key()).isEqualTo("capitalise-credit:" + a + ":2026-11-04");
        assertThat(posting.body().get("entry_type").asString()).isEqualTo("INTEREST");
        assertLeg(posting, INTEREST_EXPENSE, "DEBIT", "3.10");
        assertLeg(posting, aLedger.toString(), "CREDIT", "2.48");
        assertLeg(posting, TAX, "CREDIT", "0.62");
        assertThat(dec(acc, "accrued_credit_interest")).isZero();
        assertThat(dec(acc, "interest_paid_ytd")).isEqualByComparingTo("3.10");
        assertThat(dec(acc, "tax_withheld_ytd")).isEqualByComparingTo("0.62");
        assertThat(post("/v1/deposit-accounts/" + a + "/capitalise-interest", null, "ops", 422).get("code").asString())
                .isEqualTo("nothing_to_capitalise");
        assertThat(events("accounts.interest.capitalised", null)).isEqualTo(1);

        assertThat(get("/v1/deposit-accounts/" + a + "/accruals?from=2026-10-10&to=2026-10-19&kind=CREDIT&limit=200")
                .get("data")).hasSize(10);
        assertThat(send("GET", "/v1/deposit-accounts/" + a + "/accruals?kind=NOPE", null, null, "ops", TENANT).statusCode())
                .isEqualTo(400);

        CLOCK.advanceDays(10); // 2026-11-15; business-day-service closes 14 Nov: accrual for 5..14 Nov
        JsonNode eod = post("/v1/deposit-accounts/eod/accrue?business_date=2026-11-14", null, "system:business-day", 200);
        assertThat(eod.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(eod.get("replayed").asBoolean()).isFalse();
        assertThat(eod.get("counts").get("accounts").asInt()).isEqualTo(2);
        assertThat(eod.get("counts").get("accrual_rows").asInt()).isPositive();
        assertThat(dec(get("/v1/deposit-accounts/" + a), "accrued_credit_interest")).isEqualByComparingTo("1.00248");
        JsonNode again = post("/v1/deposit-accounts/eod/accrue?business_date=2026-11-14", null, "system:business-day", 200);
        assertThat(again.get("replayed").asBoolean()).isTrue();
        assertThat(again.get("run_id").asString()).isEqualTo(eod.get("run_id").asString());
        assertThat(dec(get("/v1/deposit-accounts/" + a), "accrued_credit_interest")).isEqualByComparingTo("1.00248");
        JsonNode runs = get("/v1/deposit-eod-runs?job=INTEREST_ACCRUAL&business_date=2026-11-14").get("data");
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).get("attempts").asInt()).isEqualTo(1);
        assertThat(send("POST", "/v1/deposit-accounts/eod/accrue", null, UUID.randomUUID().toString(), "ops", TENANT)
                .statusCode()).isEqualTo(400);
        ledger.credit(aLedger, "500.00", OffsetDateTime.parse("2026-11-10T09:00:00Z")); // back-valued
        assertThat(post("/v1/deposit-accounts/" + a + "/recalculate-interest",
                "{\"from_date\":\"2026-10-20\",\"reason\":\"late credit\"}", "ops", 422).get("code").asString())
                .isEqualTo("period_already_capitalised");
        JsonNode recalc = post("/v1/deposit-accounts/" + a + "/recalculate-interest",
                "{\"from_date\":\"2026-11-05\",\"reason\":\"back-valued credit\"}", "ops", 200);
        assertThat(dec(recalc, "accrued_credit_interest")).isEqualByComparingTo("1.25248");
        Integer corrections = asTenant(() -> jdbc.sql("""
                select count(*) from interest_accrual where deposit_account_id = ?::uuid and is_backdated_recalc
                  and supersedes_id is not null""").param(a).query(Integer.class).single());
        assertThat(corrections).isEqualTo(5);

        HttpResponse<String> projection = send("GET", "/v1/deposit-accounts/" + a + "/interest-projection?days=30", null,
                null, "ops", TENANT);
        assertThat(projection.statusCode()).as(projection.body()).isEqualTo(200);
        assertThat(projection.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        JsonNode proj = json.readTree(projection.body());
        assertThat(dec(proj, "balance")).isEqualByComparingTo("1502.48");
        assertThat(dec(proj, "projected_credit_interest")).isEqualByComparingTo("4.51");
        assertThat(dec(proj, "projected_tax")).isEqualByComparingTo("0.90");
    }

    // ------------------------------------------------------------------ 5. overdraft

    @Test
    @Order(7)
    void overdraftFacilityApprovalLimitsAndCancellation() throws Exception {
        String body = """
                {"deposit_account_id":"%s","overdraft_type":"ARRANGED","limit_amount":"500.00","currency":"EUR",
                 "interest_rate":"0.15","starts_on":"2026-11-15"}""".formatted(a);
        JsonNode od = post("/v1/overdraft-facilities", body, "maker", 201);
        assertThat(post("/v1/overdraft-facilities", body, "maker", 409).get("code").asString()).isEqualTo("facility_exists");
        String id = od.get("id").asString();
        assertThat(post("/v1/overdraft-facilities/" + id + "/approve", "{}", "maker", 422).get("code").asString())
                .isEqualTo("four_eyes_violation");
        JsonNode approved = post("/v1/overdraft-facilities/" + id + "/approve", "{\"comment\":\"score 720\"}", "credit-officer", 200);
        assertThat(approved.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(approved.get("approved_by").asString()).isEqualTo("credit-officer");
        String limitPath = "/v1/ledger-accounts/" + aLedger + "/overdraft-limit";
        FakeLedger.Call setLimit = ledger.calls("POST", limitPath).getLast();
        assertThat(new BigDecimal(setLimit.body().get("limit").asString())).isEqualByComparingTo("500.00");
        assertThat(setLimit.key()).isEqualTo("overdraft-limit:" + id + ":approve:1");
        assertThat(ledger.calls("PATCH", "/v1/ledger-accounts/")).isEmpty();
        assertThat(dec(get("/v1/deposit-accounts/" + a), "overdraft_limit")).isEqualByComparingTo("500");
        assertThat(post("/v1/overdraft-facilities/" + id + "/change-limit", "{\"limit_amount\":\"300.00\",\"reason\":\"review\"}",
                "credit-officer", 200).get("limit_amount").asString()).startsWith("300");
        // SEC-12: raising an approved limit is not a single-user action
        assertThat(post("/v1/overdraft-facilities/" + id + "/change-limit", "{\"limit_amount\":\"5000.00\",\"reason\":\"more\"}",
                "credit-officer", 422).get("code").asString()).isEqualTo("limit_increase_requires_approval");
        assertThat(post("/v1/overdraft-facilities/" + id + "/suspend", "{\"reason\":\"arrears\"}", "ops", 200)
                .get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(ledger.calls("POST", limitPath).getLast().body().get("limit").asString()).isEqualTo("0");
        assertThat(ledger.calls("POST", limitPath)).hasSize(3);
        assertThat(post("/v1/overdraft-facilities/" + id + "/cancel", "{\"reason\":\"closed\"}", "ops", 200)
                .get("status").asString()).isEqualTo("CANCELLED");
        assertThat(post("/v1/overdraft-facilities/" + id + "/approve", "{}", "credit-officer", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(events("accounts.overdraft_facility.approved", null)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 6. pots

    @Test
    @Order(8)
    void savingsPotLifecycle() throws Exception {
        JsonNode pot = post("/v1/savings-pots", """
                {"deposit_account_id":"%s","name":"Holiday","currency":"EUR","goal_amount":"100.00"}""".formatted(a),
                "customer", 201);
        String id = pot.get("id").asString();
        UUID potLedger = UUID.fromString(pot.get("ledger_account_id").asString());
        FakeLedger.Call open = ledger.calls("POST", "/v1/ledger-accounts").getLast();
        assertThat(open.body().get("parent_id").asString()).isEqualTo(aLedger.toString());
        post("/v1/savings-pots/" + id + "/deposit", "{\"amount\":\"60.00\"}", "customer", 200);
        JsonNode reached = post("/v1/savings-pots/" + id + "/deposit", "{\"amount\":\"50.00\"}", "customer", 200);
        assertThat(reached.get("status").asString()).isEqualTo("GOAL_REACHED");
        assertThat(ledger.balanceOf(potLedger)).isEqualByComparingTo("110.00");
        assertThat(events("accounts.savings_pot.goal_reached", id)).isEqualTo(1);
        assertThat(post("/v1/savings-pots/" + id + "/withdraw", "{\"amount\":\"500.00\"}", "customer", 422)
                .get("code").asString()).isEqualTo("insufficient_funds");
        assertThat(post("/v1/savings-pots/" + id + "/withdraw", "{\"amount\":\"20.00\"}", "customer", 200)
                .get("status").asString()).isEqualTo("ACTIVE");
        post("/v1/savings-pot-rules", """
                {"savings_pot_id":"%s","rule_type":"FIXED_SCHEDULE","amount":"10.00","frequency":"WEEKLY"}""".formatted(id),
                "customer", 201);
        assertThat(post("/v1/savings-pot-rules", """
                {"savings_pot_id":"%s","rule_type":"PERCENT_OF_INCOME","percentage":"1.5"}""".formatted(id), "customer", 422)
                .get("code").asString()).isEqualTo("invalid_percentage");
        assertThat(get("/v1/savings-pots/" + id + "/rules").get("data")).hasSize(1);
        JsonNode closed = post("/v1/savings-pots/" + id + "/close", null, "customer", 200);
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(ledger.balanceOf(potLedger)).isZero();
        assertThat(ledger.statusOf(potLedger)).isEqualTo("CLOSED");
        assertThat(ledger.balanceOf(aLedger)).isEqualByComparingTo("1502.48");
        assertThat(post("/v1/savings-pots/" + id + "/deposit", "{\"amount\":\"1.00\"}", "customer", 409)
                .get("code").asString()).isEqualTo("invalid_transition");
    }

    // ------------------------------------------------------------------ 7. term deposits

    @Test
    @Order(9)
    void termDepositPlacementLienAndBreak() throws Exception {
        LocalDate today = LocalDate.now(CLOCK);
        LocalDate maturity = businessDay(today.plusDays(30));
        assertThat(post("/v1/term-deposits", tdBody(today, maturity.plusDays(1), "500.00", "PAY_OUT_ALL"), "maker", 422)
                .get("code").asString()).isEqualTo("invalid_maturity_date");
        JsonNode td = post("/v1/term-deposits", tdBody(today, maturity, "500.00", "PAY_OUT_ALL"), "maker", 201);
        String id = td.get("id").asString();
        assertThat(td.get("status").asString()).isEqualTo("PENDING_FUNDING");
        assertThat(td.get("deposit_number").asString()).startsWith("TD").hasSize(14);
        assertThat(td.get("product_terms").get("interest_rates")).hasSize(2);
        assertThat(post("/v1/term-deposits/" + id + "/break", "{\"reason\":\"x\"}", "ops", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        JsonNode funded = post("/v1/term-deposits/" + id + "/fund", null, "ops", 200);
        assertThat(funded.get("status").asString()).isEqualTo("ACTIVE");
        UUID tdLedger = UUID.fromString(funded.get("ledger_account_id").asString());
        FakeLedger.Call fund = ledger.lastPosting();
        assertThat(fund.key()).isEqualTo("td-fund:" + id);
        assertLeg(fund, aLedger.toString(), "DEBIT", "500.00");
        assertLeg(fund, tdLedger.toString(), "CREDIT", "500.00");
        assertThat(events("accounts.term_deposit.placed", id)).isEqualTo(1);

        HttpResponse<String> quote = send("GET", "/v1/term-deposits/" + id + "/break-quote", null, null, "ops", TENANT);
        assertThat(quote.statusCode()).isEqualTo(200);
        assertThat(quote.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(dec(json.readTree(quote.body()), "payout")).isEqualByComparingTo("500.00");

        post("/v1/term-deposits/" + id + "/mark-lien", "{\"reference\":\"LOAN-9\",\"amount\":\"400.00\"}", "ops", 200);
        assertThat(ledger.pendingOf(tdLedger)).isEqualByComparingTo("400.00");
        assertThat(post("/v1/term-deposits/" + id + "/mark-lien", "{\"reference\":\"LOAN-10\",\"amount\":\"1.00\"}", "ops", 409)
                .get("code").asString()).isEqualTo("lien_exists");
        assertThat(post("/v1/term-deposits/" + id + "/break", "{\"reason\":\"need cash\"}", "ops", 422).get("code").asString())
                .isEqualTo("deposit_under_lien");
        assertThat(post("/v1/term-deposits/" + id + "/release-lien", "{\"reference\":\"WRONG\"}", "ops", 422)
                .get("code").asString()).isEqualTo("lien_reference_mismatch");
        post("/v1/term-deposits/" + id + "/release-lien", "{\"reference\":\"LOAN-9\"}", "ops", 200);
        assertThat(ledger.pendingOf(tdLedger)).isZero();

        HttpResponse<String> cert = send("GET", "/v1/term-deposits/" + id + "/certificate", null, null, "ops", TENANT);
        assertThat(cert.statusCode()).isEqualTo(200);
        assertThat(cert.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
        assertThat(cert.body()).contains("TERM DEPOSIT CERTIFICATE").contains("500.00 EUR");

        JsonNode broken = post("/v1/term-deposits/" + id + "/break", "{\"reason\":\"need cash\"}", "ops", 200);
        assertThat(broken.get("status").asString()).isEqualTo("BROKEN");
        assertThat(ledger.balanceOf(tdLedger)).isZero();
        assertThat(ledger.statusOf(tdLedger)).isEqualTo("CLOSED");
        assertThat(get("/v1/term-deposits/" + id + "/events").get("data")).hasSize(5);
        assertThat(events("accounts.term_deposit.broken", id)).isEqualTo(1);
    }

    @Test
    @Order(10)
    void termDepositMaturityPayOutAndRollover() throws Exception {
        LocalDate today = LocalDate.now(CLOCK);
        LocalDate maturity = businessDay(today.plusDays(30));
        String payOut = post("/v1/term-deposits", tdBody(today, maturity, "300.00", "PAY_OUT_ALL")
                .replace("\"payout_account_id\":\"" + a + "\"", "\"payout_account_id\":\"" + b + "\""), "maker", 201)
                .get("id").asString();
        String roll = post("/v1/term-deposits", tdBody(today, maturity, "200.00", "ROLL_PRINCIPAL_AND_INTEREST"), "maker", 201)
                .get("id").asString();
        post("/v1/term-deposits/" + payOut + "/fund", null, "ops", 200);
        post("/v1/term-deposits/" + roll + "/fund", null, "ops", 200);
        assertThat(post("/v1/term-deposits/" + payOut + "/change-rollover",
                "{\"rollover_instruction\":\"PAY_OUT_ALL\",\"payout_account_id\":\"" + b + "\"}", "ops", 200)
                .get("rollover_instruction").asString()).isEqualTo("PAY_OUT_ALL");
        BigDecimal bBefore = ledger.balanceOf(bLedger);
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, maturity);
        BigDecimal gross300 = new BigDecimal("300").multiply(new BigDecimal("0.0365")).multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(365), 2, java.math.RoundingMode.HALF_EVEN);
        BigDecimal tax300 = gross300.multiply(new BigDecimal("0.20")).setScale(2, java.math.RoundingMode.HALF_EVEN);

        CLOCK.advanceDays((int) days);
        JsonNode matured = post("/v1/deposit-accounts/eod/maturities?business_date=" + LocalDate.now(CLOCK), null,
                "system:business-day", 200);
        assertThat(matured.get("counts").get("maturities").asInt()).isEqualTo(2);
        assertThat(matured.get("error_count").asInt()).isZero();
        JsonNode p = get("/v1/term-deposits/" + payOut);
        assertThat(p.get("status").asString()).isEqualTo("MATURED");
        assertThat(ledger.balanceOf(bLedger).subtract(bBefore))
                .isEqualByComparingTo(new BigDecimal("300.00").add(gross300).subtract(tax300));
        assertThat(ledger.statusOf(UUID.fromString(p.get("ledger_account_id").asString()))).isEqualTo("CLOSED");

        JsonNode r = get("/v1/term-deposits/" + roll);
        assertThat(r.get("status").asString()).isEqualTo("ROLLED_OVER");
        JsonNode next = get("/v1/term-deposits?status=ACTIVE").get("data").get(0);
        assertThat(next.get("previous_deposit_id").asString()).isEqualTo(roll);
        assertThat(next.get("auto_rollover_count").asInt()).isEqualTo(1);
        assertThat(ledger.balanceOf(UUID.fromString(next.get("ledger_account_id").asString())))
                .isEqualByComparingTo(dec(next, "principal_amount"));
        assertThat(dec(next, "principal_amount")).isGreaterThan(new BigDecimal("200"));
        assertThat(events("accounts.term_deposit.matured", null)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 8. standing orders and sweeps

    @Test
    @Order(11)
    void standingOrdersScheduleSkipExecuteAndFail() throws Exception {
        LocalDate today = LocalDate.now(CLOCK);
        JsonNode so = post("/v1/standing-orders", """
                {"deposit_account_id":"%s","beneficiary_account_id":"%s","amount":"25.00","currency":"EUR",
                 "frequency":"MONTHLY","start_date":"%s","reference":"Rent"}""".formatted(a, b, today), "customer", 201);
        String id = so.get("id").asString();
        LocalDate first = LocalDate.parse(so.get("next_execution_date").asString());
        assertThat(first).isEqualTo(businessDay(today));
        JsonNode skipped = post("/v1/standing-orders/" + id + "/skip-next", null, "customer", 200);
        LocalDate second = LocalDate.parse(skipped.get("next_execution_date").asString());
        assertThat(second).isEqualTo(businessDay(today.plusMonths(1)));
        assertThat(post("/v1/standing-orders/" + id + "/pause", "{\"reason\":\"holiday\"}", "customer", 200)
                .get("status").asString()).isEqualTo("PAUSED");
        assertThat(post("/v1/standing-orders/" + id + "/pause", "{}", "customer", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        post("/v1/standing-orders/" + id + "/resume", null, "customer", 200);

        BigDecimal bBefore = ledger.balanceOf(bLedger);
        JsonNode run = post("/v1/deposit-accounts/eod/standing-orders?business_date=" + second, null, "system:business-day", 200);
        assertThat(run.get("counts").get("succeeded").asInt()).isEqualTo(1);
        assertThat(ledger.balanceOf(bLedger).subtract(bBefore)).isEqualByComparingTo("25.00");
        assertThat(post("/v1/deposit-accounts/eod/standing-orders?business_date=" + second, null, "system:business-day", 200)
                .get("replayed").asBoolean()).isTrue();
        assertThat(ledger.balanceOf(bLedger).subtract(bBefore)).isEqualByComparingTo("25.00");
        FakeLedger.Call posting = ledger.lastPosting();
        assertThat(posting.key()).isEqualTo("standing-order:" + id + ":" + second + ":1");
        JsonNode execs = get("/v1/standing-orders/" + id + "/executions").get("data");
        assertThat(execs).hasSize(2);
        assertThat(get("/v1/standing-orders/" + id).get("executions_done").asInt()).isEqualTo(1);
        assertThat(events("accounts.standing_order.executed", null)).isEqualTo(1);

        String big = post("/v1/standing-orders", """
                {"deposit_account_id":"%s","payee_id":"%s","amount":"999999.00","currency":"EUR",
                 "frequency":"WEEKLY","start_date":"%s"}""".formatted(b, UUID.randomUUID(), today), "customer", 201)
                .get("id").asString();
        LocalDate due = LocalDate.parse(get("/v1/standing-orders/" + big).get("next_execution_date").asString());
        JsonNode failedRun = post("/v1/deposit-accounts/eod/standing-orders?business_date=" + due, null,
                "system:business-day", 200);
        assertThat(failedRun.get("counts").get("failed").asInt()).isEqualTo(1);
        assertThat(failedRun.get("status").asString()).isEqualTo("COMPLETED"); // a business failure, not a job error
        JsonNode failed = get("/v1/standing-orders/" + big);
        assertThat(failed.get("consecutive_failures").asInt()).isEqualTo(1);
        assertThat(get("/v1/standing-orders/" + big + "/executions").get("data").get(0).get("failure_code").asString())
                .isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(events("accounts.standing_order.failed", null)).isEqualTo(1);
        assertThat(post("/v1/standing-orders/" + big + "/cancel", "{\"reason\":\"no funds\"}", "customer", 200)
                .get("status").asString()).isEqualTo("CANCELLED");
        assertThat(post("/v1/standing-orders/" + big + "/cancel", "{}", "customer", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(post("/v1/standing-orders", """
                {"deposit_account_id":"%s","amount":"5.00","currency":"EUR","frequency":"MONTHLY","start_date":"%s"}"""
                .formatted(a, today), "customer", 422).get("code").asString()).isEqualTo("beneficiary_required");
    }

    @Test
    @Order(12)
    void sweepRunNowMovesTheExcess() throws Exception {
        assertThat(post("/v1/sweep-rules", """
                {"source_account_id":"%s","target_account_id":"%s","sweep_type":"THRESHOLD_OUT"}""".formatted(a, b),
                "ops", 422).get("code").asString()).isEqualTo("threshold_required");
        String rule = post("/v1/sweep-rules", """
                {"source_account_id":"%s","target_account_id":"%s","sweep_type":"TARGET_BALANCE","target_balance":"1000.00",
                 "min_sweep_amount":"1.00","run_time":"18:00"}""".formatted(a, b), "ops", 201).get("id").asString();
        BigDecimal aBefore = ledger.balanceOf(aLedger);
        JsonNode exec = post("/v1/sweep-rules/" + rule + "/run-now", null, "ops", 200);
        assertThat(exec.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(dec(exec, "amount")).isEqualByComparingTo(aBefore.subtract(new BigDecimal("1000.00")).abs());
        assertThat(ledger.balanceOf(aLedger)).isEqualByComparingTo("1000.00");
        JsonNode again = post("/v1/sweep-rules/" + rule + "/run-now", null, "ops", 200);
        assertThat(again.get("status").asString()).isEqualTo("SKIPPED");
        assertThat(get("/v1/sweep-rules/" + rule + "/executions").get("data")).hasSize(2);
    }

    // ------------------------------------------------------------------ 9. cheques, virtual accounts, holders

    @Test
    @Order(13)
    void chequesStopPresentReturn() throws Exception {
        JsonNode book = post("/v1/cheque-books", """
                {"deposit_account_id":"%s","leaves":3,"first_cheque_no":"000101","last_cheque_no":"000103"}""".formatted(a),
                "ops", 201);
        assertThat(post("/v1/cheque-books", """
                {"deposit_account_id":"%s","leaves":2,"first_cheque_no":"000103","last_cheque_no":"000104"}""".formatted(a),
                "ops", 409).get("code").asString()).isEqualTo("serials_in_use");
        String bookId = book.get("id").asString();
        assertThat(book.get("status").asString()).isEqualTo("REQUESTED");
        JsonNode leaves = get("/v1/cheque-books/" + bookId + "/cheques?status=ISSUED").get("data");
        assertThat(leaves).hasSize(3);
        assertThat(get("/v1/cheque-books/" + bookId + "/cheques?status=CLEARED").get("data")).isEmpty();
        assertThat(post("/v1/cheques/" + chequeId(leaves, "000102") + "/present", "{\"amount\":\"1.00\",\"currency\":\"EUR\"}",
                "clearing", 409).get("code").asString()).isEqualTo("cheque_book_not_active");
        assertThat(post("/v1/cheque-books/" + bookId + "/activate", null, "ops", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        JsonNode dispatched = post("/v1/cheque-books/" + bookId + "/dispatch", "{\"dispatch_reference\":\"RM-123456\"}",
                "ops", 200);
        assertThat(dispatched.get("status").asString()).isEqualTo("DISPATCHED");
        assertThat(dispatched.get("dispatch_reference").asString()).isEqualTo("RM-123456");
        assertThat(dispatched.get("dispatched_at").isNull()).isFalse();
        JsonNode activated = post("/v1/cheque-books/" + bookId + "/activate", null, "customer", 200);
        assertThat(activated.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(activated.get("activated_at").isNull()).isFalse();
        String c1 = chequeId(leaves, "000101");
        String c2 = chequeId(leaves, "000102");
        String c3 = chequeId(leaves, "000103");
        post("/v1/cheques/" + c1 + "/stop", "{\"reason\":\"lost\"}", "customer", 200);
        assertThat(post("/v1/cheques/" + c1 + "/present", "{\"amount\":\"10.00\",\"currency\":\"EUR\"}", "clearing", 409)
                .get("code").asString()).isEqualTo("cheque_stopped");
        JsonNode bounced = post("/v1/cheques/" + c2 + "/present", "{\"amount\":\"50000.00\",\"currency\":\"EUR\"}", "clearing", 200);
        assertThat(bounced.get("status").asString()).isEqualTo("RETURNED");
        assertThat(bounced.get("return_reason").asString()).isEqualTo("INSUFFICIENT_FUNDS");
        JsonNode cleared = post("/v1/cheques/" + c3 + "/present",
                "{\"amount\":\"40.00\",\"currency\":\"EUR\",\"payee_name\":\"Plumber Ltd\"}", "clearing", 200);
        assertThat(cleared.get("status").asString()).isEqualTo("CLEARED");
        assertLeg(ledger.lastPosting(), CLEARING, "CREDIT", "40.00");
        assertThat(ledger.balanceOf(aLedger)).isEqualByComparingTo("960.00");
        JsonNode returned = post("/v1/cheques/" + c3 + "/return", "{\"return_reason\":\"SIGNATURE_MISMATCH\"}", "clearing", 200);
        assertThat(returned.get("status").asString()).isEqualTo("RETURNED");
        assertThat(ledger.balanceOf(aLedger)).isEqualByComparingTo("1000.00");
        assertThat(get("/v1/cheque-books/" + bookId).get("status").asString()).isEqualTo("EXHAUSTED");
        assertThat(events("accounts.cheque.returned", null)).isEqualTo(2);
    }

    @Test
    @Order(14)
    void virtualAccountsSingleBulkAndClose() throws Exception {
        JsonNode va = post("/v1/virtual-accounts", """
                {"master_account_id":"%s","purpose":"COLLECTION","currency":"EUR","owner_reference":"INV-1"}"""
                .formatted(a), "ops", 201);
        assertThat(AccountNumbers.isValidLuhn(va.get("virtual_account_number").asString())).isTrue();
        UUID vaLedger = UUID.fromString(va.get("ledger_account_id").asString());
        assertThat(ledger.accountRow(vaLedger).get("parent_id").asString()).isEqualTo(aLedger.toString());
        HttpResponse<String> bulk = send("POST", "/v1/virtual-accounts/bulk", """
                {"items":[
                  {"master_account_id":"%s","purpose":"MARKETPLACE_SELLER","currency":"EUR","owner_reference":"S-1"},
                  {"master_account_id":"%s","purpose":"MARKETPLACE_SELLER","currency":"EUR","owner_reference":"S-2"}]}"""
                .formatted(a, a), "bulk-1", "ops", TENANT);
        assertThat(bulk.statusCode()).as(bulk.body()).isEqualTo(200);
        JsonNode created = json.readTree(bulk.body());
        assertThat(created.get("created").asInt()).isEqualTo(2);
        assertThat(created.get("data")).hasSize(2);
        assertThat(AccountNumbers.isValidLuhn(created.get("data").get(0).get("virtual_account_number").asString())).isTrue();
        assertThat(post("/v1/virtual-accounts/bulk", "{\"items\":[{\"master_account_id\":\"" + a
                + "\",\"purpose\":\"INVOICE\",\"currency\":\"USD\"}]}", "ops", 422).get("code").asString())
                .isEqualTo("currency_mismatch");
        assertThat(post("/v1/virtual-accounts/bulk", "{\"items\":[]}", "ops", 422).get("code").asString()).isEqualTo("no_items");
        ledger.credit(vaLedger, "15.00", null);
        assertThat(dec(get("/v1/virtual-accounts/" + va.get("id").asString() + "/balance"), "posted_balance"))
                .isEqualByComparingTo("15.00");
        assertThat(get("/v1/virtual-accounts/" + va.get("id").asString() + "/transactions").get("data")).hasSize(1);
        assertThat(post("/v1/virtual-accounts/" + va.get("id").asString() + "/close", "{\"reason\":\"paid\"}", "ops", 200)
                .get("status").asString()).isEqualTo("CLOSED");
        assertThat(ledger.balanceOf(aLedger)).isEqualByComparingTo("1015.00");
        for (JsonNode v : get("/v1/virtual-accounts?status=ACTIVE").get("data")) {
            post("/v1/virtual-accounts/" + v.get("id").asString() + "/close", "{}", "ops", 200);
        }
        assertThat(events("accounts.virtual_account.created", null)).isEqualTo(3);
    }

    @Test
    @Order(15)
    void holdersAndMandates() throws Exception {
        String joint = UUID.randomUUID().toString();
        String kid = UUID.randomUUID().toString();
        assertThat(post("/v1/account-holders", """
                {"deposit_account_id":"%s","customer_id":"%s","role":"MINOR"}""".formatted(a, kid), "ops", 422)
                .get("code").asString()).isEqualTo("guardian_required");
        JsonNode holder = post("/v1/account-holders", """
                {"deposit_account_id":"%s","customer_id":"%s","role":"JOINT"}""".formatted(a, joint), "ops", 201);
        assertThat(post("/v1/account-holders", """
                {"deposit_account_id":"%s","customer_id":"%s","role":"PRIMARY"}""".formatted(a, joint), "ops", 409)
                .get("code").asString()).isEqualTo("primary_exists");
        post("/v1/account-mandates", """
                {"deposit_account_id":"%s","name":"Large payments","operation_type":"PAYMENT","min_amount":"1000.00",
                 "rule":"ALL","valid_from":"2026-01-01"}""".formatted(a), "ops", 201);
        String small = evaluate("PAYMENT", "50.00", CUSTOMER);
        assertThat(json.readTree(small).get("allowed").asBoolean()).isTrue();
        JsonNode large = json.readTree(evaluate("PAYMENT", "5000.00", CUSTOMER));
        assertThat(large.get("allowed").asBoolean()).isFalse();
        assertThat(large.get("required_approvals").asInt()).isEqualTo(2);
        assertThat(json.readTree(evaluate("PAYMENT", "5000.00", CUSTOMER, joint)).get("allowed").asBoolean()).isTrue();
        assertThat(post("/v1/account-mandates", """
                {"deposit_account_id":"%s","name":"Board","operation_type":"CLOSURE","rule":"CUSTOM",
                 "approver_groups":["A","B"],"valid_from":"2026-01-01"}""".formatted(a), "ops", 422).get("code").asString())
                .isEqualTo("invalid_approver_groups");
        JsonNode custom = post("/v1/account-mandates", """
                {"deposit_account_id":"%s","name":"Board","operation_type":"CLOSURE","rule":"CUSTOM",
                 "approver_groups":[["A","B"]],"valid_from":"2026-01-01"}""".formatted(a), "ops", 201);
        assertThat(custom.get("approver_groups").get(0)).hasSize(2);
        JsonNode closure = json.readTree(evaluate("CLOSURE", null, CUSTOMER));
        assertThat(closure.get("allowed").asBoolean()).isFalse();
        assertThat(closure.get("required_groups").get(0).get(1).asString()).isEqualTo("B");
        assertThat(send("DELETE", "/v1/account-holders/" + holder.get("id").asString(), null, null, "ops", TENANT)
                .statusCode()).isEqualTo(204);
        assertThat(events("accounts.account_holder.removed", null)).isEqualTo(1);
    }

    @Test
    @Order(16)
    void changeProductPinsTheCatalogTerms() throws Exception {
        JsonNode changed = post("/v1/deposit-accounts/" + b + "/change-product", """
                {"product_id":"%s","product_version_id":"%s","effective_date":"%s"}"""
                .formatted(PRODUCT, VERSION_2, LocalDate.now(CLOCK)), "ops", 200);
        assertThat(changed.get("product_version_id").asString()).isEqualTo(VERSION_2);
        assertThat(changed.get("product_terms").get("interest_rates").get(0).get("tiers")).hasSize(2);
        assertThat(changed.hasNonNull("pending_product_change")).isFalse();
        assertThat(post("/v1/deposit-accounts/" + b + "/change-product", """
                {"product_id":"%s","product_version_id":"%s","effective_date":"%s"}"""
                .formatted(PRODUCT, VERSION_2, LocalDate.now(CLOCK)), "ops", 409).get("code").asString())
                .isEqualTo("same_product_version");
        assertThat(events("accounts.deposit_account.product_changed", b)).isEqualTo(1);
        HttpResponse<String> patch = send("PATCH", "/v1/deposit-accounts/" + b, "{\"currency\":\"USD\"}", null, "ops", TENANT);
        assertThat(patch.statusCode()).isEqualTo(422);

        LocalDate tomorrow = LocalDate.now(CLOCK).plusDays(1);
        JsonNode scheduled = post("/v1/deposit-accounts/" + b + "/change-product", """
                {"product_id":"%s","product_version_id":"%s","effective_date":"%s"}"""
                .formatted(PRODUCT, VERSION, tomorrow), "ops", 200);
        assertThat(scheduled.get("product_version_id").asString()).isEqualTo(VERSION_2);
        assertThat(scheduled.get("pending_product_change").get("effective_date").asString()).isEqualTo(tomorrow.toString());
        JsonNode applied = post("/v1/deposit-accounts/eod/product-changes?business_date=" + tomorrow, null,
                "system:business-day", 200);
        assertThat(applied.get("counts").get("product_changes").asInt()).isEqualTo(1);
        JsonNode moved = get("/v1/deposit-accounts/" + b);
        assertThat(moved.get("product_version_id").asString()).isEqualTo(VERSION);
        assertThat(moved.hasNonNull("pending_product_change")).isFalse();
        assertThat(moved.get("product_terms").get("fees")).hasSize(2);
    }

    // ------------------------------------------------------------------ 10. dormancy and closure

    @Test
    @Order(17)
    void dormancyAndReactivation() throws Exception {
        String c = openActive("C");
        JsonNode scan = post("/v1/deposit-accounts/eod/dormancy?business_date=" + LocalDate.now(CLOCK).plusDays(800), null,
                "system:business-day", 200);
        assertThat(scan.get("counts").get("accounts_changed").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode dormant = get("/v1/deposit-accounts/" + c);
        assertThat(dormant.get("status").asString()).isEqualTo("DORMANT");
        assertThat(events("accounts.dormancy.marked_dormant", null)).isGreaterThanOrEqualTo(1);
        assertThat(post("/v1/deposit-accounts/" + c + "/reactivate", "{\"reason\":\"customer verified\"}", "ops", 200)
                .get("status").asString()).isEqualTo("ACTIVE");
        assertThat(get("/v1/dormancy-events?deposit_account_id=" + c).get("data")).hasSize(2);
        for (String acc : List.of(a, b)) { // keep the other accounts active for later tests
            if ("DORMANT".equals(get("/v1/deposit-accounts/" + acc).get("status").asString())) {
                post("/v1/deposit-accounts/" + acc + "/reactivate", "{\"reason\":\"test\"}", "ops", 200);
            }
        }
    }

    @Test
    @Order(18)
    void closureQuoteFourEyesSettlementAndZeroBalanceClose() throws Exception {
        String d = openActive("D");
        UUID dLedger = UUID.fromString(get("/v1/deposit-accounts/" + d).get("ledger_account_id").asString());
        ledger.credit(dLedger, "100.00", null);
        assertThat(send("GET", "/v1/account-closures/quote", null, null, "ops", TENANT).statusCode()).isEqualTo(400);
        HttpResponse<String> q = send("GET", "/v1/account-closures/quote?deposit_account_id=" + d, null, null, "ops", TENANT);
        assertThat(q.statusCode()).as(q.body()).isEqualTo(200);
        JsonNode quote = json.readTree(q.body());
        assertThat(dec(quote, "closure_fee")).isEqualByComparingTo("10.00");
        assertThat(dec(quote, "final_payout")).isEqualByComparingTo("90.00");
        assertThat(quote.get("closable").asBoolean()).isTrue();
        assertThat(post("/v1/account-closures", """
                {"deposit_account_id":"%s","reason_code":"CUSTOMER_REQUEST"}""".formatted(d), "maker", 422)
                .get("code").asString()).isEqualTo("payout_destination_required");
        JsonNode closure = post("/v1/account-closures", """
                {"deposit_account_id":"%s","reason_code":"CUSTOMER_REQUEST","transfer_to_account_id":"%s"}"""
                .formatted(d, b), "maker", 201);
        assertThat(closure.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(get("/v1/deposit-accounts/" + d).get("status").asString()).isEqualTo("CLOSING");
        String id = closure.get("id").asString();
        assertThat(post("/v1/account-closures/" + id + "/approve", "{}", "maker", 422).get("code").asString())
                .isEqualTo("four_eyes_violation");
        BigDecimal bBefore = ledger.balanceOf(bLedger);
        JsonNode done = post("/v1/account-closures/" + id + "/approve", "{\"comment\":\"verified\"}", "checker", 200);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(dec(done, "final_balance")).isEqualByComparingTo("90.00");
        assertThat(dec(done, "final_fees")).isEqualByComparingTo("10.00");
        assertThat(ledger.balanceOf(dLedger)).isZero();
        assertThat(ledger.statusOf(dLedger)).isEqualTo("CLOSED");
        assertThat(ledger.balanceOf(bLedger).subtract(bBefore)).isEqualByComparingTo("90.00");
        assertThat(ledger.calls("POST", "/v1/journal-entries").stream().anyMatch(c -> ("closure-payout:" + id).equals(c.key())))
                .isTrue();
        JsonNode closed = get("/v1/deposit-accounts/" + d);
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("closed_at").isNull()).isFalse();
        assertThat(events("accounts.deposit_account.closed", d)).isEqualTo(1);
        assertThat(post("/v1/deposit-accounts/" + d + "/freeze", "{\"reason\":\"x\"}", "ops", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(post("/v1/account-closures/" + id + "/cancel", "{}", "ops", 409).get("code").asString())
                .isEqualTo("invalid_transition");

        String e = openActive("E");
        UUID eLedger = UUID.fromString(get("/v1/deposit-accounts/" + e).get("ledger_account_id").asString());
        assertThat(post("/v1/account-closures", """
                {"deposit_account_id":"%s","reason_code":"CUSTOMER_REQUEST"}""".formatted(e), "maker", 422)
                .get("code").asString()).isEqualTo("negative_balance"); // the closure fee would overdraw it
        ledger.credit(eLedger, "10.00", null); // exactly covers the closure fee: nothing to pay out
        String req = post("/v1/account-closures", """
                {"deposit_account_id":"%s","reason_code":"CUSTOMER_REQUEST"}""".formatted(e), "maker", 201).get("id").asString();
        assertThat(post("/v1/account-closures/" + req + "/reject", "{\"reason\":\"retention offer accepted\"}", "checker", 200)
                .get("status").asString()).isEqualTo("REJECTED");
        assertThat(get("/v1/deposit-accounts/" + e).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    @Order(19)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/deposit-accounts/" + a, null, null, "ops", OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/v1/deposit-accounts/" + a + "/freeze", "{\"reason\":\"x\"}", UUID.randomUUID().toString(),
                "ops", OTHER_TENANT).statusCode()).isEqualTo(404);
        JsonNode list = json.readTree(send("GET", "/v1/deposit-accounts", null, null, "ops", OTHER_TENANT).body());
        assertThat(list.get("data")).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private String accountBody() {
        return """
                {"account_type":"CURRENT","customer_id":"%s","product_id":"%s",
                 "product_version_id":"%s","legal_entity_id":"%s","currency":"EUR","metadata":{"channel_ref":"T-1"}}"""
                .formatted(CUSTOMER, PRODUCT, VERSION, UUID.randomUUID());
    }

    private String openActive(String nickname) throws Exception {
        String id = post("/v1/deposit-accounts", accountBody().replace("\"CURRENT\"", "\"CURRENT\",\"nickname\":\""
                + nickname + "\""), "maker", 201).get("id").asString();
        post("/v1/deposit-accounts/" + id + "/approve", "{}", "checker", 200);
        post("/v1/deposit-accounts/" + id + "/activate", null, "checker", 200);
        return id;
    }

    private String tdBody(LocalDate start, LocalDate maturity, String principal, String rollover) {
        return """
                {"customer_id":"%s","product_id":"%s","product_version_id":"%s",
                 "funding_account_id":"%s","payout_account_id":"%s","principal_amount":"%s","currency":"EUR",
                 "interest_rate":"0.0365","day_count":"ACT_365","term_days":30,"start_date":"%s","maturity_date":"%s",
                 "rollover_instruction":"%s","premature_penalty_rate":"0.01"}"""
                .formatted(CUSTOMER, PRODUCT, VERSION, a, a, principal, start, maturity, rollover);
    }

    private static LocalDate businessDay(LocalDate d) {
        LocalDate x = d;
        while (x.getDayOfWeek() == DayOfWeek.SATURDAY || x.getDayOfWeek() == DayOfWeek.SUNDAY) {
            x = x.plusDays(1);
        }
        return x;
    }

    private String evaluate(String op, String amount, String... signatories) throws Exception {
        String body = "{\"deposit_account_id\":\"" + a + "\",\"operation_type\":\"" + op + "\""
                + (amount == null ? "" : ",\"amount\":\"" + amount + "\"")
                + ",\"signatories\":[" + String.join(",", java.util.Arrays.stream(signatories).map(x -> "\"" + x + "\"").toList())
                + "]}";
        HttpResponse<String> r = send("POST", "/v1/account-mandates/evaluate", body, UUID.randomUUID().toString(), "ops", TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r.body();
    }

    private static String chequeId(JsonNode leaves, String no) {
        for (JsonNode c : leaves) {
            if (no.equals(c.get("cheque_no").asString())) {
                return c.get("id").asString();
            }
        }
        throw new AssertionError("no cheque " + no);
    }

    private static void assertLeg(FakeLedger.Call posting, String account, String direction, String amount) {
        boolean found = false;
        for (JsonNode e : posting.body().get("entries")) {
            if (account.equals(e.get("ledger_account_id").asString()) && direction.equals(e.get("direction").asString())
                    && new BigDecimal(e.get("amount").asString()).compareTo(new BigDecimal(amount)) == 0) {
                found = true;
            }
        }
        assertThat(found).as("leg %s %s %s in %s", account, direction, amount, posting.body()).isTrue();
    }

    private <T> T asTenant(Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "batch", body);
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
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
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
