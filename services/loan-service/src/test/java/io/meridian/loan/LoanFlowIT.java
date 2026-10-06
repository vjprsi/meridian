package io.meridian.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * loan-service end to end over HTTP against PostgreSQL 18 (RLS enforced) with ledger-service, deposit-service and the
 * product catalog stubbed behind a mocked {@link ServiceClient} (stateful {@link FakeLedger}). A controllable clock
 * moves business time; the end-of-day jobs are called over HTTP the way business-day-service calls them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.loan.ledger-id=0199b000-0000-7000-8000-0000000000aa",
        "meridian.loan.gl.interest-receivable=0199b000-0000-7000-8000-0000000000e1",
        "meridian.loan.gl.interest-income=0199b000-0000-7000-8000-0000000000e2",
        "meridian.loan.gl.interest-suspense=0199b000-0000-7000-8000-0000000000e3",
        "meridian.loan.gl.fee-receivable=0199b000-0000-7000-8000-0000000000e4",
        "meridian.loan.gl.fee-income=0199b000-0000-7000-8000-0000000000e5",
        "meridian.loan.gl.penalty-receivable=0199b000-0000-7000-8000-0000000000e6",
        "meridian.loan.gl.penalty-income=0199b000-0000-7000-8000-0000000000e7",
        "meridian.loan.gl.write-off-expense=0199b000-0000-7000-8000-0000000000e8",
        "meridian.loan.gl.recovery-income=0199b000-0000-7000-8000-0000000000e9",
        "meridian.loan.gl.agency-commission=0199b000-0000-7000-8000-0000000000ea",
        "meridian.loan.gl.clearing=0199b000-0000-7000-8000-0000000000eb",
        "meridian.loan.gl.overpayment=0199b000-0000-7000-8000-0000000000ec",
        "meridian.loan.gl.tax-payable=0199b000-0000-7000-8000-0000000000ed",
        "meridian.loan.gl.waiver-expense=0199b000-0000-7000-8000-0000000000ee"})
@Import({MeridianTestContainers.class, LoanFlowIT.ClockConfig.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoanFlowIT {
    static final String TENANT = "0199b000-0000-7000-8000-00000000d001";
    static final String OTHER_TENANT = "0199b000-0000-7000-8000-00000000d002";
    static final String INTEREST_RECEIVABLE = "0199b000-0000-7000-8000-0000000000e1";
    static final String INTEREST_INCOME = "0199b000-0000-7000-8000-0000000000e2";
    static final String FEE_RECEIVABLE = "0199b000-0000-7000-8000-0000000000e4";
    static final String FEE_INCOME = "0199b000-0000-7000-8000-0000000000e5";
    static final String PENALTY_RECEIVABLE = "0199b000-0000-7000-8000-0000000000e6";
    static final String WRITE_OFF_EXPENSE = "0199b000-0000-7000-8000-0000000000e8";
    static final String RECOVERY_INCOME = "0199b000-0000-7000-8000-0000000000e9";
    static final String AGENCY = "0199b000-0000-7000-8000-0000000000ea";
    static final String CLEARING = "0199b000-0000-7000-8000-0000000000eb";
    static final String CUSTOMER = "0199b000-0000-7000-8000-00000000c001";
    static final String CUSTOMER_2 = "0199b000-0000-7000-8000-00000000c002";
    static final String MERCHANT = "0199b000-0000-7000-8000-00000000c0aa";
    static final String PRODUCT = "0199b000-0000-7000-8000-00000000f001";
    static final String VERSION = "0199b000-0000-7000-8000-00000000f002";
    static final String LEGAL_ENTITY = "0199b000-0000-7000-8000-00000000e001";

    /** A clock tests can move forward. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void set(String isoDate) {
            now = Instant.parse(isoDate + "T10:00:00Z");
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
    /** deposit account id -> ledger account id. */
    final Map<String, UUID> deposits = new HashMap<>();
    String accountA;
    String merchantAccount;
    String loan;
    String loan2;

    @BeforeAll
    void setUp() {
        ledger = new FakeLedger(json, CLOCK);
        accountA = openDeposit("20000");
        merchantAccount = openDeposit("0");
    }

    @BeforeEach
    void stubServiceClient() {
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> ledger.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> {
            String service = i.getArgument(0);
            String path = i.getArgument(1);
            return switch (service) {
                case "product-catalog-service" -> catalog(path);
                case "deposit-service" -> deposit(path);
                default -> ledger.get(service, path);
            };
        });
    }

    private String openDeposit(String balance) {
        UUID ledgerAccount = ledger.openCustomerAccount("EUR");
        if (new BigDecimal(balance).signum() > 0) {
            ledger.credit(ledgerAccount, balance, null);
        }
        String id = UUID.randomUUID().toString();
        deposits.put(id, ledgerAccount);
        return id;
    }

    private JsonNode deposit(String path) {
        String id = path.substring("/v1/deposit-accounts/".length());
        UUID acc = deposits.get(id);
        if (acc == null) {
            throw ApiException.notFound("deposit account", id);
        }
        return json.readTree("{\"id\":\"" + id + "\",\"ledger_account_id\":\"" + acc + "\",\"currency\":\"EUR\",\"status\":\"ACTIVE\"}");
    }

    private JsonNode catalog(String path) {
        if (path.equals("/v1/product-versions/" + VERSION)) {
            return json.readTree("{\"id\":\"" + VERSION + "\",\"product_id\":\"" + PRODUCT + "\",\"status\":\"PUBLISHED\"}");
        }
        if (path.startsWith("/v1/product-versions/" + VERSION + "/interest-rates")) {
            return json.readTree("""
                    {"data":[{"rate_kind":"DEBIT_INTEREST","rate_type":"FIXED","fixed_rate":"0.12","day_count":"ACT_365"},
                             {"rate_kind":"PENALTY_INTEREST","rate_type":"FIXED","fixed_rate":"0.24","day_count":"ACT_365"}],"has_more":false}""");
        }
        if (path.startsWith("/v1/product-versions/" + VERSION + "/fees")) {
            return json.readTree("""
                    {"data":[{"code":"ORIG","trigger":"LOAN_ORIGINATION","calculation_method":"FLAT","flat_amount":"50.00"},
                             {"code":"LATE","trigger":"LATE_PAYMENT","calculation_method":"FLAT","flat_amount":"15.00"},
                             {"code":"ERC","trigger":"LOAN_PREPAYMENT","calculation_method":"PERCENTAGE","percentage":"0.01"}],"has_more":false}""");
        }
        if (path.startsWith("/v1/product-versions/" + VERSION + "/parameters")) {
            return json.readTree("""
                    {"data":[{"param_key":"lending.min_amount","value_text":"100"},{"param_key":"lending.max_amount","value_text":"50000"},
                             {"param_key":"lending.max_term_months","value_text":"84"},{"param_key":"lending.cooling_off_days","value_text":"14"}],
                     "has_more":false}""");
        }
        throw new ApiException(404, "not_found", path);
    }

    // ------------------------------------------------------------------ 1. booking

    @Test
    @Order(1)
    void bookLoanPinsProductTermsAndGeneratesTheSchedule() throws Exception {
        String key = UUID.randomUUID().toString();
        HttpResponse<String> r = send("POST", "/v1/loans", loanBody("10000", 12, "ANNUITY"), key, "ops", TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        JsonNode l = json.readTree(r.body());
        loan = l.get("id").asString();
        assertThat(l.get("loan_number").asString()).startsWith("LN").hasSize(12);
        assertThat(l.get("status").asString()).isEqualTo("PENDING_DISBURSEMENT");
        assertThat(dec(l, "penalty_rate")).isEqualByComparingTo("0.24");
        assertThat(new BigDecimal(l.get("product_terms").get("origination_fee").asString())).isEqualByComparingTo("50");
        assertThat(l.get("maturity_date").asString()).isEqualTo("2027-10-05");

        HttpResponse<String> replay = send("POST", "/v1/loans", loanBody("10000", 12, "ANNUITY"), key, "ops", TENANT);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(loan);

        JsonNode schedule = get("/v1/loans/" + loan + "/schedule").get("data");
        assertThat(schedule).hasSize(12);
        BigDecimal principal = BigDecimal.ZERO;
        for (JsonNode i : schedule) {
            principal = principal.add(dec(i, "principal_due"));
            assertThat(i.get("status").asString()).isEqualTo("SCHEDULED");
        }
        assertThat(principal).isEqualByComparingTo("10000");
        assertThat(dec(schedule.get(11), "outstanding_balance_after")).isEqualByComparingTo("0");

        // validation: product bounds, rate, PATCH of contract terms
        assertThat(post("/v1/loans", loanBody("60000", 12, "ANNUITY"), "ops", 422).get("code").asString()).isEqualTo("amount_out_of_range");
        assertThat(post("/v1/loans", loanBody("10000", 12, "BALLOON"), "ops", 422).get("code").asString()).isEqualTo("invalid_schedule");
        HttpResponse<String> patch = send("PATCH", "/v1/loans/" + loan, "{\"principal_amount\":\"20000\"}", null, "ops", TENANT);
        assertThat(patch.statusCode()).isEqualTo(422);
        assertThat(json.readTree(patch.body()).get("code").asString()).isEqualTo("field_not_updatable");
        HttpResponse<String> okPatch = send("PATCH", "/v1/loans/" + loan, "{\"risk_grade\":\"B2\"}", null, "ops", TENANT);
        assertThat(okPatch.statusCode()).as(okPatch.body()).isEqualTo(200);
        assertThat(events("lending.loan.created", loan)).isEqualTo(1);
    }

    @Test
    @Order(2)
    void simulateReturnsScheduleTotalsAndApr() throws Exception {
        JsonNode s = post("/v1/loans/simulate", """
                {"product_id":"%s","product_version_id":"%s","principal_amount":"10000","currency":"EUR","term_months":12,
                 "schedule_type":"ANNUITY","repayment_frequency":"MONTHLY"}""".formatted(PRODUCT, VERSION), "ops", 200);
        assertThat(s.get("installments")).hasSize(12);
        assertThat(dec(s, "interest_rate")).isEqualByComparingTo("0.12");
        assertThat(dec(s, "upfront_fees")).isEqualByComparingTo("50");
        assertThat(new BigDecimal(s.get("apr").asString())).isGreaterThan(new BigDecimal("0.13"));
        JsonNode balloon = post("/v1/loans/simulate", """
                {"product_id":"%s","principal_amount":"20000","term_months":24,"interest_rate":"0.08","schedule_type":"BALLOON",
                 "balloon_amount":"8000","upfront_fees":"0"}""".formatted(PRODUCT), "ops", 200);
        assertThat(new BigDecimal(balloon.get("installments").get(23).get("principal").asString())).isGreaterThan(new BigDecimal("8000"));
    }

    // ------------------------------------------------------------------ 2. disbursement

    @Test
    @Order(3)
    void disburseNetsTheOriginationFeeAndPostsToTheLedger() throws Exception {
        BigDecimal before = ledger.balanceOf(deposits.get(accountA));
        JsonNode d = post("/v1/loans/" + loan + "/disburse", "{\"method\":\"DEPOSIT_ACCOUNT\"}", "ops", 200);
        assertThat(d.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(dec(d, "fees_deducted")).isEqualByComparingTo("50");
        assertThat(dec(d, "net_amount")).isEqualByComparingTo("9950");
        FakeLedger.Call posting = ledger.lastPosting();
        JsonNode l = get("/v1/loans/" + loan);
        String receivable = l.get("ledger_account_id").asString();
        assertLeg(posting, receivable, "DEBIT", "10000");
        assertLeg(posting, deposits.get(accountA).toString(), "CREDIT", "9950");
        assertLeg(posting, FEE_INCOME, "CREDIT", "50");
        assertThat(posting.key()).startsWith("loan-disbursement:");
        assertThat(ledger.balanceOf(deposits.get(accountA)).subtract(before)).isEqualByComparingTo("9950");
        assertThat(l.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(dec(l, "outstanding_principal")).isEqualByComparingTo("10000");
        assertThat(l.get("cooling_off_until").asString()).isEqualTo("2026-10-19");
        assertThat(post("/v1/loans/" + loan + "/disburse", "{\"method\":\"DEPOSIT_ACCOUNT\"}", "ops", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(get("/v1/loan-fee-charges?loan_id=" + loan).get("data").get(0).get("status").asString()).isEqualTo("PAID");
        assertThat(get("/v1/loans/" + loan + "/schedules").get("data")).hasSize(2);
        assertThat(events("lending.loan.disbursed", null)).isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------ 3. accrual and replays

    @Test
    @Order(4)
    void dailyAccrualPostsInterestAndIsIdempotentPerBusinessDate() throws Exception {
        JsonNode run = post("/v1/loans/eod/accrue?business_date=2026-10-05", null, "business-day", 200);
        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(run.get("replayed").asBoolean()).isFalse();
        assertThat(run.get("counts").get("accrual_rows").asInt()).isEqualTo(1);
        JsonNode l = get("/v1/loans/" + loan);
        // 10,000 x 12% / 365 = 3.29 for one day
        assertThat(dec(l, "outstanding_interest")).isEqualByComparingTo("3.29");
        FakeLedger.Call accrual = ledger.lastPosting();
        assertLeg(accrual, INTEREST_RECEIVABLE, "DEBIT", "3.29");
        assertLeg(accrual, INTEREST_INCOME, "CREDIT", "3.29");
        JsonNode again = post("/v1/loans/eod/accrue?business_date=2026-10-05", null, "business-day", 200);
        assertThat(again.get("replayed").asBoolean()).isTrue();
        assertThat(get("/v1/loans/" + loan + "/accruals").get("data")).hasSize(1);
        assertThat(send("POST", "/v1/loans/eod/accrue", null, UUID.randomUUID().toString(), "ops", TENANT).statusCode()).isEqualTo(400);
        assertThat(get("/v1/loan-eod-runs?job=DAILY_ACCRUAL").get("data")).hasSize(1);
    }

    // ------------------------------------------------------------------ 4. credit line

    @Test
    @Order(5)
    void creditLineDrawRepayLimitsStatementsAndConversion() throws Exception {
        JsonNode line = post("/v1/credit-lines", """
                {"customer_id":"%s","product_id":"%s","product_version_id":"%s","legal_entity_id":"%s","line_type":"REVOLVING",
                 "currency":"EUR","credit_limit":"2000","purchase_rate":"0.18","cash_advance_rate":"0.24","statement_day":5,
                 "minimum_payment_pct":"0.05","minimum_payment_floor":"25","repayment_account_id":"%s","autopay_mode":"NONE"}"""
                .formatted(CUSTOMER, PRODUCT, VERSION, LEGAL_ENTITY, accountA), "ops", 201);
        String id = line.get("id").asString();
        assertThat(line.get("credit_line_number").asString()).startsWith("CL");
        assertThat(post("/v1/credit-lines/" + id + "/draw", "{\"draw_type\":\"PURCHASE\",\"amount\":\"10\"}", "ops", 409)
                .get("code").asString()).isEqualTo("invalid_transition");
        JsonNode active = post("/v1/credit-lines/" + id + "/activate", null, "ops", 200);
        assertThat(dec(active, "available_amount")).isEqualByComparingTo("2000");

        JsonNode draw = post("/v1/credit-lines/" + id + "/draw", """
                {"draw_type":"TRANSFER_TO_ACCOUNT","amount":"1000","destination_account_id":"%s"}""".formatted(accountA), "ops", 200);
        assertThat(draw.get("status").asString()).isEqualTo("POSTED");
        assertLeg(ledger.lastPosting(), active.get("ledger_account_id").asString(), "DEBIT", "1000");
        JsonNode declined = post("/v1/credit-lines/" + id + "/draw", "{\"draw_type\":\"PURCHASE\",\"amount\":\"1500\"}", "ops", 200);
        assertThat(declined.get("status").asString()).isEqualTo("DECLINED");
        JsonNode purchase = post("/v1/credit-lines/" + id + "/draw", "{\"draw_type\":\"PURCHASE\",\"amount\":\"200\"}", "ops", 200);

        post("/v1/credit-lines/" + id + "/freeze", "{\"reason\":\"suspected fraud\"}", "ops", 200);
        assertThat(post("/v1/credit-lines/" + id + "/draw", "{\"draw_type\":\"PURCHASE\",\"amount\":\"10\"}", "ops", 409)
                .get("code").asString()).isEqualTo("credit_line_frozen");
        post("/v1/credit-lines/" + id + "/unfreeze", "{\"reason\":\"cleared\"}", "ops", 200);

        assertThat(post("/v1/credit-lines/" + id + "/change-limit", "{\"new_limit\":\"5000\",\"reason\":\"REVIEW\"}", "ops", 422)
                .get("code").asString()).isEqualTo("approval_required");
        assertThat(post("/v1/credit-lines/" + id + "/change-limit", "{\"new_limit\":\"500\",\"reason\":\"RISK_DECREASE\"}", "ops", 422)
                .get("code").asString()).isEqualTo("limit_below_utilisation");
        post("/v1/credit-lines/" + id + "/change-limit", "{\"new_limit\":\"2400\",\"reason\":\"CUSTOMER_REQUEST\"}", "ops", 200);
        post("/v1/credit-lines/" + id + "/change-limit", """
                {"new_limit":"3000","is_temporary":true,"expires_at":"2099-01-01T00:00:00Z","reason":"PROACTIVE_INCREASE",
                 "approval_request_id":"%s"}""".formatted(UUID.randomUUID()), "ops", 200);
        JsonNode avail = get("/v1/credit-lines/" + id + "/available");
        assertThat(avail.get("temporary_limit_active").asBoolean()).isTrue();
        assertThat(dec(avail, "available_amount")).isEqualByComparingTo("1800");
        assertThat(get("/v1/credit-lines/" + id + "/limit-changes").get("data")).hasSize(2);

        JsonNode reversed = post("/v1/credit-line-drawdowns/" + purchase.get("id").asString() + "/reverse", "{\"reason\":\"merchant refund\"}", "ops", 200);
        assertThat(reversed.get("status").asString()).isEqualTo("REVERSED");

        JsonNode pay = post("/v1/credit-lines/" + id + "/repay", """
                {"amount":"100","source":"MANUAL","source_account_id":"%s"}""".formatted(accountA), "ops", 200);
        assertThat(dec(pay, "allocated_principal")).isEqualByComparingTo("100");
        assertThat(pay.get("credit_line_id").asString()).isEqualTo(id);
        assertThat(post("/v1/credit-lines/" + id + "/repay", "{\"amount\":\"99999\",\"source\":\"MANUAL\"}", "ops", 422)
                .get("code").asString()).isEqualTo("overpayment");

        JsonNode converted = post("/v1/credit-lines/" + id + "/convert-to-instalments", "{\"amount\":\"300\",\"term_months\":6}", "ops", 200);
        assertThat(converted.get("credit_line_id").asString()).isEqualTo(id);
        assertThat(converted.get("status").asString()).isEqualTo("ACTIVE");
        JsonNode after = get("/v1/credit-lines/" + id);
        assertThat(dec(after, "utilised_amount")).isEqualByComparingTo("600");
        assertThat(dec(after, "instalment_balance")).isEqualByComparingTo("300");

        // statement cycle on the statement day (first cycle is interest-free on purchases, cash attracts interest)
        JsonNode run = post("/v1/credit-lines/eod/statements?business_date=2026-10-05", null, "business-day", 200);
        assertThat(run.get("counts").get("statements_issued").asInt()).isEqualTo(1);
        JsonNode st = get("/v1/credit-lines/" + id + "/statements").get("data").get(0);
        assertThat(dec(st, "cash_advances")).isEqualByComparingTo("1000");
        assertThat(dec(st, "interest_charged")).isEqualByComparingTo("0.66");
        assertThat(dec(st, "minimum_payment_due")).isEqualByComparingTo("30.03");
        assertThat(st.get("payment_due_date").asString()).isEqualTo("2026-10-26");
        assertThat(events("lending.credit_line.statement_issued", null)).isEqualTo(1);
        lineId = id;
    }

    String lineId;
    String bnplLoan;

    // ------------------------------------------------------------------ 5. BNPL

    @Test
    @Order(6)
    void bnplEligibilityPlanRefundAndCancel() throws Exception {
        JsonNode elig = post("/v1/bnpl-plans/check-eligibility", """
                {"customer_id":"%s","merchant_id":"%s","amount":"400","currency":"EUR","installment_count":4}"""
                .formatted(CUSTOMER_2, MERCHANT), "ops", 200);
        assertThat(elig.get("eligible").asBoolean()).isTrue();
        assertThat(elig.get("split")).hasSize(4);
        JsonNode tooBig = post("/v1/bnpl-plans/check-eligibility", """
                {"customer_id":"%s","merchant_id":"%s","amount":"9000","currency":"EUR"}""".formatted(CUSTOMER_2, MERCHANT), "ops", 200);
        assertThat(tooBig.get("eligible").asBoolean()).isFalse();

        JsonNode plan = post("/v1/bnpl-plans", bnplBody("ORDER-1", "400"), "ops", 201);
        assertThat(plan.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(dec(plan, "merchant_settlement_amount")).isEqualByComparingTo("388");
        assertThat(ledger.balanceOf(deposits.get(merchantAccount))).isEqualByComparingTo("388");
        String planLoan = plan.get("loan_id").asString();
        bnplLoan = planLoan;
        JsonNode schedule = get("/v1/loans/" + planLoan + "/schedule").get("data");
        assertThat(schedule).hasSize(4);
        assertThat(schedule).allSatisfy(i -> assertThat(dec(i, "total_due")).isEqualByComparingTo("100"));
        assertThat(post("/v1/bnpl-plans", bnplBody("ORDER-1", "400"), "ops", 409).get("code").asString()).isEqualTo("duplicate_order");

        JsonNode refunded = post("/v1/bnpl-plans/" + plan.get("id").asString() + "/refund", "{\"amount\":\"150\",\"reason\":\"item returned\"}", "ops", 200);
        assertThat(dec(refunded, "refunded_amount")).isEqualByComparingTo("150");
        JsonNode after = get("/v1/loans/" + planLoan + "/schedule").get("data");
        assertThat(dec(after.get(3), "principal_due")).isEqualByComparingTo("0");
        assertThat(dec(after.get(2), "principal_due")).isEqualByComparingTo("50");
        assertThat(dec(get("/v1/loans/" + planLoan), "outstanding_principal")).isEqualByComparingTo("250");

        JsonNode plan2 = post("/v1/bnpl-plans", bnplBody("ORDER-2", "200"), "ops", 201);
        JsonNode cancelled = post("/v1/bnpl-plans/" + plan2.get("id").asString() + "/cancel", "{\"reason\":\"order cancelled\"}", "ops", 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(get("/v1/loans/" + plan2.get("loan_id").asString()).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(ledger.balanceOf(deposits.get(merchantAccount))).isEqualByComparingTo("238");
    }

    // ------------------------------------------------------------------ 6. instalments, auto-debit, delinquency

    @Test
    @Order(7)
    void instalmentDueAutoDebitAndDelinquency() throws Exception {
        CLOCK.set("2026-11-05");
        post("/v1/loans/eod/accrue?business_date=2026-11-04", null, "business-day", 200);
        JsonNode due = post("/v1/loans/eod/installments-due?business_date=2026-11-05", null, "business-day", 200);
        assertThat(due.get("counts").get("installments_due").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode first = get("/v1/loans/" + loan + "/schedule").get("data").get(0);
        assertThat(first.get("status").asString()).isEqualTo("DUE");
        BigDecimal instalment = dec(first, "total_due");

        BigDecimal before = ledger.balanceOf(deposits.get(accountA));
        JsonNode debit = post("/v1/loans/eod/auto-debit?business_date=2026-11-05", null, "business-day", 200);
        assertThat(debit.get("counts").get("collected").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(before.subtract(ledger.balanceOf(deposits.get(accountA)))).isGreaterThanOrEqualTo(instalment);
        JsonNode paid = get("/v1/loans/" + loan + "/schedule").get("data").get(0);
        assertThat(paid.get("status").asString()).isEqualTo("PAID");
        JsonNode rep = get("/v1/loans/" + loan + "/repayments").get("data").get(0);
        assertThat(rep.get("source").asString()).isEqualTo("AUTO_DEBIT");
        assertThat(dec(rep, "allocated_interest").add(dec(rep, "allocated_principal"))).isEqualByComparingTo(instalment);
        assertThat(post("/v1/loans/eod/auto-debit?business_date=2026-11-05", null, "business-day", 200).get("replayed").asBoolean()).isTrue();

        // the credit line missed its minimum payment due on 26 October
        JsonNode dq = post("/v1/loans/eod/delinquency?business_date=2026-11-05", null, "business-day", 200);
        assertThat(dq.get("counts").get("credit_lines_changed").asInt()).isEqualTo(1);
        JsonNode line = get("/v1/credit-lines/" + lineId);
        assertThat(line.get("status").asString()).isEqualTo("IN_ARREARS");
        assertThat(line.get("days_past_due").asInt()).isEqualTo(10);
        assertThat(get("/v1/loans/" + loan).get("status").asString()).isEqualTo("ACTIVE");

        // second instalment (7 Dec, 5 Dec is a Saturday) is missed: overdue with a late fee, loan in arrears
        CLOCK.set("2026-12-10");
        post("/v1/loans/eod/accrue?business_date=2026-12-09", null, "business-day", 200);
        JsonNode od = post("/v1/loans/eod/installments-due?business_date=2026-12-10", null, "business-day", 200);
        assertThat(od.get("counts").get("installments_overdue").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(od.get("counts").get("late_fees").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode second = get("/v1/loans/" + loan + "/schedule").get("data").get(1);
        assertThat(second.get("due_date").asString()).isEqualTo("2026-12-07");
        assertThat(second.get("status").asString()).isEqualTo("OVERDUE");
        assertThat(dec(second, "fees_due")).isEqualByComparingTo("15");
        post("/v1/loans/eod/delinquency?business_date=2026-12-10", null, "business-day", 200);
        JsonNode l = get("/v1/loans/" + loan);
        assertThat(l.get("status").asString()).isEqualTo("IN_ARREARS");
        assertThat(l.get("days_past_due").asInt()).isEqualTo(3);
        assertThat(l.get("delinquency_bucket").asString()).isEqualTo("DPD_1_29");
        assertThat(dec(l, "overdue_amount")).isGreaterThan(BigDecimal.ZERO);
        JsonNode history = get("/v1/loans/" + loan + "/status-history").get("data");
        assertThat(history.get(0).get("to_status").asString()).isEqualTo("IN_ARREARS");
        assertThat(history.get(0).get("customer_id").asString()).isEqualTo(CUSTOMER);
        assertThat(events("lending.loan.delinquency_changed", null)).isGreaterThanOrEqualTo(1);
        assertThat(events("lending.loan.installment_overdue", second.get("id").asString())).isEqualTo(1);
    }

    @Test
    @Order(8)
    void repaymentWaterfallCuresArrearsAndExcessReducesTheTerm() throws Exception {
        JsonNode l = get("/v1/loans/" + loan);
        BigDecimal overdue = dec(l, "overdue_amount");
        JsonNode rep = post("/v1/loans/" + loan + "/repay", """
                {"amount":"%s","source":"MANUAL","source_account_id":"%s"}""".formatted(overdue.toPlainString(), accountA), "ops", 200);
        // fees first, then penalties, interest and principal
        assertThat(dec(rep, "allocated_fees")).isEqualByComparingTo("15");
        assertThat(dec(rep, "allocated_penalties")).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(rep.get("allocation_detail").get(0).get("component").asString()).isEqualTo("FEE");
        FakeLedger.Call posting = ledger.lastPosting();
        assertLeg(posting, FEE_RECEIVABLE, "CREDIT", "15");
        assertLeg(posting, deposits.get(accountA).toString(), "DEBIT", overdue.toPlainString());
        JsonNode cured = get("/v1/loans/" + loan);
        assertThat(cured.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(cured.get("days_past_due").asInt()).isZero();
        assertThat(dec(cured, "overdue_amount")).isEqualByComparingTo("0");

        // validation and a waterfall rule check
        assertThat(post("/v1/loans/" + loan + "/repay", "{\"amount\":\"-5\",\"source\":\"MANUAL\"}", "ops", 422).get("code").asString())
                .isEqualTo("invalid_amount");
        assertThat(post("/v1/allocation-rules", """
                {"name":"bad","order_of_components":["FEE","FEE"],"valid_from":"2026-01-01"}""", "ops", 422).get("code").asString())
                .isEqualTo("duplicate_component");

        // an excess payment prepays principal and shortens the schedule (REDUCE_TERM)
        int before = countOpen(get("/v1/loans/" + loan + "/schedule").get("data"));
        JsonNode excess = post("/v1/loans/" + loan + "/repay", """
                {"amount":"2500","source":"MANUAL","source_account_id":"%s"}""".formatted(accountA), "ops", 200);
        assertThat(dec(excess, "allocated_principal")).isEqualByComparingTo("2500");
        int after = countOpen(get("/v1/loans/" + loan + "/schedule").get("data"));
        assertThat(after).isLessThan(before);

        // reversal restores the balances and is refused twice
        BigDecimal principalBefore = dec(get("/v1/loans/" + loan), "outstanding_principal");
        JsonNode rev = post("/v1/loan-repayments/" + excess.get("id").asString() + "/reverse", "{\"reason\":\"returned\"}", "ops", 200);
        assertThat(rev.get("status").asString()).isEqualTo("REVERSED");
        assertThat(rev.get("reversal_of_id").asString()).isEqualTo(excess.get("id").asString());
        assertThat(dec(get("/v1/loans/" + loan), "outstanding_principal")).isEqualByComparingTo(principalBefore.add(new BigDecimal("2500")));
        assertThat(post("/v1/loan-repayments/" + excess.get("id").asString() + "/reverse", "{\"reason\":\"again\"}", "ops", 409)
                .get("code").asString()).isEqualTo("already_reversed");
        assertThat(events("lending.loan.repayment_reversed", null)).isEqualTo(1);
    }

    @Test
    @Order(9)
    void manualFeeWaiverAndInstalmentWaiver() throws Exception {
        JsonNode fee = post("/v1/loan-fee-charges", """
                {"loan_id":"%s","fee_type":"DOCUMENTATION","amount":"20","currency":"EUR","charge_date":"2026-12-10"}""".formatted(loan),
                "ops", 201);
        assertLeg(ledger.lastPosting(), FEE_RECEIVABLE, "DEBIT", "20");
        assertThat(dec(get("/v1/loans/" + loan), "outstanding_fees")).isEqualByComparingTo("20");
        JsonNode waived = post("/v1/loan-fee-charges/" + fee.get("id").asString() + "/waive", "{\"waiver_reason\":\"goodwill\"}", "ops", 200);
        assertThat(waived.get("status").asString()).isEqualTo("WAIVED");
        assertThat(waived.get("waived_by").asString()).isEqualTo("ops");
        assertThat(dec(get("/v1/loans/" + loan), "outstanding_fees")).isEqualByComparingTo("0");
        assertThat(post("/v1/loan-fee-charges/" + fee.get("id").asString() + "/waive", "{\"waiver_reason\":\"x\"}", "ops", 409)
                .get("code").asString()).isEqualTo("invalid_transition");
        assertThat(post("/v1/loan-fee-charges", """
                {"loan_id":"%s","fee_type":"OTHER","amount":"20","currency":"USD","charge_date":"2026-12-10"}""".formatted(loan), "ops", 422).get("code").asString())
                .isEqualTo("currency_mismatch");

        // the BNPL customer missed the first instalment; it is waived as goodwill (principal against the waiver expense,
        // the late fee against fee income)
        JsonNode first = get("/v1/loans/" + bnplLoan + "/schedule").get("data").get(0);
        assertThat(first.get("status").asString()).isEqualTo("OVERDUE");
        JsonNode waivedInst = post("/v1/schedule-installments/" + first.get("id").asString() + "/waive",
                "{\"reason\":\"goodwill\"}", "ops", 200);
        assertThat(waivedInst.get("status").asString()).isEqualTo("WAIVED");
        assertThat(dec(waivedInst, "waived_amount")).isGreaterThanOrEqualTo(new BigDecimal("115"));
        assertThat(dec(waivedInst, "principal_due")).isEqualByComparingTo("0");
        assertLeg(ledger.lastPosting(), "0199b000-0000-7000-8000-0000000000ee", "DEBIT", "100");
        assertThat(dec(get("/v1/loans/" + bnplLoan), "outstanding_principal")).isEqualByComparingTo("150");
        assertThat(post("/v1/schedule-installments/" + first.get("id").asString() + "/waive", "{\"reason\":\"again\"}", "ops", 409)
                .get("code").asString()).isEqualTo("invalid_transition");
    }

    // ------------------------------------------------------------------ 7. rate change and restructure

    @Test
    @Order(10)
    void rateChangeRegeneratesTheFutureSchedule() throws Exception {
        JsonNode change = post("/v1/loans/" + loan + "/change-rate", """
                {"new_rate":"0.10","effective_date":"2026-12-10","reason":"REPRICING"}""", "ops", 200);
        assertThat(dec(change, "old_rate")).isEqualByComparingTo("0.12");
        assertThat(change.get("new_schedule_id").isNull()).isFalse();
        assertThat(dec(get("/v1/loans/" + loan), "interest_rate")).isEqualByComparingTo("0.10");
        assertThat(post("/v1/loans/" + loan + "/change-rate", """
                {"new_rate":"0.09","effective_date":"2026-12-01","reason":"REPRICING"}""", "ops", 422).get("code").asString())
                .isEqualTo("effective_date_before_last_accrual");
        assertThat(events("lending.loan.rate_changed", null)).isEqualTo(1);
    }

    @Test
    @Order(11)
    void restructureNeedsFourEyesAndCreatesANewSchedule() throws Exception {
        JsonNode r = post("/v1/loans/" + loan + "/restructure", """
                {"restructure_type":"TERM_EXTENSION","reason":"hardship: reduced income","new_term_months":24}""", "maker", 200);
        String id = r.get("id").asString();
        assertThat(r.get("status").asString()).isEqualTo("PROPOSED");
        assertThat(r.get("proposed_schedule")).hasSize(24);
        assertThat(post("/v1/loans/" + loan + "/restructure", """
                {"restructure_type":"RESCHEDULE","reason":"again","new_term_months":12}""", "maker", 409).get("code").asString())
                .isEqualTo("restructure_in_progress");
        assertThat(post("/v1/loan-restructures/" + id + "/apply", null, "maker", 409).get("code").asString()).isEqualTo("invalid_transition");
        post("/v1/loan-restructures/" + id + "/submit", null, "maker", 200);
        assertThat(post("/v1/loan-restructures/" + id + "/approve", "{}", "maker", 422).get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode approved = post("/v1/loan-restructures/" + id + "/approve", "{\"comment\":\"ok\"}", "checker", 200);
        assertThat(approved.get("approved_by").asString()).isEqualTo("checker");
        JsonNode applied = post("/v1/loan-restructures/" + id + "/apply", null, "checker", 200);
        assertThat(applied.get("status").asString()).isEqualTo("APPLIED");
        JsonNode l = get("/v1/loans/" + loan);
        assertThat(l.get("status").asString()).isEqualTo("RESTRUCTURED");
        assertThat(l.get("is_forborne").asBoolean()).isTrue();
        assertThat(l.get("ecl_stage").asString()).isEqualTo("STAGE_2");
        assertThat(countOpen(get("/v1/loans/" + loan + "/schedule").get("data"))).isEqualTo(24);
        assertThat(events("lending.loan.restructured", id)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 8. prepayment and close

    @Test
    @Order(12)
    void fullPrepaymentPaysOffAndCloseZeroesTheReceivable() throws Exception {
        JsonNode partial = post("/v1/loans/" + loan + "/prepayment-quote", """
                {"quote_type":"PARTIAL","requested_amount":"1000","partial_option":"REDUCE_INSTALMENT"}""", "ops", 200);
        assertThat(dec(partial, "prepayment_penalty")).isEqualByComparingTo("10");
        JsonNode prepaid = post("/v1/loans/" + loan + "/prepay", """
                {"loan_prepayment_quote_id":"%s","source":"MANUAL","source_account_id":"%s"}""".formatted(partial.get("id").asString(), accountA),
                "ops", 200);
        assertThat(dec(prepaid, "amount")).isEqualByComparingTo("1010");
        assertThat(post("/v1/loans/" + loan + "/prepay", """
                {"loan_prepayment_quote_id":"%s"}""".formatted(partial.get("id").asString()), "ops", 409).get("code").asString())
                .isEqualTo("quote_not_usable");

        JsonNode quote = post("/v1/loans/" + loan + "/prepayment-quote", "{\"quote_type\":\"FULL\"}", "ops", 200);
        JsonNode l = get("/v1/loans/" + loan);
        assertThat(dec(quote, "principal")).isEqualByComparingTo(dec(l, "outstanding_principal"));
        assertThat(dec(quote, "prepayment_penalty")).isEqualByComparingTo(dec(l, "outstanding_principal").multiply(new BigDecimal("0.01"))
                .setScale(2, java.math.RoundingMode.HALF_EVEN));
        assertThat(quote.get("valid_until").asString()).isNotBlank();
        assertThat(post("/v1/loans/" + loan + "/close", null, "ops", 409).get("code").asString()).isEqualTo("invalid_transition");
        JsonNode payoff = post("/v1/loans/" + loan + "/prepay", """
                {"loan_prepayment_quote_id":"%s","source":"MANUAL","source_account_id":"%s"}""".formatted(quote.get("id").asString(), accountA),
                "ops", 200);
        assertThat(dec(payoff, "amount")).isEqualByComparingTo(dec(quote, "total_amount"));
        JsonNode paidOff = get("/v1/loans/" + loan);
        assertThat(paidOff.get("status").asString()).isEqualTo("PAID_OFF");
        assertThat(dec(paidOff, "total_outstanding")).isEqualByComparingTo("0");
        assertThat(dec(paidOff, "outstanding_interest")).isEqualByComparingTo("0");
        // every receivable movement balanced out: disbursement, repayments, reversal, prepayments
        assertThat(ledger.rawBalance(UUID.fromString(paidOff.get("ledger_account_id").asString()))).isEqualByComparingTo("0");
        JsonNode closed = post("/v1/loans/" + loan + "/close", null, "ops", 200);
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(ledger.statusOf(UUID.fromString(paidOff.get("ledger_account_id").asString()))).isEqualTo("CLOSED");
        assertThat(events("lending.loan.paid_off", loan)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 9. write-off, recovery, cancellation

    @Test
    @Order(13)
    void writeOffWithFourEyesAndRecovery() throws Exception {
        loan2 = post("/v1/loans", loanBody("2000", 6, "EQUAL_PRINCIPAL"), "ops", 201).get("id").asString();
        post("/v1/loans/" + loan2 + "/disburse", "{\"method\":\"DEPOSIT_ACCOUNT\"}", "ops", 200);
        JsonNode w = post("/v1/loans/" + loan2 + "/write-off", "{\"write_off_type\":\"FULL\",\"reason\":\"uncollectable\"}", "maker", 200);
        assertThat(w.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(dec(w, "principal_amount")).isEqualByComparingTo("2000");
        assertThat(post("/v1/loans/" + loan2 + "/write-off", "{\"write_off_type\":\"FULL\",\"reason\":\"dup\"}", "maker", 409)
                .get("code").asString()).isEqualTo("write_off_pending");
        assertThat(post("/v1/loan-write-offs/" + w.get("id").asString() + "/approve", "{}", "maker", 422).get("code").asString())
                .isEqualTo("four_eyes_violation");
        JsonNode posted = post("/v1/loan-write-offs/" + w.get("id").asString() + "/approve", "{\"comment\":\"ok\"}", "checker", 200);
        assertThat(posted.get("status").asString()).isEqualTo("POSTED");
        FakeLedger.Call wo = ledger.lastPosting();
        assertLeg(wo, WRITE_OFF_EXPENSE, "DEBIT", posted.get("total_amount").asString());
        JsonNode l = get("/v1/loans/" + loan2);
        assertThat(l.get("status").asString()).isEqualTo("WRITTEN_OFF");
        assertThat(l.get("ecl_stage").asString()).isEqualTo("STAGE_3");
        assertThat(ledger.rawBalance(UUID.fromString(l.get("ledger_account_id").asString()))).isEqualByComparingTo("0");
        assertThat(post("/v1/loans/" + loan2 + "/repay", "{\"amount\":\"10\",\"source\":\"MANUAL\"}", "ops", 409).get("code").asString())
                .isEqualTo("loan_written_off");

        JsonNode rec = post("/v1/loan-recoveries", """
                {"loan_id":"%s","amount":"300","currency":"EUR","source":"AGENCY","agency_commission":"30","received_at":"2026-12-10T10:00:00Z"}"""
                .formatted(loan2), "ops", 201);
        assertThat(rec.get("loan_write_off_id").asString()).isEqualTo(w.get("id").asString());
        FakeLedger.Call rp = ledger.lastPosting();
        assertLeg(rp, CLEARING, "DEBIT", "270");
        assertLeg(rp, AGENCY, "DEBIT", "30");
        assertLeg(rp, RECOVERY_INCOME, "CREDIT", "300");
        assertThat(dec(get("/v1/loan-write-offs/" + w.get("id").asString()), "recovered_amount")).isEqualByComparingTo("300");
        assertThat(post("/v1/loan-recoveries", """
                {"loan_id":"%s","amount":"10","currency":"EUR","source":"CUSTOMER","received_at":"2026-12-10T10:00:00Z"}""".formatted(loan), "ops", 409)
                .get("code").asString()).isEqualTo("loan_not_written_off");
    }

    @Test
    @Order(14)
    void cancelWithinCoolingOffReversesTheDisbursement() throws Exception {
        String l3 = post("/v1/loans", loanBody("1000", 6, "BULLET"), "ops", 201).get("id").asString();
        JsonNode pending = post("/v1/loans/" + l3 + "/cancel", "{\"reason\":\"customer changed mind\"}", "ops", 200);
        assertThat(pending.get("status").asString()).isEqualTo("CANCELLED");

        String l4 = post("/v1/loans", loanBody("1000", 6, "INTEREST_ONLY"), "ops", 201).get("id").asString();
        BigDecimal before = ledger.balanceOf(deposits.get(accountA));
        post("/v1/loans/" + l4 + "/disburse", "{\"method\":\"DEPOSIT_ACCOUNT\"}", "ops", 200);
        JsonNode cancelled = post("/v1/loans/" + l4 + "/cancel", "{\"reason\":\"cooling-off withdrawal\"}", "ops", 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(ledger.balanceOf(deposits.get(accountA))).isEqualByComparingTo(before);
        assertThat(ledger.rawBalance(UUID.fromString(cancelled.get("ledger_account_id").asString()))).isEqualByComparingTo("0");
        assertThat(post("/v1/loans/" + l4 + "/cancel", "{\"reason\":\"again\"}", "ops", 409).get("code").asString()).isEqualTo("invalid_transition");
    }

    // ------------------------------------------------------------------ 10. isolation

    @Test
    @Order(15)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/loans/" + loan, null, null, "ops", OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/v1/loans/" + loan + "/repay", "{\"amount\":\"1\",\"source\":\"MANUAL\"}",
                UUID.randomUUID().toString(), "ops", OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(json.readTree(send("GET", "/v1/loans", null, null, "ops", OTHER_TENANT).body()).get("data")).isEmpty();
        JsonNode run = json.readTree(send("POST", "/v1/loans/eod/accrue?business_date=2026-10-05", null, UUID.randomUUID().toString(),
                "ops", OTHER_TENANT).body());
        assertThat(run.get("replayed").asBoolean()).isFalse();
        assertThat(run.get("counts").get("loans").asInt()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private String loanBody(String principal, int term, String scheduleType) {
        return """
                {"customer_id":"%s","product_id":"%s","product_version_id":"%s","legal_entity_id":"%s","loan_type":"INSTALMENT",
                 "currency":"EUR","principal_amount":"%s","interest_rate_type":"FIXED","interest_rate":"0.12","schedule_type":"%s",
                 "repayment_frequency":"MONTHLY","term_months":%d,"disbursement_account_id":"%s","repayment_account_id":"%s"}"""
                .formatted(CUSTOMER, PRODUCT, VERSION, LEGAL_ENTITY, principal, scheduleType, term, accountA, accountA);
    }

    private String bnplBody(String order, String amount) {
        return """
                {"customer_id":"%s","merchant_id":"%s","product_id":"%s","product_version_id":"%s","legal_entity_id":"%s",
                 "merchant_order_ref":"%s","purchase_amount":"%s","installment_count":4,"merchant_discount_rate":"0.03",
                 "merchant_settlement_account_id":"%s","currency":"EUR"}"""
                .formatted(CUSTOMER_2, MERCHANT, PRODUCT, VERSION, LEGAL_ENTITY, order, amount, merchantAccount);
    }

    private static int countOpen(JsonNode schedule) {
        int n = 0;
        for (JsonNode i : schedule) {
            if (List.of("SCHEDULED", "DUE", "PARTIALLY_PAID", "OVERDUE").contains(i.get("status").asString())) {
                n++;
            }
        }
        return n;
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

    private int events(String type, String aggregateId) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", () -> jdbc.sql("""
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
            b.header("If-Match", "\"" + rowVersion(path) + "\"");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int rowVersion(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "ops").GET().build(), HttpResponse.BodyHandlers.ofString());
        return json.readTree(r.body()).path("row_version").asInt(1);
    }
}
