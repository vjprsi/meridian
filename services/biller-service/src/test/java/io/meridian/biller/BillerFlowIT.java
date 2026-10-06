package io.meridian.biller;

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
 * biller-service end to end over HTTP against PostgreSQL 18 (RLS enforced) with the built-in deterministic biller
 * simulator and wallet-service replaced by a stateful {@link FakeWalletService}: every bill payment, top-up, reversal
 * and settlement is asserted on wallet balances, holds and the wallet calls made.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.biller.settlement-clearing-account-id=0199c000-0000-7000-8000-0000000000c1"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BillerFlowIT {
    static final String TENANT = "0199c000-0000-7000-8000-00000000b001";
    static final String OTHER_TENANT = "0199c000-0000-7000-8000-00000000b002";
    static final UUID CLEARING = UUID.fromString("0199c000-0000-7000-8000-0000000000c1");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakeWalletService wallets;
    UUID payer;
    UUID kplcWallet;
    UUID waterWallet;
    UUID safWallet;
    String kplc;
    String water;
    String saf;
    String airtimeProduct;
    String kplcRef;
    String kplcPayment;

    @BeforeAll
    void setUp() {
        wallets = new FakeWalletService(json);
        payer = wallets.wallet("KES", "20000");
        kplcWallet = wallets.wallet("KES", "0");
        waterWallet = wallets.wallet("KES", "0");
        safWallet = wallets.wallet("KES", "0");
        kplcRef = luhn("1234567890");
    }

    @BeforeEach
    void stub() {
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> wallets.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> wallets.get(i.getArgument(0), i.getArgument(1)));
    }

    // ------------------------------------------------------------------ 1. catalogue

    @Test
    @Order(1)
    void catalogueValidationProductsAndStatus() throws Exception {
        post("/v1/billers", biller("BAD", "ELECTRICITY", "[0-9", "NONE", null), "ops", 422, "invalid_regex");
        post("/v1/billers", """
                {"biller_code":"BAD2","name":"Bad","category":"WATER","country":"KE","currency":"KES","integration_type":"API",
                 "min_amount":"500","max_amount":"100"}""", "ops", 422, "invalid_amount_range");
        kplc = post("/v1/billers", biller("KPLC", "ELECTRICITY", "\\\\d{11}", "LUHN", kplcWallet), "ops", 201).get("id").asString();
        water = post("/v1/billers", biller("NWSC", "WATER", "\\\\d{6,12}", "NONE", waterWallet), "ops", 201).get("id").asString();
        saf = post("/v1/billers", biller("SAF", "AIRTIME", null, "NONE", safWallet), "ops", 201).get("id").asString();

        post("/v1/biller-products", """
                {"biller_id":"%s","product_code":"AT100","name":"Airtime 100","product_type":"AIRTIME","denomination":"100","currency":"USD"}"""
                .formatted(saf), "ops", 422, "currency_mismatch");
        airtimeProduct = post("/v1/biller-products", """
                {"biller_id":"%s","product_code":"AT100","name":"Airtime 100","product_type":"AIRTIME","denomination":"100","currency":"KES"}"""
                .formatted(saf), "ops", 201).get("id").asString();
        post("/v1/biller-products", """
                {"biller_id":"%s","product_code":"AT100","name":"Again","product_type":"AIRTIME","denomination":"100","currency":"KES"}"""
                .formatted(saf), "ops", 409, "product_exists");
        assertThat(get("/v1/billers/" + saf + "/products").get("data")).hasSize(1);

        post("/v1/billers/" + water + "/activate", null, "ops", 409, "invalid_transition");
        JsonNode suspended = post("/v1/billers/" + water + "/suspend", "{\"reason\":\"Maintenance\"}", "ops", 200);
        assertThat(suspended.get("status").asString()).isEqualTo("SUSPENDED");
        post("/v1/bill-payments", pay(water, "123456", "100", null, null), "alice", 422, "biller_not_active");
        assertThat(post("/v1/billers/" + water + "/activate", null, "ops", 200).get("status").asString()).isEqualTo("ACTIVE");

        HttpResponse<String> patch = raw("PATCH", "/v1/billers/" + kplc, "{\"currency\":\"USD\"}", null, "ops", TENANT);
        assertThat(patch.statusCode()).isEqualTo(422);
        HttpResponse<String> patchOk = raw("PATCH", "/v1/billers/" + kplc, "{\"display_order\":1}", null, "ops", TENANT);
        assertThat(patchOk.statusCode()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ 2. reference validation + inquiry

    @Test
    @Order(2)
    void referenceValidationAndInquiry() throws Exception {
        post("/v1/billers/" + kplc + "/validate-reference", "{\"account_reference\":\"12AB\"}", "alice", 422, "invalid_reference");
        String wrongDigit = kplcRef.substring(0, 10) + ((kplcRef.charAt(10) - '0' + 1) % 10);
        post("/v1/billers/" + kplc + "/validate-reference", "{\"account_reference\":\"" + wrongDigit + "\"}", "alice", 422,
                "invalid_reference");
        JsonNode inq = post("/v1/billers/" + kplc + "/validate-reference", "{\"account_reference\":\"" + kplcRef + "\"}", "alice", 200);
        assertThat(inq.get("response_code").asString()).isEqualTo("00");
        assertThat(inq.get("customer_name").asString()).isNotBlank();
        assertThat(new BigDecimal(inq.get("amount_due").asString())).isBetween(new BigDecimal("100"), new BigDecimal("9099"));
        assertThat(inq.get("expires_at").isNull()).isFalse();
        JsonNode unknown = post("/v1/bill-inquiries", "{\"biller_id\":\"" + water + "\",\"account_reference\":\"1230000\"}",
                "alice", 201);
        assertThat(unknown.get("response_code").asString()).isEqualTo("14");
    }

    // ------------------------------------------------------------------ 3. bill payment happy path and wallet rejections

    @Test
    @Order(3)
    void billPaymentReservesThenConfirmsFunds() throws Exception {
        JsonNode inq = post("/v1/bill-inquiries", "{\"biller_id\":\"" + kplc + "\",\"account_reference\":\"" + kplcRef + "\"}",
                "alice", 201);
        BigDecimal due = new BigDecimal(inq.get("amount_due").asString());
        String inquiryId = inq.get("id").asString();

        post("/v1/bill-payments", pay(kplc, kplcRef, due.subtract(BigDecimal.TEN).toPlainString(), inquiryId, FakeWalletService.PIN),
                "alice", 422, "partial_payment_not_allowed");

        String key = UUID.randomUUID().toString();
        String body = pay(kplc, kplcRef, due.toPlainString(), inquiryId, FakeWalletService.PIN);
        JsonNode p = send("POST", "/v1/bill-payments", body, key, "alice", TENANT, 201);
        kplcPayment = p.get("id").asString();
        assertThat(p.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(p.get("token").asString()).matches("\\d{20}");
        assertThat(p.get("provider_reference").asString()).startsWith("KPLC-");
        assertThat(p.get("receipt_number").isNull()).isFalse();
        assertThat(new BigDecimal(p.get("fee_amount").asString())).isEqualByComparingTo("2.50");
        assertThat(p.get("customer_name").asString()).isEqualTo(inq.get("customer_name").asString());
        JsonNode replay = send("POST", "/v1/bill-payments", body, key, "alice", TENANT, 201);
        assertThat(replay.get("id").asString()).isEqualTo(kplcPayment);
        assertThat(wallets.balance(payer)).isEqualByComparingTo(new BigDecimal("20000").subtract(due).subtract(FakeWalletService.BILL_FEE));
        assertThat(wallets.balance(kplcWallet)).isEqualByComparingTo(due);
        assertThat(wallets.held(payer)).isEqualByComparingTo("0");
        FakeWalletService.Call create = wallets.posts("/v1/wallet-transactions").getFirst();
        assertThat(create.key()).isEqualTo("bpy-" + kplcPayment + "-debit");
        assertThat(create.body().get("txn_type").asString()).isEqualTo("BILL_PAYMENT");
        assertThat(create.body().get("two_phase").asBoolean()).isTrue();
        assertThat(create.body().get("bill_payment_id").asString()).isEqualTo(kplcPayment);
        assertThat(create.body().get("payee_wallet_id").asString()).isEqualTo(kplcWallet.toString());
        assertThat(wallets.posts("/confirm")).hasSize(1);

        // wrong PIN: the FAILED row is kept and a replay answers the same error
        String badKey = UUID.randomUUID().toString();
        String bad = pay(water, "555555", "100", null, "0000");
        HttpResponse<String> r1 = raw("POST", "/v1/bill-payments", bad, badKey, "alice", TENANT);
        HttpResponse<String> r2 = raw("POST", "/v1/bill-payments", bad, badKey, "alice", TENANT);
        assertThat(r1.statusCode()).isEqualTo(422);
        assertThat(json.readTree(r1.body()).get("code").asString()).isEqualTo("invalid_pin");
        assertThat(json.readTree(r2.body()).get("code").asString()).isEqualTo("invalid_pin");
        JsonNode failed = get("/v1/bill-payments?status=FAILED&biller_id=" + water);
        assertThat(failed.get("data")).hasSize(1);
        assertThat(failed.get("data").get(0).get("failure_reason").asString()).startsWith("[invalid_pin]");
        post("/v1/bill-payments", pay(water, "555555", "50000", null, FakeWalletService.PIN), "alice", 422, "insufficient_funds");
        post("/v1/bill-payments", pay(water, "55", "100", null, null), "alice", 422, "invalid_reference");

        HttpResponse<String> receipt = raw("GET", "/v1/bill-payments/" + kplcPayment + "/receipt", null, null, "alice", TENANT);
        assertThat(receipt.statusCode()).isEqualTo(200);
        assertThat(receipt.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
        assertThat(receipt.body()).contains("MERIDIAN BILL PAYMENT RECEIPT").contains(p.get("token").asString());
    }

    // ------------------------------------------------------------------ 4. provider rejection, timeout, retry

    @Test
    @Order(4)
    void providerRejectionTimeoutRetryAndCancellation() throws Exception {
        BigDecimal before = wallets.balance(payer);
        JsonNode rejected = post("/v1/bill-payments", pay(water, "1238888", "200", null, null), "alice", 201);
        assertThat(rejected.get("status").asString()).isEqualTo("FAILED");
        assertThat(rejected.get("failure_reason").asString()).contains("closed");
        assertThat(wallets.held(payer)).isEqualByComparingTo("0");
        assertThat(wallets.balance(payer)).isEqualByComparingTo(before);

        JsonNode pending = post("/v1/bill-payments", pay(water, "1239999", "300", null, null), "alice", 201);
        String id = pending.get("id").asString();
        assertThat(pending.get("status").asString()).isEqualTo("PENDING_PROVIDER");
        assertThat(wallets.held(payer)).isEqualByComparingTo("302.50");
        JsonNode retried = post("/v1/bill-payments/" + id + "/retry", null, "ops", 200);
        assertThat(retried.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(retried.get("retry_count").asInt()).isEqualTo(1);
        assertThat(wallets.held(payer)).isEqualByComparingTo("0");
        assertThat(wallets.balance(waterWallet)).isEqualByComparingTo("300");
        post("/v1/bill-payments/" + id + "/retry", null, "ops", 409, "invalid_transition");

        JsonNode stuck = post("/v1/bill-payments", pay(water, "4569999", "100", null, null), "alice", 201);
        JsonNode closed = post("/v1/bill-payments/" + stuck.get("id").asString() + "/reverse", "{\"reason\":\"Provider outage\"}",
                "ops", 200);
        assertThat(closed.get("status").asString()).isEqualTo("FAILED");
        assertThat(wallets.held(payer)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ 5. reversal of a completed payment

    @Test
    @Order(5)
    void reversalOfCompletedPayment() throws Exception {
        BigDecimal before = wallets.balance(payer);
        JsonNode p = get("/v1/bill-payments/" + kplcPayment);
        BigDecimal amount = new BigDecimal(p.get("amount").asString());
        JsonNode rev = post("/v1/bill-payments/" + kplcPayment + "/reverse", "{\"reason\":\"Paid wrong meter\"}", "ops2", 200);
        assertThat(rev.get("status").asString()).isEqualTo("REVERSED");
        assertThat(rev.get("reversal_reason").asString()).isEqualTo("Paid wrong meter");
        assertThat(rev.get("reversed_at").isNull()).isFalse();
        assertThat(wallets.balance(payer).subtract(before)).isEqualByComparingTo(amount.add(FakeWalletService.BILL_FEE));
        assertThat(wallets.balance(kplcWallet)).isEqualByComparingTo("0");
        assertThat(wallets.posts("/reverse").getFirst().key()).isEqualTo("bpy-" + kplcPayment + "-reverse");
        JsonNode failedRow = get("/v1/bill-payments?status=FAILED&biller_id=" + water).get("data").get(0);
        post("/v1/bill-payments/" + failedRow.get("id").asString() + "/reverse", "{\"reason\":\"x\"}", "ops2", 409, "invalid_transition");
    }

    // ------------------------------------------------------------------ 6. airtime

    @Test
    @Order(6)
    void airtimeTopups() throws Exception {
        post("/v1/airtime-topups", topup(saf, "+254711000001", "150", airtimeProduct), "alice", 422, "amount_mismatch");
        post("/v1/airtime-topups", topup(kplc, "+254711000001", "100", null), "alice", 422, "not_airtime_biller");
        JsonNode ok = post("/v1/airtime-topups", topup(saf, "+254711000001", "100", airtimeProduct), "alice", 201);
        assertThat(ok.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(ok.get("provider_reference").asString()).startsWith("SAF-");
        assertThat(wallets.balance(safWallet)).isEqualByComparingTo("100");
        assertThat(wallets.posts("/v1/wallet-transactions").getLast().body().get("txn_type").asString()).isEqualTo("AIRTIME");
        JsonNode failed = post("/v1/airtime-topups", topup(saf, "+254711000000", "50", null), "alice", 201);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        JsonNode pending = post("/v1/airtime-topups", topup(saf, "+254711009999", "40", null), "alice", 201);
        assertThat(pending.get("status").asString()).isEqualTo("PENDING_PROVIDER");
        assertThat(post("/v1/airtime-topups/" + pending.get("id").asString() + "/retry", null, "ops", 200)
                .get("status").asString()).isEqualTo("COMPLETED");
        assertThat(wallets.balance(safWallet)).isEqualByComparingTo("140");
    }

    // ------------------------------------------------------------------ 7. settlements

    @Test
    @Order(7)
    void settlementMakerCheckerAndPayout() throws Exception {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        String body = "{\"biller_id\":\"" + water + "\",\"period_start\":\"" + today + "\",\"period_end\":\"" + today
                + "\",\"currency\":\"KES\"}";
        JsonNode s = post("/v1/biller-settlements", body, "maker", 201);
        String id = s.get("id").asString();
        assertThat(s.get("status").asString()).isEqualTo("CALCULATED");
        assertThat(s.get("payment_count").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(s.get("gross_amount").asString())).isEqualByComparingTo("300");
        assertThat(new BigDecimal(s.get("net_amount").asString())).isEqualByComparingTo("300");
        assertThat(get("/v1/biller-settlements/" + id + "/payments").get("data")).hasSize(1);
        post("/v1/biller-settlements", body, "maker", 422, "nothing_to_settle");

        post("/v1/biller-settlements/" + id + "/mark-paid", "{\"payment_reference\":\"EFT1\"}", "checker", 409, "invalid_transition");
        post("/v1/biller-settlements/" + id + "/approve", "{}", "maker", 422, "four_eyes_violation");
        assertThat(post("/v1/biller-settlements/" + id + "/approve", "{\"comment\":\"ok\"}", "checker", 200)
                .get("status").asString()).isEqualTo("APPROVED");
        JsonNode paid = post("/v1/biller-settlements/" + id + "/mark-paid", "{\"payment_reference\":\"EFT-0001\"}", "checker", 200);
        assertThat(paid.get("status").asString()).isEqualTo("PAID");
        assertThat(paid.get("payment_reference").asString()).isEqualTo("EFT-0001");
        assertThat(paid.get("wallet_transaction_id").isNull()).isFalse();
        assertThat(wallets.balance(waterWallet)).isEqualByComparingTo("0");
        assertThat(wallets.counterparty(CLEARING)).isEqualByComparingTo("300");
        FakeWalletService.Call payout = wallets.posts("/v1/wallet-transactions").getLast();
        assertThat(payout.key()).isEqualTo("bst-" + id + "-pay");
        assertThat(payout.body().get("txn_type").asString()).isEqualTo("WALLET_TO_BANK");

        JsonNode airtime = post("/v1/biller-settlements", "{\"biller_id\":\"" + saf + "\",\"period_start\":\"" + today
                + "\",\"period_end\":\"" + today + "\",\"currency\":\"KES\"}", "maker", 201);
        assertThat(airtime.get("topup_count").asInt()).isEqualTo(2);
        assertThat(new BigDecimal(airtime.get("gross_amount").asString())).isEqualByComparingTo("140");
    }

    // ------------------------------------------------------------------ 8. isolation and events

    @Test
    @Order(8)
    void tenantIsolationAndEvents() throws Exception {
        assertThat(raw("GET", "/v1/bill-payments/" + kplcPayment, null, null, "x", OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(json.readTree(raw("GET", "/v1/billers", null, null, "x", OTHER_TENANT).body()).get("data")).isEmpty();
        List<String> types = TenantContext.callAs(UUID.fromString(TENANT), "test",
                () -> jdbc.sql("select distinct event_type from outbox").query(String.class).list());
        assertThat(types).containsExactlyInAnyOrder("wallets.biller.status_changed", "wallets.bill_payment.completed",
                "wallets.bill_payment.failed", "wallets.bill_payment.reversed", "wallets.airtime_topup.completed",
                "wallets.airtime_topup.failed", "wallets.biller_settlement.approved", "wallets.biller_settlement.paid");
    }

    // ------------------------------------------------------------------ helpers

    static String luhn(String payload) {
        int sum = 0;
        boolean dbl = true;
        for (int i = payload.length() - 1; i >= 0; i--) {
            int d = payload.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return payload + (10 - sum % 10) % 10;
    }

    private String biller(String code, String category, String regex, String checksum, UUID wallet) {
        return "{\"biller_code\":\"" + code + "\",\"name\":\"" + code + " Ltd\",\"category\":\"" + category
                + "\",\"country\":\"KE\",\"currency\":\"KES\",\"integration_type\":\"API\",\"min_amount\":\"10\",\"max_amount\":\"100000\""
                + (regex == null ? "" : ",\"account_reference_regex\":\"" + regex + "\"")
                + ",\"account_reference_checksum\":\"" + checksum + "\""
                + (wallet == null ? "" : ",\"settlement_wallet_id\":\"" + wallet + "\"") + "}";
    }

    private String pay(String billerId, String ref, String amount, String inquiry, String pin) {
        return "{\"biller_id\":\"" + billerId + "\",\"payer_wallet_id\":\"" + payer + "\",\"account_reference\":\"" + ref
                + "\",\"amount\":\"" + amount + "\",\"currency\":\"KES\""
                + (inquiry == null ? "" : ",\"bill_inquiry_id\":\"" + inquiry + "\"")
                + (pin == null ? "" : ",\"pin\":\"" + pin + "\"") + "}";
    }

    private String topup(String billerId, String msisdn, String amount, String product) {
        return "{\"biller_id\":\"" + billerId + "\",\"payer_wallet_id\":\"" + payer + "\",\"target_msisdn\":\"" + msisdn
                + "\",\"amount\":\"" + amount + "\",\"currency\":\"KES\",\"channel\":\"USSD\""
                + (product == null ? "" : ",\"biller_product_id\":\"" + product + "\"") + "}";
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        return send("POST", path, body, UUID.randomUUID().toString(), user, TENANT, expected);
    }

    private void post(String path, String body, String user, int expected, String code) throws Exception {
        JsonNode r = post(path, body, user, expected);
        assertThat(r.path("code").asString()).as(path).isEqualTo(code);
    }

    private JsonNode get(String path) throws Exception {
        return send("GET", path, null, null, "ops", TENANT, 200);
    }

    private JsonNode send(String method, String path, String body, String key, String user, String tenant, int expected)
            throws Exception {
        HttpResponse<String> r = raw(method, path, body, key, user, tenant);
        assertThat(r.statusCode()).as(method + " " + path + " -> " + r.body()).isEqualTo(expected);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private HttpResponse<String> raw(String method, String path, String body, String key, String user, String tenant)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        if ("PATCH".equals(method)) {
            b.header("If-Match", "\"1\"");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
