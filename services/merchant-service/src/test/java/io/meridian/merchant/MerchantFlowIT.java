package io.meridian.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.merchant.domain.EmvQr;
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
 * merchant-service end to end over HTTP against PostgreSQL 18 (RLS enforced) with wallet-service and deposit-service
 * replaced by a stateful {@link FakeWalletService} behind the mocked {@link ServiceClient}. Every money movement
 * (customer to merchant, refund, MDR fee, net to the bank account) is asserted on the fake's balances.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.merchant.fee-income-account-id=0199c000-0000-7000-8000-0000000000f1",
        "meridian.merchant.payment-link-base-url=https://pay.test/l"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MerchantFlowIT {
    static final String TENANT = "0199c000-0000-7000-8000-00000000c001";
    static final String OTHER_TENANT = "0199c000-0000-7000-8000-00000000c002";
    static final UUID FEE_INCOME = UUID.fromString("0199c000-0000-7000-8000-0000000000f1");
    static final UUID DEPOSIT = UUID.fromString("0199c000-0000-7000-8000-0000000000d1");
    static final UUID DEPOSIT_LEDGER = UUID.fromString("0199c000-0000-7000-8000-0000000000d2");

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
    UUID customer;
    String plan;
    String merchant;
    UUID merchantWallet;
    String dynamicPayload;
    String dynamicPaymentId;
    String staticPayload;

    @BeforeAll
    void setUp() {
        wallets = new FakeWalletService(json);
        wallets.depositAccounts.put(DEPOSIT, json.readTree("{\"id\":\"" + DEPOSIT + "\",\"status\":\"ACTIVE\",\"currency\":\"KES\","
                + "\"ledger_account_id\":\"" + DEPOSIT_LEDGER + "\"}"));
        customer = wallets.customer("Jane Doe", "KES", "5000", "4826");
    }

    @BeforeEach
    void stub() {
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> wallets.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> wallets.get(i.getArgument(0), i.getArgument(1)));
    }

    @Test
    @Order(1)
    void onboardMerchantWithFourEyesAndWallet() throws Exception {
        post("/v1/merchant-fee-plans", """
                {"code":"BAD","name":"Bad","currency":"KES","mdr_percentage":"1.5","valid_from":"2020-01-01"}""", "maker", 422);
        plan = post("/v1/merchant-fee-plans", """
                {"code":"STD","name":"Standard","currency":"KES","mdr_percentage":"0.01","fixed_fee":"0","min_fee":"1",
                 "max_fee":"50","refund_fee":"2","settlement_fee":"5","valid_from":"2020-01-01"}""", "maker", 201)
                .get("id").asString();
        post("/v1/merchants", merchantBody("54X1", plan), "maker", 422, "invalid_mcc");
        String key = UUID.randomUUID().toString();
        JsonNode m = send("POST", "/v1/merchants", merchantBody("5411", plan), key, "maker", TENANT, 201, null);
        assertThat(send("POST", "/v1/merchants", merchantBody("5411", plan), key, "maker", TENANT, 201, null)
                .get("id").asString()).isEqualTo(m.get("id").asString());
        merchant = m.get("id").asString();
        assertThat(m.get("status").asString()).isEqualTo("ONBOARDING");
        assertThat(m.get("merchant_code").asString()).matches("\\d{7}");

        post("/v1/merchants/" + merchant + "/activate", "{}", "maker", 422, "four_eyes_violation");
        JsonNode active = post("/v1/merchants/" + merchant + "/activate", "{\"comment\":\"KYB ok\"}", "checker", 200);
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(active.get("onboarded_at").isNull()).isFalse();
        merchantWallet = UUID.fromString(active.get("settlement_wallet_id").asString());
        JsonNode walletCreate = wallets.calls.stream().filter(c -> c.path().equals("/v1/wallets")).findFirst().orElseThrow().body();
        assertThat(walletCreate.get("wallet_type").asString()).isEqualTo("MERCHANT");
        assertThat(walletCreate.get("merchant_id").asString()).isEqualTo(merchant);
        assertThat(wallets.calls).anyMatch(c -> c.path().equals("/v1/wallets/" + merchantWallet + "/activate"));
        post("/v1/merchants/" + merchant + "/activate", "{}", "checker", 409);

        String other = post("/v1/merchants", merchantBody("5812", null), "maker", 201).get("id").asString();
        post("/v1/merchants/" + other + "/activate", "{}", "checker", 422, "fee_plan_required");

        HttpResponse<String> patch = raw("PATCH", "/v1/merchants/" + merchant, "{\"currency\":\"USD\"}", null, "maker",
                TENANT, version("/v1/merchants/" + merchant));
        assertThat(patch.statusCode()).isEqualTo(422);
    }

    @Test
    @Order(2)
    void issueAndDecodeQrCodes() throws Exception {
        String store = post("/v1/merchant-stores", """
                {"merchant_id":"%s","store_code":"WEST-1","name":"Westlands","country":"KE","city":"Nairobi"}""".formatted(merchant),
                "maker", 201).get("id").asString();
        String terminal = post("/v1/merchant-terminals", """
                {"merchant_id":"%s","merchant_store_id":"%s","terminal_id":"T0001","terminal_type":"POS"}"""
                .formatted(merchant, store), "maker", 201).get("id").asString();
        JsonNode dyn = post("/v1/merchant-qr-codes", """
                {"merchant_id":"%s","merchant_store_id":"%s","merchant_terminal_id":"%s","qr_type":"DYNAMIC",
                 "amount":"1000","currency":"KES","reference":"INV-1001"}""".formatted(merchant, store, terminal), "maker", 201);
        dynamicPayload = dyn.get("payload").asString();
        assertThat(dyn.get("expires_at").isNull()).isFalse();
        assertThat(EmvQr.crcValid(dynamicPayload)).isTrue();
        assertThat(dynamicPayload).startsWith("000201010212").contains("54041000").contains("0108INV-1001").contains("0705T0001");
        post("/v1/merchant-qr-codes", """
                {"merchant_id":"%s","qr_type":"STATIC","amount":"5","currency":"KES"}""".formatted(merchant), "maker", 422);
        post("/v1/merchant-qr-codes", """
                {"merchant_id":"%s","qr_type":"DYNAMIC","currency":"KES"}""".formatted(merchant), "maker", 422, "amount_required");
        staticPayload = post("/v1/merchant-qr-codes", """
                {"merchant_id":"%s","qr_type":"STATIC","currency":"KES"}""".formatted(merchant), "maker", 201)
                .get("payload").asString();

        JsonNode decoded = post("/v1/merchant-qr-codes/decode", "{\"payload\":\"" + dynamicPayload + "\"}", "app", 200);
        assertThat(decoded.get("crc_valid").asBoolean()).isTrue();
        assertThat(decoded.get("payable").asBoolean()).isTrue();
        assertThat(decoded.get("merchant_id").asString()).isEqualTo(merchant);
        assertThat(decoded.get("amount").asString()).isEqualTo("1000");
        assertThat(decoded.get("currency").asString()).isEqualTo("KES");
        assertThat(decoded.get("point_of_initiation").asString()).isEqualTo("12");

        String tampered = dynamicPayload.replace("54041000", "54040100");
        JsonNode bad = post("/v1/merchant-qr-codes/decode", "{\"payload\":\"" + tampered + "\"}", "app", 200);
        assertThat(bad.get("crc_valid").asBoolean()).isFalse();
        assertThat(bad.get("payable").asBoolean()).isFalse();
        JsonNode garbage = post("/v1/merchant-qr-codes/decode", "{\"payload\":\"hello\"}", "app", 200);
        assertThat(garbage.get("payable").asBoolean()).isFalse();
        // re-signed tampered payload is detected because it differs from the issued one
        String body = tampered.substring(0, tampered.length() - 4);
        String resigned = body + EmvQr.crcHex(body);
        JsonNode mismatch = post("/v1/merchant-qr-codes/decode", "{\"payload\":\"" + resigned + "\"}", "app", 200);
        assertThat(mismatch.get("crc_valid").asBoolean()).isTrue();
        assertThat(mismatch.get("reason").asString()).isEqualTo("payload_mismatch");
    }

    @Test
    @Order(3)
    void payQrCodesMovesMoneyToTheMerchantWallet() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"payload\":\"" + dynamicPayload + "\",\"payer_wallet_id\":\"" + customer + "\",\"pin\":\"4826\"}";
        JsonNode t = send("POST", "/v1/merchant-qr-codes/pay", body, key, "jane", TENANT, 200, null);
        dynamicPaymentId = t.get("id").asString();
        assertThat(t.get("txn_type").asString()).isEqualTo("MERCHANT_PAYMENT");
        assertThat(t.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(send("POST", "/v1/merchant-qr-codes/pay", body, key, "jane", TENANT, 200, null).get("id").asString())
                .isEqualTo(dynamicPaymentId);
        assertThat(wallets.balance(customer)).isEqualByComparingTo("4000");
        assertThat(wallets.balance(merchantWallet)).isEqualByComparingTo("1000");
        JsonNode sent = wallets.transactions().getFirst().body();
        assertThat(sent.get("payee_wallet_id").asString()).isEqualTo(merchantWallet.toString());
        assertThat(sent.get("merchant_id").asString()).isEqualTo(merchant);
        assertThat(sent.get("channel").asString()).isEqualTo("QR");
        assertThat(sent.get("pin").asString()).isEqualTo("4826");

        post("/v1/merchant-qr-codes/pay", body, "jane", 409, "qr_already_used");
        JsonNode qrs = get("/v1/merchant-qr-codes?merchant_id=" + merchant + "&qr_type=DYNAMIC");
        assertThat(qrs.get("data").get(0).get("scan_count").asInt()).isEqualTo(1);
        assertThat(qrs.get("data").get(0).get("is_active").asBoolean()).isFalse();

        String stat = "{\"payload\":\"" + staticPayload + "\",\"payer_wallet_id\":\"" + customer + "\",\"pin\":\"4826\"";
        post("/v1/merchant-qr-codes/pay", stat + "}", "jane", 422, "amount_required");
        post("/v1/merchant-qr-codes/pay", stat.replace("4826", "1111") + ",\"amount\":\"500\"}", "jane", 422, "invalid_pin");
        post("/v1/merchant-qr-codes/pay", "{\"payload\":\"" + dynamicPayload.replace("54041000", "54040100")
                + "\",\"payer_wallet_id\":\"" + customer + "\"}", "jane", 422, "invalid_qr_crc");
        post("/v1/merchant-qr-codes/pay", stat + ",\"amount\":\"500\"}", "jane", 200);
        assertThat(wallets.balance(merchantWallet)).isEqualByComparingTo("1500");
    }

    @Test
    @Order(4)
    void paymentLinksAndDailyLimit() throws Exception {
        JsonNode link = post("/v1/merchant-payment-links", """
                {"merchant_id":"%s","amount":"200","currency":"KES","description":"Order 77","max_uses":1}""".formatted(merchant),
                "maker", 201);
        String id = link.get("id").asString();
        assertThat(link.get("url").asString()).isEqualTo("https://pay.test/l/" + link.get("link_code").asString());

        raw("PATCH", "/v1/merchants/" + merchant, "{\"daily_limit\":\"1600\"}", null, "maker", TENANT,
                version("/v1/merchants/" + merchant));
        String pay = "{\"payer_wallet_id\":\"" + customer + "\",\"pin\":\"4826\"}";
        post("/v1/merchant-payment-links/" + id + "/pay", pay, "jane", 422, "merchant_daily_limit_exceeded");
        raw("PATCH", "/v1/merchants/" + merchant, "{\"daily_limit\":\"100000\"}", null, "maker", TENANT,
                version("/v1/merchants/" + merchant));
        post("/v1/merchant-payment-links/" + id + "/pay", "{\"payer_wallet_id\":\"" + customer
                + "\",\"pin\":\"4826\",\"amount\":\"999\"}", "jane", 422, "amount_mismatch");
        JsonNode paid = post("/v1/merchant-payment-links/" + id + "/pay", pay, "jane", 200);
        assertThat(paid.get("amount").asString()).startsWith("200");
        JsonNode after = get("/v1/merchant-payment-links/" + id);
        assertThat(after.get("use_count").asInt()).isEqualTo(1);
        assertThat(after.get("is_active").asBoolean()).isFalse();
        post("/v1/merchant-payment-links/" + id + "/pay", pay, "jane", 409, "link_inactive");
        assertThat(wallets.balance(merchantWallet)).isEqualByComparingTo("1700");
        assertThat(wallets.balance(customer)).isEqualByComparingTo("3300");

        JsonNode txns = get("/v1/merchants/" + merchant + "/transactions");
        assertThat(txns.get("data")).hasSize(3);
    }

    @Test
    @Order(5)
    void refundsNeedACheckerAndCannotExceedThePayment() throws Exception {
        String refund = post("/v1/merchant-refunds", refundBody(dynamicPaymentId, "300"), "maker", 201).get("id").asString();
        post("/v1/merchant-refunds", refundBody(dynamicPaymentId, "800"), "maker", 422, "refund_exceeds_amount");
        post("/v1/merchant-refunds/" + refund + "/approve", "{}", "maker", 422, "four_eyes_violation");
        JsonNode done = post("/v1/merchant-refunds/" + refund + "/approve", "{\"comment\":\"ok\"}", "checker", 200);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("wallet_transaction_id").isNull()).isFalse();
        assertThat(wallets.balance(customer)).isEqualByComparingTo("3600");
        assertThat(wallets.balance(merchantWallet)).isEqualByComparingTo("1398");
        assertThat(wallets.account(FEE_INCOME)).isEqualByComparingTo("2");
        JsonNode refundTxn = wallets.transactions().stream().filter(c -> "REFUND".equals(c.body().get("txn_type").asString()))
                .findFirst().orElseThrow().body();
        assertThat(refundTxn.get("original_transaction_id").asString()).isEqualTo(dynamicPaymentId);
        assertThat(refundTxn.get("payee_wallet_id").asString()).isEqualTo(customer.toString());
        post("/v1/merchant-refunds/" + refund + "/approve", "{}", "checker", 409);

        String rejected = post("/v1/merchant-refunds", refundBody(dynamicPaymentId, "100"), "maker", 201).get("id").asString();
        assertThat(post("/v1/merchant-refunds/" + rejected + "/reject", "{\"reason\":\"Goods not returned\"}", "checker", 200)
                .get("status").asString()).isEqualTo("REJECTED");

        // the wallet engine refuses: the refund is recorded FAILED
        String failing = post("/v1/merchant-refunds", refundBody(dynamicPaymentId, "50"), "maker", 201).get("id").asString();
        wallets.setStatus(merchantWallet, "SUSPENDED");
        post("/v1/merchant-refunds/" + failing + "/approve", "{}", "checker", 422, "wallet_not_active");
        wallets.setStatus(merchantWallet, "ACTIVE");
        assertThat(get("/v1/merchant-refunds/" + failing).get("status").asString()).isEqualTo("FAILED");
        post("/v1/merchant-refunds", refundBody(UUID.randomUUID().toString(), "10"), "maker", 422, "original_transaction_not_found");
    }

    @Test
    @Order(6)
    void settlementNetOfMdrPaidToTheBank() throws Exception {
        raw("PATCH", "/v1/merchants/" + merchant, "{\"settlement_account_id\":\"" + DEPOSIT + "\"}", null, "maker", TENANT,
                version("/v1/merchants/" + merchant));
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        String body = "{\"merchant_id\":\"" + merchant + "\",\"period_start\":\"" + today + "\",\"period_end\":\"" + today
                + "\",\"currency\":\"KES\"}";
        JsonNode s = post("/v1/merchant-settlements", body, "maker", 201);
        String id = s.get("id").asString();
        assertThat(s.get("status").asString()).isEqualTo("CALCULATED");
        assertThat(s.get("payment_count").asInt()).isEqualTo(3);
        assertThat(new BigDecimal(s.get("gross_amount").asString())).isEqualByComparingTo("1700");
        assertThat(new BigDecimal(s.get("mdr_amount").asString())).isEqualByComparingTo("22");
        assertThat(new BigDecimal(s.get("refund_amount").asString())).isEqualByComparingTo("300");
        assertThat(new BigDecimal(s.get("net_amount").asString())).isEqualByComparingTo("1376");
        post("/v1/merchant-settlements", body, "maker", 409, "settlement_overlap");
        post("/v1/merchant-settlements/" + id + "/pay", null, "checker", 409);
        post("/v1/merchant-settlements/" + id + "/approve", "{}", "maker", 422, "four_eyes_violation");
        assertThat(post("/v1/merchant-settlements/" + id + "/approve", "{}", "checker", 200).get("status").asString())
                .isEqualTo("APPROVED");
        JsonNode paid = post("/v1/merchant-settlements/" + id + "/pay", null, "checker", 200);
        assertThat(paid.get("status").asString()).isEqualTo("PAID");
        assertThat(paid.get("fee_wallet_transaction_id").isNull()).isFalse();
        assertThat(paid.get("wallet_transaction_id").isNull()).isFalse();
        assertThat(wallets.balance(merchantWallet)).isEqualByComparingTo("0");
        assertThat(wallets.account(FEE_INCOME)).isEqualByComparingTo("24");
        assertThat(wallets.account(DEPOSIT_LEDGER)).isEqualByComparingTo("1376");
        assertThat(get("/v1/merchants/" + merchant + "/settlements").get("data")).hasSize(1);

        HttpResponse<String> report = raw("GET", "/v1/merchant-settlements/" + id + "/report", null, null, "ops", TENANT, null);
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(report.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(report.body()).contains("gross_amount").contains("PAYMENT,").contains("REFUND,");
        assertThat(report.body().lines().filter(l -> l.startsWith("PAYMENT,")).count()).isEqualTo(3);
    }

    @Test
    @Order(7)
    void suspendTerminateIsolationAndEvents() throws Exception {
        post("/v1/merchants/" + merchant + "/suspend", "{\"reason\":\"Chargebacks\"}", "ops", 200);
        post("/v1/merchant-qr-codes/pay", "{\"payload\":\"" + staticPayload + "\",\"payer_wallet_id\":\"" + customer
                + "\",\"amount\":\"10\",\"pin\":\"4826\"}", "jane", 422, "merchant_not_active");
        JsonNode term = post("/v1/merchants/" + merchant + "/terminate", "{\"reason\":\"Contract ended\"}", "ops", 200);
        assertThat(term.get("status").asString()).isEqualTo("TERMINATED");
        assertThat(get("/v1/merchant-qr-codes?merchant_id=" + merchant + "&is_active=true").get("data")).isEmpty();
        post("/v1/merchants/" + merchant + "/activate", "{}", "checker", 409);

        assertThat(raw("GET", "/v1/merchants/" + merchant, null, null, "x", OTHER_TENANT, null).statusCode()).isEqualTo(404);
        assertThat(json.readTree(raw("GET", "/v1/merchant-refunds", null, null, "x", OTHER_TENANT, null).body()).get("data")).isEmpty();

        List<String> types = TenantContext.callAs(UUID.fromString(TENANT), "test",
                () -> jdbc.sql("select distinct event_type from outbox").query(String.class).list());
        assertThat(types).contains("wallets.merchant.onboarded", "wallets.merchant.status_changed", "wallets.merchant_qr.paid",
                "wallets.merchant_payment_link.paid", "wallets.merchant_settlement.approved",
                "wallets.merchant_settlement.paid", "wallets.merchant_refund.completed", "wallets.merchant_refund.rejected");
    }

    // ------------------------------------------------------------------ helpers

    private String merchantBody(String mcc, String feePlan) {
        return "{\"legal_name\":\"Mama Mboga Ltd\",\"trading_name\":\"Mama Mboga\",\"merchant_type\":\"COMPANY\",\"mcc\":\""
                + mcc + "\",\"country\":\"KE\",\"city\":\"Nairobi\",\"currency\":\"KES\""
                + (feePlan == null ? "" : ",\"merchant_fee_plan_id\":\"" + feePlan + "\"") + "}";
    }

    private String refundBody(String original, String amount) {
        return "{\"merchant_id\":\"" + merchant + "\",\"original_transaction_id\":\"" + original + "\",\"amount\":\"" + amount
                + "\",\"currency\":\"KES\",\"reason\":\"Customer returned goods\"}";
    }

    private String version(String path) throws Exception {
        return "\"" + get(path).get("row_version").asInt() + "\"";
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        return send("POST", path, body, UUID.randomUUID().toString(), user, TENANT, expected, null);
    }

    private void post(String path, String body, String user, int expected, String code) throws Exception {
        assertThat(post(path, body, user, expected).path("code").asString()).as(path).isEqualTo(code);
    }

    private JsonNode get(String path) throws Exception {
        return send("GET", path, null, null, "ops", TENANT, 200, null);
    }

    private JsonNode send(String method, String path, String body, String key, String user, String tenant, int expected,
                          String ifMatch) throws Exception {
        HttpResponse<String> r = raw(method, path, body, key, user, tenant, ifMatch);
        assertThat(r.statusCode()).as(method + " " + path + " -> " + r.body()).isEqualTo(expected);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private HttpResponse<String> raw(String method, String path, String body, String key, String user, String tenant,
                                     String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
