package io.meridian.treasurydeal;

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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
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
 * treasury-deal-service end to end over HTTP against PostgreSQL 18 (RLS enforced). ledger-service, payment-service and
 * reference-data-service are stubbed behind a mocked {@link ServiceClient}; every call is recorded so the test can
 * assert the postings, payments and rate look-ups the service made.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.treasury.ledger-id=0199a000-0000-7000-8000-0000000000aa",
        "meridian.treasury.our-bic=MERIGB2LXXX",
        "meridian.treasury.accounts.EUR.nostro=0199a000-0000-7000-8000-00000000e001",
        "meridian.treasury.accounts.EUR.mm-placements=0199a000-0000-7000-8000-00000000e002",
        "meridian.treasury.accounts.EUR.mm-borrowings=0199a000-0000-7000-8000-00000000e003",
        "meridian.treasury.accounts.EUR.interest-receivable=0199a000-0000-7000-8000-00000000e004",
        "meridian.treasury.accounts.EUR.interest-payable=0199a000-0000-7000-8000-00000000e005",
        "meridian.treasury.accounts.EUR.interest-income=0199a000-0000-7000-8000-00000000e006",
        "meridian.treasury.accounts.EUR.interest-expense=0199a000-0000-7000-8000-00000000e007",
        "meridian.treasury.accounts.EUR.fx-position=0199a000-0000-7000-8000-00000000e008",
        "meridian.treasury.accounts.EUR.securities=0199a000-0000-7000-8000-00000000e009",
        "meridian.treasury.accounts.EUR.realised-pnl=0199a000-0000-7000-8000-00000000e010",
        "meridian.treasury.accounts.USD.nostro=0199a000-0000-7000-8000-00000000d001",
        "meridian.treasury.accounts.USD.fx-position=0199a000-0000-7000-8000-00000000d008",
        "meridian.treasury.accounts.USD.realised-pnl=0199a000-0000-7000-8000-00000000d010"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TreasuryDealFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000a001";
    static final String OTHER = "0199a000-0000-7000-8000-00000000a002";
    static final String ENTITY = "0199a000-0000-7000-8000-00000000b001";
    static final String DEALER = "0199a000-0000-7000-8000-00000000b002";
    static final String EUR_NOSTRO = "0199a000-0000-7000-8000-00000000e001";
    static final String PLACEMENTS = "0199a000-0000-7000-8000-00000000e002";
    static final String RECEIVABLE = "0199a000-0000-7000-8000-00000000e004";
    static final String INCOME = "0199a000-0000-7000-8000-00000000e006";

    record Call(String service, String method, String path, JsonNode body, String key) {}

    @Value("${local.server.port}")
    int port;
    @Autowired
    JsonMapper json;
    @Autowired
    JdbcClient jdbc;
    @MockitoBean
    ServiceClient serviceClient;
    @Autowired
    io.meridian.web.time.BusinessDates businessDates;
    /** ENTITY's business date reported by the stubbed business-day-service (null: none, the database date applies). */
    volatile LocalDate businessDate;

    final HttpClient http = HttpClient.newHttpClient();
    final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    LocalDate today;
    String bank;
    String mmLimit;
    String settlementLimit;
    String placement;

    @BeforeAll
    void today() {
        today = jdbc.sql("select current_date").query(LocalDate.class).single();
    }

    /** Mockito beans are reset after each test, so the stubs are attached again every time. */
    @BeforeEach
    void stub() {
        when(serviceClient.post(anyString(), anyString(), any(), any())).thenAnswer(i -> {
            JsonNode body = json.valueToTree(i.getArgument(2));
            String key = i.getArgument(3);
            calls.add(new Call(i.getArgument(0), "POST", i.getArgument(1), body, key));
            return json.readTree("{\"id\":\"" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)) + "\"}");
        });
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> {
            String path = i.getArgument(1);
            if ("config-service".equals(i.getArgument(0))) { // no tenant ledger settings: deployment properties apply
                return json.readTree("{\"namespace\":\"ledger\",\"values\":{}}");
            }
            if ("business-day-service".equals(i.getArgument(0))) {
                return businessDate != null && path.equals("/v1/operations/business-days/current?legal_entity_id=" + ENTITY)
                        ? json.readTree("{\"business_date\":\"" + businessDate + "\"}") : null;
            }
            calls.add(new Call(i.getArgument(0), "GET", path, null, null));
            if (path.startsWith("/v1/reference/fx-rates/latest?base=EUR&quote=USD")) {
                return json.readTree("{\"rate\":\"1.0900000000\"}");
            }
            throw new ApiException(404, "not_found", "no rate");
        });
    }

    // ------------------------------------------------------------------ 1. counterparties and SSIs

    @Test
    @Order(1)
    void counterpartyOnboardingIsMakerChecker() throws Exception {
        assertThat(post("/v1/treasury/counterparties", counterparty("BADBIC", "DEUT1EFF"), "maker", 422)
                .get("code").asString()).isEqualTo("invalid_bic");
        assertThat(post("/v1/treasury/counterparties", counterparty("BADLEI", "DEUTDEFF").replace("529900T8BM49AURSDO55",
                "529900T8BM49AURSDO56"), "maker", 422).get("code").asString()).isEqualTo("invalid_lei");
        HttpResponse<String> created = send("POST", "/v1/treasury/counterparties", counterparty("DB", "DEUTDEFF"),
                "cp-db", "maker", TENANT);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode cp = json.readTree(created.body());
        bank = cp.get("id").asString();
        assertThat(cp.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        HttpResponse<String> replay = send("POST", "/v1/treasury/counterparties", counterparty("DB", "DEUTDEFF"),
                "cp-db", "maker", TENANT);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(post("/v1/treasury/counterparties", counterparty("DB", "DEUTDEFF"), "maker", 409).get("code").asString())
                .isEqualTo("duplicate_code");

        assertThat(post("/v1/treasury/counterparties/" + bank + "/approve", "{}", "maker", 422).get("code").asString())
                .isEqualTo("four_eyes_violation");
        JsonNode approved = post("/v1/treasury/counterparties/" + bank + "/approve", "{\"comment\":\"KYC ok\"}", "checker", 200);
        assertThat(approved.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(approved.get("approved_by").asString()).isEqualTo("checker");
        assertThat(post("/v1/treasury/counterparties/" + bank + "/approve", "{}", "checker", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(post("/v1/treasury/counterparties/" + bank + "/reactivate", "{\"reason\":\"x\"}", "ops", 409)
                .get("code").asString()).isEqualTo("invalid_transition");

        for (String ccy : List.of("EUR", "USD")) {
            JsonNode ssi = post("/v1/treasury/ssis", """
                    {"treasury_counterparty_id":"%s","currency":"%s","beneficiary_bic":"DEUTDEFFXXX",
                     "beneficiary_account":"DE89370400440532013000","valid_from":"2020-01-01"}""".formatted(bank, ccy),
                    "maker", 201);
            assertThat(ssi.hasNonNull("verified_by")).isFalse();
            String ssiId = ssi.get("id").asString();
            assertThat(post("/v1/treasury/ssis/" + ssiId + "/verify", "{}", "maker", 422).get("code").asString())
                    .isEqualTo("four_eyes_violation");
            assertThat(post("/v1/treasury/ssis/" + ssiId + "/verify", "{}", "checker", 200).get("verified_by").asString())
                    .isEqualTo("checker");
        }
        assertThat(events("treasury.counterparty.created")).isEqualTo(1);
        assertThat(events("treasury.counterparty.approved")).isEqualTo(1);
        assertThat(events("treasury.ssi.verified")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 2. limits

    @Test
    @Order(2)
    void limitsNeedApprovalAndPreDealCheck() throws Exception {
        mmLimit = limit("MONEY_MARKET", "EUR", "5000000", null);
        settlementLimit = limit("SETTLEMENT", "EUR", "10000000", null);
        limit("SETTLEMENT", "USD", "10000000", null);
        limit("PRE_SETTLEMENT", "EUR", "1000000", null);
        limit("FX", "EUR", "20000000", null);
        limit("FX", "USD", "20000000", null);
        limit("ISSUER", "EUR", "50000000", null);
        String tenor = limit("TENOR", "EUR", "50000000", 400);
        assertThat(get("/v1/treasury/limits/" + tenor).get("status").asString()).isEqualTo("ACTIVE");

        JsonNode check = post("/v1/treasury/limits/check", """
                {"treasury_counterparty_id":"%s","limit_type":"MONEY_MARKET","amount":"6000000","currency":"EUR"}"""
                .formatted(bank), "dealer", 200);
        assertThat(check.get("breach").asBoolean()).isTrue();
        assertThat(check.get("approved").asBoolean()).isFalse();
        JsonNode ok = post("/v1/treasury/limits/check", """
                {"treasury_counterparty_id":"%s","limit_type":"MONEY_MARKET","amount":"4500000","currency":"EUR"}"""
                .formatted(bank), "dealer", 200);
        assertThat(ok.get("approved").asBoolean()).isTrue();
        assertThat(ok.get("limits").get(0).get("warning").asBoolean()).isTrue();
        // increasing an active limit needs a new approval
        HttpResponse<String> patched = send("PATCH", "/v1/treasury/limits/" + mmLimit, "{\"limit_amount\":\"6000000\"}",
                null, "maker", TENANT);
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        assertThat(json.readTree(patched.body()).get("status").asString()).isEqualTo("PENDING_APPROVAL");
        post("/v1/treasury/limits/" + mmLimit + "/approve", "{}", "checker", 200);
        assertThat(post("/v1/treasury/limits", """
                {"treasury_counterparty_id":"%s","limit_type":"TENOR","currency":"EUR","limit_amount":"1","valid_from":"2020-01-01"}"""
                .formatted(bank), "maker", 422).get("code").asString()).isEqualTo("missing_field");
    }

    // ------------------------------------------------------------------ 3. money market placement end to end

    @Test
    @Order(3)
    void placementCaptureVerifyConfirmSettleAccrue() throws Exception {
        JsonNode deal = post("/v1/treasury/money-market-deals", mm("PLACEMENT", "2000000", today, today, today.plusDays(90)),
                "dealer", 201);
        placement = deal.get("id").asString();
        assertThat(deal.get("deal_ref").asString()).startsWith("MM");
        assertThat(dec(deal, "interest_amount")).isEqualByComparingTo("25000.00");
        assertThat(dec(deal, "maturity_amount")).isEqualByComparingTo("2025000.00");
        assertThat(dec(get("/v1/treasury/limits/" + mmLimit), "utilised_amount")).isEqualByComparingTo("2000000");
        assertThat(dec(get("/v1/treasury/limits/" + settlementLimit), "utilised_amount")).isEqualByComparingTo("2000000");
        assertThat(get("/v1/treasury/limits/" + mmLimit + "/utilisations").get("data")).hasSize(1);

        assertThat(post("/v1/treasury/money-market-deals", mm("PLACEMENT", "4500000", today, today, today.plusDays(30)),
                "dealer", 422).get("code").asString()).isEqualTo("limit_breached");
        assertThat(post("/v1/treasury/money-market-deals", mm("PLACEMENT", "100000", today, today, today.plusDays(500)),
                "dealer", 422).get("code").asString()).isEqualTo("tenor_limit_exceeded");
        assertThat(post("/v1/treasury/money-market-deals", mm("PLACEMENT", "100000", today, today.plusDays(5), today.plusDays(2)),
                "dealer", 422).get("code").asString()).isEqualTo("invalid_dates");
        assertThat(post("/v1/treasury/money-market-deals", mm("REPO", "100000", today, today, today.plusDays(5)),
                "dealer", 422).get("code").asString()).isEqualTo("gmra_required");

        String mmPath = "/v1/treasury/money-market-deals/" + placement;
        assertThat(post(mmPath + "/settle", null, "ops", 409).get("code").asString()).isEqualTo("invalid_transition");
        assertThat(post(mmPath + "/verify", null, "dealer", 422).get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode verified = post(mmPath + "/verify", null, "backoffice", 200);
        assertThat(verified.get("status").asString()).isEqualTo("VERIFIED");
        assertThat(post(mmPath + "/confirm", "{}", "backoffice", 422).get("code").asString()).isEqualTo("confirmation_not_matched");

        // outbound MT320 and the counterparty's mirror confirmation
        JsonNode sent = post("/v1/treasury/confirmations/generate",
                "{\"deal_type\":\"MONEY_MARKET\",\"deal_id\":\"" + placement + "\"}", "backoffice", 200);
        assertThat(sent.get("status").asString()).isEqualTo("SENT");
        assertThat(sent.get("message_type").asString()).isEqualTo("MT320");
        String text = getText("/v1/treasury/confirmations/" + sent.get("id").asString() + "/message");
        assertThat(text).contains(":17R:L", ":32B:EUR2000000,", ":87A:DEUTDEFF");
        assertThat(getText(mmPath + "/confirmation-message")).contains(":20:" + deal.get("deal_ref").asString());

        String wrong = theirMt320("1999000");
        JsonNode unmatched = post("/v1/treasury/confirmations", inbound(wrong, placement), "backoffice", 201);
        assertThat(unmatched.get("status").asString()).isEqualTo("UNMATCHED");
        assertThat(unmatched.get("mismatch_fields").get(0).get("field").asString()).isEqualTo("principal");
        assertThat(get(mmPath).get("status").asString()).isEqualTo("VERIFIED");
        JsonNode inbound = post("/v1/treasury/confirmations", inbound(theirMt320("2000000"), null), "backoffice", 201);
        assertThat(inbound.get("status").asString()).isEqualTo("RECEIVED");
        assertThat(inbound.get("fields").get("party_role").asString()).isEqualTo("BORROWER");
        JsonNode matched = post("/v1/treasury/confirmations/" + inbound.get("id").asString() + "/match",
                "{\"deal_id\":\"" + placement + "\"}", "backoffice", 200);
        assertThat(matched.get("status").asString()).isEqualTo("MATCHED");
        assertThat(get(mmPath).get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(post("/v1/treasury/confirmations/" + unmatched.get("id").asString() + "/dispute",
                "{\"reason\":\"wrong amount\"}", "backoffice", 200).get("status").asString()).isEqualTo("DISPUTED");

        // settlement: ledger posting, instruction, SETTLEMENT limit released
        JsonNode settled = post(mmPath + "/settle", null, "ops", 200);
        assertThat(settled.get("status").asString()).isEqualTo("SETTLED");
        assertThat(settled.get("ledger_account_id").asString()).isEqualTo(PLACEMENTS);
        Call posting = lastPosting("mm-settle:" + placement);
        assertLeg(posting, PLACEMENTS, "DEBIT", "2000000");
        assertLeg(posting, EUR_NOSTRO, "CREDIT", "2000000");
        assertThat(dec(get("/v1/treasury/limits/" + settlementLimit), "utilised_amount")).isZero();
        JsonNode stl = get("/v1/treasury/settlements?deal_id=" + placement).get("data").get(0);
        assertThat(stl.get("flow_type").asString()).isEqualTo("PRINCIPAL_OUT");
        assertThat(stl.get("direction").asString()).isEqualTo("CREDIT");
        assertThat(stl.hasNonNull("treasury_ssi_id")).isTrue();
        String stlId = stl.get("id").asString();
        JsonNode instructed = post("/v1/treasury/settlements/" + stlId + "/instruct", null, "ops", 200);
        assertThat(instructed.get("status").asString()).isEqualTo("INSTRUCTED");
        assertThat(instructed.hasNonNull("payment_id")).isTrue();
        Call payment = calls.stream().filter(c -> c.path().equals("/v1/payments")).reduce((a, b) -> b).orElseThrow();
        assertThat(payment.body().get("creditor_account_number").asString()).isEqualTo("DE89370400440532013000");
        assertThat(payment.body().get("creditor_account_id_type").asString()).isEqualTo("IBAN");
        assertThat(payment.body().get("amount").asString()).isEqualTo("2000000.00000000");
        assertThat(post("/v1/treasury/settlements/" + stlId + "/mark-settled", "{\"reference\":\"NOSTRO-1\"}", "ops", 200)
                .get("status").asString()).isEqualTo("SETTLED");
        assertThat(post("/v1/treasury/settlements/" + stlId + "/fail", "{\"reason\":\"x\"}", "ops", 409)
                .get("code").asString()).isEqualTo("invalid_transition");

        // accrual: 2,000,000 x 5% / 360 = 277.78 for the first day
        JsonNode accrued = post(mmPath + "/accrue", "{\"as_of\":\"" + today + "\"}", "ops", 200);
        assertThat(dec(accrued, "accrued_interest")).isEqualByComparingTo("277.78");
        Call accrual = lastPosting("mm-accrual:" + placement);
        assertLeg(accrual, RECEIVABLE, "DEBIT", "277.78");
        assertLeg(accrual, INCOME, "CREDIT", "277.78");

        JsonNode flows = get("/v1/treasury/settlements/projected-flows?from=" + today + "&to=" + today.plusDays(100)
                + "&currency=EUR");
        assertThat(flows.get("flows")).anySatisfy(f -> {
            assertThat(f.get("deal_id").asString()).isEqualTo(placement);
            assertThat(f.get("flow_type").asString()).isEqualTo("MATURITY");
            assertThat(f.get("direction").asString()).isEqualTo("IN");
            assertThat(new BigDecimal(f.get("amount").asString())).isEqualByComparingTo("2025000");
        });
        JsonNode exposure = get("/v1/treasury/counterparties/" + bank + "/exposure");
        assertThat(exposure.get("outstanding")).anySatisfy(o -> assertThat(o.get("deal_family").asString()).isEqualTo("MONEY_MARKET"));
        assertThat(exposure.get("limits")).hasSize(8);
        assertThat(events("treasury.money_market_deal.settled")).isEqualTo(1);
        assertThat(events("treasury.confirmation.matched")).isEqualTo(1);
        assertThat(events("treasury.confirmation.unmatched")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 4. amend, cancel, rollover, EOD maturities

    @Test
    @Order(4)
    void amendCancelRolloverAndEndOfDayMaturity() throws Exception {
        JsonNode d = post("/v1/treasury/money-market-deals", mm("PLACEMENT", "100000", today, today, today.plusDays(30)),
                "dealer", 201);
        String id = d.get("id").asString();
        HttpResponse<String> amended = send("PATCH", "/v1/treasury/money-market-deals/" + id,
                "{\"interest_rate\":\"0.036\"}", null, "dealer", TENANT);
        assertThat(amended.statusCode()).as(amended.body()).isEqualTo(200);
        assertThat(dec(json.readTree(amended.body()), "interest_amount")).isEqualByComparingTo("300.00");
        JsonNode cancelled = post("/v1/treasury/money-market-deals/" + id + "/cancel", "{\"reason\":\"duplicate\"}", "dealer", 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(dec(get("/v1/treasury/limits/" + mmLimit), "utilised_amount")).isEqualByComparingTo("2000000");
        assertThat(post("/v1/treasury/money-market-deals/" + id + "/verify", null, "backoffice", 409).get("code").asString())
                .isEqualTo("invalid_transition");

        // a deal maturing today, rolled over with capitalised interest
        String rolled = settledDeal("1000000", today.minusDays(36), today);
        assertThat(post("/v1/treasury/money-market-deals/" + rolled + "/rollover",
                "{\"new_rate\":\"0.04\",\"rollover_type\":\"NONE\"}", "dealer", 422).get("code").asString())
                .isEqualTo("invalid_rollover_type");
        JsonNode fresh = post("/v1/treasury/money-market-deals/" + rolled + "/rollover",
                "{\"new_rate\":\"0.04\",\"rollover_type\":\"PRINCIPAL_AND_INTEREST\"}", "dealer", 200);
        // 1,000,000 x 5% x 36/360 = 5,000 capitalised
        assertThat(dec(fresh, "principal")).isEqualByComparingTo("1005000.00");
        assertThat(fresh.get("value_date").asString()).isEqualTo(today.toString());
        assertThat(fresh.get("maturity_date").asString()).isEqualTo(today.plusDays(36).toString());
        assertThat(fresh.get("rolled_from_deal_id").asString()).isEqualTo(rolled);
        JsonNode old = get("/v1/treasury/money-market-deals/" + rolled);
        assertThat(old.get("status").asString()).isEqualTo("MATURED");
        assertThat(old.get("rolled_into_deal_id").asString()).isEqualTo(fresh.get("id").asString());
        Call roll = lastPosting("mm-rollover:" + rolled);
        assertLeg(roll, PLACEMENTS, "DEBIT", "5000");

        // end-of-day maturity: repays principal and interest, idempotent per business date
        String due = settledDeal("500000", today.minusDays(72), today);
        JsonNode run = post("/v1/treasury/eod-runs/maturities?business_date=" + today, null, "system", 200);
        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(run.get("result").get("counts").get("money_market_matured").asInt()).isEqualTo(1);
        JsonNode matured = get("/v1/treasury/money-market-deals/" + due);
        assertThat(matured.get("status").asString()).isEqualTo("MATURED");
        Call maturity = lastPosting("mm-mature:" + due);
        assertLeg(maturity, EUR_NOSTRO, "DEBIT", "505000");
        assertThat(get("/v1/treasury/settlements?deal_id=" + due + "&flow_type=INTEREST_IN").get("data")).isNotEmpty();
        JsonNode replay = post("/v1/treasury/eod-runs/maturities?business_date=" + today, null, "system", 200);
        assertThat(replay.get("replayed").asBoolean()).isTrue();
        assertThat(get("/v1/treasury/eod-runs?job=MATURITY_PROCESSING").get("data")).hasSize(1);

        JsonNode accruals = post("/v1/treasury/eod-runs/accruals?business_date=" + today, null, "system", 200);
        assertThat(accruals.get("status").asString()).as(accruals.toString()).isEqualTo("COMPLETED");
        assertThat(events("treasury.money_market_deal.rolled_over")).isEqualTo(1);
        assertThat(events("treasury.eod_run.completed")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 5. FX

    @Test
    @Order(5)
    void fxForwardSpotAndNdf() throws Exception {
        assertThat(post("/v1/treasury/fx-deals", fx("FORWARD", today, today.plusDays(90), "1.0880", "1.0850", "25.5"),
                "dealer", 422).get("code").asString()).isEqualTo("forward_points_mismatch");
        JsonNode fwd = post("/v1/treasury/fx-deals", fx("FORWARD", today, today.plusDays(90), "1.08755", "1.0850", "25.5"),
                "dealer", 201);
        String fwdId = fwd.get("id").asString();
        assertThat(fwd.get("legs")).hasSize(1);
        assertThat(dec(fwd.get("legs").get(0), "quote_amount")).isEqualByComparingTo("1087550.00");
        assertThat(get("/v1/treasury/fx-deals/" + fwdId + "/legs").get("data")).hasSize(1);
        // PSE add-on 1% of EUR 1m on the pre-settlement limit
        JsonNode pse = get("/v1/treasury/limits?limit_type=PRE_SETTLEMENT").get("data").get(0);
        assertThat(dec(pse, "utilised_amount")).isEqualByComparingTo("10000");
        String fxPath = "/v1/treasury/fx-deals/" + fwdId;
        post(fxPath + "/verify", null, "backoffice", 200);
        assertThat(getText(fxPath + "/confirmation-message")).contains(":32B:EUR1000000,", ":33B:USD1087550,", ":36:1,08755");
        assertThat(post(fxPath + "/confirm", "{\"manual_reference\":\"PHONE-123\"}", "backoffice", 200).get("status").asString())
                .isEqualTo("CONFIRMED");
        JsonNode mtm = post(fxPath + "/revalue", "{\"valuation_date\":\"" + today + "\",\"market_rate\":\"1.0900\"}", "ops", 200);
        assertThat(dec(mtm, "mtm_value")).isEqualByComparingTo("2450.00");
        assertThat(mtm.get("mtm_currency").asString()).isEqualTo("USD");
        assertThat(post(fxPath + "/settle", "{\"leg_type\":\"NEAR\"}", "ops", 422).get("code").asString())
                .isEqualTo("value_date_not_reached");

        // spot settles today: receive EUR, pay USD
        JsonNode spot = post("/v1/treasury/fx-deals", fx("SPOT", today, today, "1.0850", null, null), "dealer", 201);
        String spotPath = "/v1/treasury/fx-deals/" + spot.get("id").asString();
        post(spotPath + "/verify", null, "backoffice", 200);
        post(spotPath + "/confirm", "{\"manual_reference\":\"BBG-1\"}", "backoffice", 200);
        JsonNode settled = post(spotPath + "/settle", "{\"leg_type\":\"NEAR\"}", "ops", 200);
        assertThat(settled.get("status").asString()).isEqualTo("SETTLED");
        assertThat(settled.get("legs").get(0).get("settlement_status").asString()).isEqualTo("SETTLED");
        JsonNode rows = get("/v1/treasury/settlements?deal_id=" + spot.get("id").asString()).get("data");
        assertThat(rows).hasSize(2);
        Call fxPosting = lastPosting("fx-settle:" + settled.get("legs").get(0).get("id").asString());
        assertThat(fxPosting.body().get("entries")).hasSize(4);

        // swap legs must be in opposite directions
        String badSwap = """
                {"legal_entity_id":"%s","treasury_counterparty_id":"%s","deal_type":"SWAP","trade_date":"%s","dealer_id":"%s",
                 "legs":[{"leg_type":"NEAR","buy_sell":"BUY","base_currency":"EUR","base_amount":"1000000","quote_currency":"USD",
                          "deal_rate":"1.085","value_date":"%s"},
                         {"leg_type":"FAR","buy_sell":"BUY","base_currency":"EUR","base_amount":"1000000","quote_currency":"USD",
                          "deal_rate":"1.0875","value_date":"%s"}]}"""
                .formatted(ENTITY, bank, today, DEALER, today.plusDays(2), today.plusDays(92));
        assertThat(post("/v1/treasury/fx-deals", badSwap, "dealer", 422).get("code").asString()).isEqualTo("invalid_legs");

        // NDF USD/INR bought USD 1m at 83.00, fixed at 83.50, settled net in USD
        String ndfBody = """
                {"legal_entity_id":"%s","treasury_counterparty_id":"%s","deal_type":"NDF","trade_date":"%s","dealer_id":"%s",
                 "settlement_currency":"USD","fixing_date":"%s","fixing_source":"RBI",
                 "legs":[{"buy_sell":"BUY","base_currency":"USD","base_amount":"1000000","quote_currency":"INR",
                          "deal_rate":"83.00","value_date":"%s"}]}"""
                .formatted(ENTITY, bank, today.minusDays(10), DEALER, today, today);
        JsonNode ndf = post("/v1/treasury/fx-deals", ndfBody.replace("\"settlement_currency\":\"USD\",", ""), "dealer", 422);
        assertThat(ndf.get("code").asString()).isEqualTo("missing_field");
        ndf = post("/v1/treasury/fx-deals", ndfBody, "dealer", 201);
        assertThat(ndf.get("non_deliverable").asBoolean()).isTrue();
        String ndfPath = "/v1/treasury/fx-deals/" + ndf.get("id").asString();
        post(ndfPath + "/verify", null, "backoffice", 200);
        JsonNode fixed = post(ndfPath + "/fix", "{\"fixing_rate\":\"83.50\"}", "ops", 200);
        assertThat(dec(fixed, "ndf_settlement_amount")).isEqualByComparingTo("5988.02");
        post(ndfPath + "/confirm", "{\"manual_reference\":\"NDF-1\"}", "backoffice", 200);
        post(ndfPath + "/settle", "{\"leg_type\":\"NEAR\"}", "ops", 200);
        JsonNode ndfStl = get("/v1/treasury/settlements?deal_id=" + ndf.get("id").asString()).get("data");
        assertThat(ndfStl).hasSize(1);
        assertThat(ndfStl.get(0).get("flow_type").asString()).isEqualTo("NDF_RECEIVE");
        assertThat(dec(ndfStl.get(0), "amount")).isEqualByComparingTo("5988.02");

        // end of day: MTM from the reference rate and limit utilisation refresh
        JsonNode mtmRun = post("/v1/treasury/eod-runs/mark-to-market?business_date=" + today, null, "system", 200);
        assertThat(mtmRun.get("result").get("counts").get("fx_deals_revalued").asInt()).isEqualTo(1);
        assertThat(dec(get(fxPath), "mtm_value")).isEqualByComparingTo("2450.00");
        JsonNode refresh = post("/v1/treasury/eod-runs/limit-utilisation?business_date=" + today, null, "system", 200);
        assertThat(refresh.get("status").asString()).as(refresh.toString()).isEqualTo("COMPLETED");
        // PSE = 1% x 1m + MTM 2,450 USD / 1.09 = 12,247.71 EUR
        assertThat(dec(get("/v1/treasury/limits?limit_type=PRE_SETTLEMENT").get("data").get(0), "utilised_amount"))
                .isEqualByComparingTo("12247.71");
        assertThat(post(fxPath + "/cancel", "{\"reason\":\"client request\"}", "dealer", 200).get("status").asString())
                .isEqualTo("CANCELLED");
        assertThat(dec(get("/v1/treasury/limits?limit_type=PRE_SETTLEMENT").get("data").get(0), "utilised_amount")).isZero();
    }

    // ------------------------------------------------------------------ 6. securities

    @Test
    @Order(6)
    void securitiesTradesHoldingsAndAnalytics() throws Exception {
        assertThat(post("/v1/treasury/securities", security("XS2010028938"), "ops", 422).get("code").asString())
                .isEqualTo("invalid_isin");
        JsonNode sec = post("/v1/treasury/securities", security("XS2010028939"), "ops", 201);
        String secId = sec.get("id").asString();
        JsonNode analytics = get("/v1/treasury/securities/" + secId + "/analytics?clean_price=99.5&settlement_date=" + today
                + "&nominal=1000000");
        BigDecimal accruedPct = dec(analytics, "accrued_interest_pct");
        assertThat(dec(analytics, "dirty_price")).isEqualByComparingTo(new BigDecimal("99.5").add(accruedPct)
                .setScale(10, java.math.RoundingMode.HALF_EVEN));
        assertThat(dec(analytics, "settlement_amount")).isEqualByComparingTo(dec(analytics, "clean_consideration")
                .add(dec(analytics, "accrued_amount")));

        JsonNode buy = post("/v1/treasury/securities-trades", trade(secId, "BUY", "1000000", "99.5", "AFS"), "dealer", 201);
        assertThat(dec(buy, "settlement_amount")).isEqualByComparingTo(dec(analytics, "settlement_amount"));
        assertThat(buy.get("currency").asString()).isEqualTo("EUR");
        assertThat(buy.get("deal_ref").asString()).startsWith("SC");
        JsonNode issuer = get("/v1/treasury/limits?limit_type=ISSUER").get("data").get(0);
        assertThat(dec(issuer, "utilised_amount")).isEqualByComparingTo("995000");
        post("/v1/treasury/securities-trades/" + buy.get("id").asString() + "/settle", null, "ops", 200);
        String holdingId = buy.get("securities_holding_id").asString();
        JsonNode holding = get("/v1/treasury/holdings/" + holdingId);
        assertThat(dec(holding, "nominal")).isEqualByComparingTo("1000000");
        assertThat(dec(holding, "book_value")).isEqualByComparingTo("995000.00");

        post("/v1/treasury/securities/" + secId + "/prices", "{\"price\":\"101\",\"price_date\":\"" + today + "\"}", "ops", 200);
        JsonNode reval = post("/v1/treasury/holdings/revalue", "{\"valuation_date\":\"" + today + "\",\"legal_entity_id\":\""
                + ENTITY + "\"}", "ops", 200);
        assertThat(reval.get("holdings_valued").asInt()).isEqualTo(1);
        assertThat(dec(get("/v1/treasury/holdings/" + holdingId), "unrealised_pnl")).isEqualByComparingTo("15000.00");

        JsonNode sell = post("/v1/treasury/securities-trades", trade(secId, "SELL", "400000", "100", "AFS"), "dealer", 201);
        assertThat(post("/v1/treasury/securities-trades", trade(secId, "SELL", "700000", "100", "AFS"), "dealer", 422)
                .get("code").asString()).isEqualTo("insufficient_holding");
        JsonNode sold = post("/v1/treasury/securities-trades/" + sell.get("id").asString() + "/settle", null, "ops", 200);
        assertThat(dec(sold, "realised_pnl")).isEqualByComparingTo("2000.00");
        assertThat(dec(get("/v1/treasury/holdings/" + holdingId), "nominal")).isEqualByComparingTo("600000");

        post("/v1/treasury/holdings/" + holdingId + "/encumber", "{\"is_encumbered\":true,\"reason\":\"repo collateral\"}", "ops", 200);
        assertThat(post("/v1/treasury/holdings/" + holdingId + "/encumber", "{\"is_encumbered\":true}", "ops", 409)
                .get("code").asString()).isEqualTo("already_encumbered");
        assertThat(post("/v1/treasury/securities-trades", trade(secId, "SELL", "100000", "100", "AFS"), "dealer", 422)
                .get("code").asString()).isEqualTo("holding_encumbered");

        JsonNode htm = post("/v1/treasury/securities-trades", trade(secId, "BUY", "100000", "99", "HTM"), "dealer", 201);
        post("/v1/treasury/securities-trades/" + htm.get("id").asString() + "/settle", null, "ops", 200);
        assertThat(post("/v1/treasury/securities-trades", trade(secId, "SELL", "100000", "100", "HTM"), "dealer", 422)
                .get("code").asString()).isEqualTo("htm_sale_restricted");
        JsonNode pending = post("/v1/treasury/securities-trades", trade(secId, "BUY", "100000", "99", "FVTPL"), "dealer", 201);
        assertThat(post("/v1/treasury/securities-trades/" + pending.get("id").asString() + "/cancel", "{\"reason\":\"error\"}",
                "dealer", 200).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(events("treasury.securities_trade.settled")).isEqualTo(3);
    }

    // ------------------------------------------------------------------ 7. tenant isolation and validation

    @Test
    @Order(7)
    void tenantIsolationAndRequestValidation() throws Exception {
        HttpResponse<String> other = send("GET", "/v1/treasury/counterparties/" + bank, null, null, "ops", OTHER);
        assertThat(other.statusCode()).isEqualTo(404);
        HttpResponse<String> list = send("GET", "/v1/treasury/money-market-deals", null, null, "ops", OTHER);
        assertThat(json.readTree(list.body()).get("data")).isEmpty();
        HttpResponse<String> foreign = send("POST", "/v1/treasury/money-market-deals/" + placement + "/verify", null,
                UUID.randomUUID().toString(), "x", OTHER);
        assertThat(foreign.statusCode()).isEqualTo(404);
        HttpResponse<String> noKey = send("POST", "/v1/treasury/counterparties", counterparty("NOKEY", "DEUTDEFF"), null, "maker", TENANT);
        assertThat(noKey.statusCode()).isEqualTo(400);
        HttpResponse<String> noDate = send("POST", "/v1/treasury/eod-runs/accruals", null, UUID.randomUUID().toString(), "s", TENANT);
        assertThat(noDate.statusCode()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ helpers

    private String counterparty(String code, String bic) {
        return """
                {"legal_entity_id":"%s","code":"%s","name":"Deutsche Bank AG","counterparty_type":"BANK","bic":"%s",
                 "lei":"529900T8BM49AURSDO55","country_code":"DE","isda_signed":true,"external_rating":"A-"}"""
                .formatted(ENTITY, code, bic);
    }

    private String limit(String type, String ccy, String amount, Integer tenor) throws Exception {
        JsonNode l = post("/v1/treasury/limits", """
                {"treasury_counterparty_id":"%s","limit_type":"%s","currency":"%s","limit_amount":"%s",
                 "valid_from":"2020-01-01"%s}""".formatted(bank, type, ccy, amount,
                tenor == null ? "" : ",\"max_tenor_days\":" + tenor), "maker", 201);
        assertThat(l.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        String id = l.get("id").asString();
        assertThat(post("/v1/treasury/limits/" + id + "/approve", "{}", "maker", 422).get("code").asString())
                .isEqualTo("four_eyes_violation");
        post("/v1/treasury/limits/" + id + "/approve", "{}", "checker", 200);
        return id;
    }

    @Test
    @Order(8)
    void tradeDatesAreCheckedAgainstTheEntityBusinessDate() throws Exception {
        LocalDate ahead = today.plusDays(5);
        assertThat(post("/v1/treasury/fx-deals", fx("SPOT", ahead, ahead, "1.0850", null, null), "dealer", 422)
                .get("code").asString()).isEqualTo("invalid_dates");
        try {
            businessDate = ahead; // the entity has already rolled forward
            businessDates.invalidate();
            assertThat(post("/v1/treasury/fx-deals", fx("SPOT", ahead, ahead, "1.0850", null, null), "dealer", 201)
                    .get("trade_date").asString()).isEqualTo(ahead.toString());
        } finally {
            businessDate = null;
            businessDates.invalidate();
        }
    }

    private String mm(String type, String principal, LocalDate trade, LocalDate value, LocalDate maturity) {
        return """
                {"legal_entity_id":"%s","treasury_counterparty_id":"%s","deal_type":"%s","currency":"EUR","principal":"%s",
                 "trade_date":"%s","value_date":"%s","maturity_date":"%s","interest_rate":"0.05","day_count":"ACT_360",
                 "dealer_id":"%s"}""".formatted(ENTITY, bank, type, principal, trade, value, maturity, DEALER);
    }

    /** Captures, verifies, confirms (manually) and settles a placement; returns its id. */
    private String settledDeal(String principal, LocalDate value, LocalDate maturity) throws Exception {
        String id = post("/v1/treasury/money-market-deals", mm("PLACEMENT", principal, value, value, maturity), "dealer", 201)
                .get("id").asString();
        String p = "/v1/treasury/money-market-deals/" + id;
        post(p + "/verify", null, "backoffice", 200);
        post(p + "/confirm", "{\"manual_reference\":\"REF-" + principal + "\"}", "backoffice", 200);
        post(p + "/settle", null, "ops", 200);
        return id;
    }

    /** The counterparty's MT320 for our placement: they borrow (17R:B). */
    private String theirMt320(String principal) {
        String d = today.toString().replace("-", "");
        String m = today.plusDays(90).toString().replace("-", "");
        return "{1:F01DEUTDEFFXXXX}{2:I320MERIGB2LXXXXN}{4:\n:20:DB-CONF-1\n:22A:NEWT\n:82A:DEUTDEFFXXX\n:87A:MERIGB2LXXX\n"
                + ":17R:B\n:30T:" + d + "\n:30V:" + d + "\n:30P:" + m + "\n:32B:EUR" + principal + ",00\n"
                + ":34E:NEUR25000,00\n:37G:5,0\n:14D:ACT/360\n-}";
    }

    private String inbound(String text, String dealId) {
        return """
                {"deal_type":"MONEY_MARKET","direction":"INBOUND","channel":"SWIFT","our_reference":"DB-CONF-1",
                 "message_text":%s%s}""".formatted(json.writeValueAsString(text),
                dealId == null ? "" : ",\"deal_id\":\"" + dealId + "\"");
    }

    private String fx(String type, LocalDate trade, LocalDate value, String rate, String spot, String points) {
        return """
                {"legal_entity_id":"%s","treasury_counterparty_id":"%s","deal_type":"%s","trade_date":"%s","dealer_id":"%s",
                 "legs":[{"leg_type":"NEAR","buy_sell":"BUY","base_currency":"EUR","base_amount":"1000000",
                          "quote_currency":"USD","deal_rate":"%s"%s%s,"value_date":"%s"}]}"""
                .formatted(ENTITY, bank, type, trade, DEALER, rate, spot == null ? "" : ",\"spot_rate\":\"" + spot + "\"",
                        points == null ? "" : ",\"forward_points\":\"" + points + "\"", value);
    }

    private String security(String isin) {
        return """
                {"isin":"%s","name":"Bund 6%% 2030","security_type":"GOVERNMENT_BOND","issuer_name":"Deutsche Bank AG",
                 "issuer_counterparty_id":"%s","currency":"EUR","coupon_rate":"0.06","coupon_frequency":2,
                 "issue_date":"2020-03-15","maturity_date":"2030-03-15","day_count":"THIRTY_360","hqla_level":"L1"}"""
                .formatted(isin, bank);
    }

    private String trade(String secId, String side, String nominal, String price, String cls) {
        return """
                {"legal_entity_id":"%s","treasury_security_id":"%s","portfolio":"BANKING_BOOK","accounting_class":"%s",
                 "treasury_counterparty_id":"%s","buy_sell":"%s","nominal":"%s","clean_price":"%s","trade_date":"%s",
                 "settlement_date":"%s","dealer_id":"%s"}""".formatted(ENTITY, secId, cls, bank, side, nominal, price, today,
                today, DEALER);
    }

    private Call lastPosting(String keyPrefix) {
        return calls.stream().filter(c -> c.path().equals("/v1/journal-entries") && c.key() != null
                && c.key().startsWith(keyPrefix)).reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no posting with key " + keyPrefix));
    }

    private static void assertLeg(Call posting, String account, String direction, String amount) {
        boolean found = false;
        for (JsonNode e : posting.body().get("entries")) {
            if (account.equals(e.get("ledger_account_id").asString()) && direction.equals(e.get("direction").asString())
                    && new BigDecimal(e.get("amount").asString()).compareTo(new BigDecimal(amount)) == 0) {
                found = true;
            }
        }
        assertThat(found).as("leg %s %s %s in %s", account, direction, amount, posting.body()).isTrue();
    }

    private int events(String type) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", () -> jdbc.sql(
                "select count(*) from outbox where tenant_id = ?::uuid and event_type = ?")
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
        HttpResponse<String> r = send("GET", path, null, null, "ops", TENANT);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private String getText(String path) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, "ops", TENANT);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
        return r.body();
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String user, String tenant)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        if ("PATCH".equals(method)) {
            HttpResponse<String> current = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("X-Tenant-Id", tenant).header("X-User", user).GET().build(), HttpResponse.BodyHandlers.ofString());
            b.header("If-Match", "\"" + json.readTree(current.body()).get("row_version").asString() + "\"");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
