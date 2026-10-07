package io.meridian.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.wallet.domain.WalletNumbers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * wallet-service end to end over HTTP against PostgreSQL 18 (RLS enforced). ledger-service is a stateful
 * {@link FakeLedger} behind the mocked {@link ServiceClient}; limits, fraud, notification, deposit and payment
 * services are stubbed. Every money movement is asserted on the fake ledger (journal lines, holds, balances).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.wallet.ledger-id=0199b000-0000-7000-8000-0000000000aa",
        "meridian.wallet.gl.fee-income=0199b000-0000-7000-8000-0000000000e1",
        "meridian.wallet.gl.tax-payable=0199b000-0000-7000-8000-0000000000e2",
        "meridian.wallet.gl.bank-clearing=0199b000-0000-7000-8000-0000000000e3",
        "meridian.wallet.pin.iterations=1000", "meridian.wallet.dormancy-days=180"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WalletFlowIT {
    static final String TENANT = "0199b000-0000-7000-8000-00000000a001";
    static final String OTHER_TENANT = "0199b000-0000-7000-8000-00000000a002";
    static final UUID FEE_GL = UUID.fromString("0199b000-0000-7000-8000-0000000000e1");
    static final UUID TAX_GL = UUID.fromString("0199b000-0000-7000-8000-0000000000e2");
    static final UUID CLEARING_GL = UUID.fromString("0199b000-0000-7000-8000-0000000000e3");
    static final UUID DEPOSIT_ACCOUNT = UUID.fromString("0199b000-0000-7000-8000-00000000d001");
    static final UUID DEPOSIT_LEDGER = UUID.fromString("0199b000-0000-7000-8000-00000000d0aa");
    static final String AGENT_ID = "0199b000-0000-7000-8000-00000000a9e1";

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

    /** Business date reported by the stubbed business-day-service (null: none, the database date applies). */
    volatile LocalDate businessDate;

    final HttpClient http = HttpClient.newHttpClient();
    FakeLedger ledger;
    final List<JsonNode> sms = Collections.synchronizedList(new ArrayList<>());
    final List<JsonNode> payments = Collections.synchronizedList(new ArrayList<>());
    /** Whether each payment-service call was made with wallet-service's own identity (ServiceClient.asService). */
    final List<Boolean> paymentsAsService = Collections.synchronizedList(new ArrayList<>());
    final List<String> limitCalls = Collections.synchronizedList(new ArrayList<>());

    String alice;
    UUID aliceLedger;
    String bob;
    UUID bobLedger;
    String agent;
    UUID agentLedger;
    String carol;
    String p2pTxn;

    @BeforeAll
    void createLedger() {
        ledger = new FakeLedger(json, Clock.systemUTC());
    }

    @BeforeEach
    void stubServiceClient() {
        when(serviceClient.post(anyString(), anyString(), any(), any())).thenAnswer(i -> {
            String service = i.getArgument(0);
            String path = i.getArgument(1);
            JsonNode body = i.getArgument(2) == null ? json.createObjectNode() : json.valueToTree(i.getArgument(2));
            return switch (service) {
                case "ledger-service" -> ledger.post(service, path, i.getArgument(2), i.getArgument(3));
                case "limits-service" -> {
                    limitCalls.add(path);
                    ObjectNode r = json.createObjectNode().put("id", UUID.randomUUID().toString());
                    boolean blocked = body.has("amount") && new BigDecimal(body.get("amount").asString())
                            .compareTo(new BigDecimal("777")) == 0;
                    yield r.put("allowed", !blocked).put("status", blocked ? "REJECTED" : "RESERVED");
                }
                case "fraud-service" -> {
                    boolean block = new BigDecimal(body.get("amount").asString()).compareTo(new BigDecimal("666")) == 0;
                    yield json.createObjectNode().put("fraud_score_id", UUID.randomUUID().toString())  // fraud-service's shape
                            .put("decision", block ? "BLOCK" : "ALLOW").put("score", block ? 950 : 120);
                }
                case "notification-service" -> {
                    sms.add(body);
                    yield json.createObjectNode().put("id", UUID.randomUUID().toString()).put("status", "QUEUED");
                }
                case "payment-service" -> {
                    payments.add(body);
                    paymentsAsService.add(io.meridian.web.client.ServiceClient.asServiceActive());
                    yield json.createObjectNode().put("id", UUID.randomUUID().toString()).put("status", "PENDING");
                }
                default -> throw new IllegalArgumentException("unexpected POST " + service + path);
            };
        });
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> {
            String service = i.getArgument(0);
            String path = i.getArgument(1);
            if ("business-day-service".equals(service)) {
                return businessDate == null ? null : json.readTree(
                        "{\"data\":[{\"business_date\":\"" + businessDate + "\",\"is_current\":true}]}");
            }
            if ("deposit-service".equals(service)) {
                if (path.equals("/v1/deposit-accounts/" + DEPOSIT_ACCOUNT)) {
                    return json.readTree("{\"id\":\"" + DEPOSIT_ACCOUNT + "\",\"status\":\"ACTIVE\",\"currency\":\"KES\","
                            + "\"ledger_account_id\":\"" + DEPOSIT_LEDGER + "\",\"nickname\":\"Alice Wanjiru\"}");
                }
                throw new ApiException(404, "not_found", path);
            }
            return ledger.get(service, path);
        });
    }

    // ------------------------------------------------------------------ 1. opening and activation

    @Test
    @Order(1)
    void openWalletsConfigureTariffsAndActivate() throws Exception {
        post("/v1/wallet-fee-rules", """
                {"code":"P2P-STD","txn_type":"P2P","currency":"KES","basis":"FLAT","flat_fee":"5.00","tax_rate":"0.10",
                 "valid_from":"2020-01-01"}""", "ops", 201);
        post("/v1/wallet-fee-rules", """
                {"code":"BAD","txn_type":"P2P","currency":"KES","basis":"PERCENTAGE","valid_from":"2020-01-01"}""", "ops", 422);
        post("/v1/wallet-tier-limits", """
                {"kyc_tier":0,"wallet_type":"CUSTOMER","currency":"KES","per_txn_max":"10000","daily_amount":"30000",
                 "monthly_amount":"100000","max_balance":"50000","valid_from":"2020-01-01"}""", "ops", 201);
        post("/v1/wallet-tier-limits", """
                {"kyc_tier":0,"wallet_type":"CUSTOMER","currency":"KES","per_txn_max":"500","daily_amount":"100",
                 "valid_from":"2020-01-01"}""", "ops", 422);

        // weak PIN is refused; a good PIN is only stored as a hash
        post("/v1/wallets", walletBody("CUSTOMER", "Alice Wanjiru", "+254700000001", "1234"), "ops", 422);
        String key = UUID.randomUUID().toString();
        JsonNode a = send("POST", "/v1/wallets", walletBody("CUSTOMER", "Alice Wanjiru", "+254700000001", "4826"), key, "ops", TENANT, 201);
        JsonNode replay = send("POST", "/v1/wallets", walletBody("CUSTOMER", "Alice Wanjiru", "+254700000001", "4826"), key, "ops", TENANT, 201);
        assertThat(replay.get("id").asString()).isEqualTo(a.get("id").asString());
        alice = a.get("id").asString();
        assertThat(a.get("status").asString()).isEqualTo("PENDING");
        assertThat(a.get("has_pin").asBoolean()).isTrue();
        assertThat(a.has("pin_hash")).isFalse();
        String number = a.get("wallet_number").asString();
        assertThat(number).hasSize(12).startsWith("26");
        assertThat(WalletNumbers.isValid(number)).isTrue();
        // duplicate msisdn for the same type/currency
        post("/v1/wallets", walletBody("CUSTOMER", "Alice Again", "+254700000001", null), "ops", 409);

        bob = post("/v1/wallets", walletBody("CUSTOMER", "Bob Otieno", "+254700000002", null), "ops", 201).get("id").asString();
        carol = post("/v1/wallets", walletBody("CUSTOMER", "Carol Njeri", "+254700000003", "5937"), "ops", 201).get("id").asString();
        agent = post("/v1/wallets", """
                {"wallet_type":"AGENT","display_name":"Kibera Agent","currency":"KES","agent_id":"%s"}""".formatted(AGENT_ID),
                "ops", 201).get("id").asString();
        post("/v1/wallets", """
                {"wallet_type":"AGENT","display_name":"No owner","currency":"KES"}""", "ops", 422);

        for (String w : List.of(alice, bob, carol, agent)) {
            JsonNode act = post("/v1/wallets/" + w + "/activate", "{\"reason\":\"KYC tier 0\"}", "ops", 200);
            assertThat(act.get("status").asString()).isEqualTo("ACTIVE");
            assertThat(act.get("ledger_account_id").isNull()).isFalse();
        }
        aliceLedger = ledgerOf(alice);
        bobLedger = ledgerOf(bob);
        agentLedger = ledgerOf(agent);
        assertThat(ledger.calls("POST", "/v1/ledger-accounts")).hasSize(4);
        JsonNode opened = ledger.calls("POST", "/v1/ledger-accounts").getFirst().body();
        assertThat(opened.get("account_type").asString()).isEqualTo("LIABILITY");
        assertThat(opened.get("owner_type").asString()).isEqualTo("WALLET");
        // activating again is an invalid transition
        post("/v1/wallets/" + alice + "/activate", "{}", "ops", 409);
        // PATCH cannot change owner-bound fields
        HttpResponse<String> patch = raw("PATCH", "/v1/wallets/" + alice, "{\"currency\":\"USD\"}", null, "ops", TENANT);
        assertThat(patch.statusCode()).isEqualTo(422);
    }

    // ------------------------------------------------------------------ 2. cash-in and P2P

    @Test
    @Order(2)
    void cashInAndPeerToPeerWithFeesPinAndLimits() throws Exception {
        ledger.credit(agentLedger, "100000", null);
        JsonNode cashIn = post("/v1/wallet-transactions/cash-in", """
                {"agent_wallet_id":"%s","payee_msisdn":"+254700000001","amount":"20000.00","currency":"KES"}""".formatted(agent),
                "agent-1", 200);
        assertThat(cashIn.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(cashIn.get("txn_type").asString()).isEqualTo("CASH_IN");
        assertThat(cashIn.get("agent_id").asString()).isEqualTo(AGENT_ID);
        assertThat(cashIn.get("payee_name").asString()).isEqualTo("Alice Wanjiru");
        assertThat(ledger.balanceOf(aliceLedger)).isEqualByComparingTo("20000");
        assertThat(ledger.balanceOf(agentLedger)).isEqualByComparingTo("80000");

        // PIN is required for a customer-initiated debit when the wallet has one
        post("/v1/wallet-transactions/p2p", p2p("+254700000002", "100", null), "alice", 422, "pin_required");
        post("/v1/wallet-transactions/p2p", p2p("+254700000002", "100", "0000"), "alice", 422, "invalid_pin");

        JsonNode quote = post("/v1/wallet-transactions/quote", """
                {"txn_type":"P2P","amount":"1000","currency":"KES","payer_wallet_id":"%s","payee_msisdn":"+254700000002"}"""
                .formatted(alice), "alice", 200);
        assertThat(quote.get("fee_amount").asString()).isEqualTo("5.00");
        assertThat(quote.get("tax_amount").asString()).isEqualTo("0.50");
        assertThat(quote.get("total_debit_amount").asString()).isEqualTo("1005.50");
        assertThat(quote.get("payee_name").asString()).isEqualTo("Bob Otieno");
        assertThat(quote.get("limits_ok").asBoolean()).isTrue();

        String key = UUID.randomUUID().toString();
        JsonNode t = send("POST", "/v1/wallet-transactions/p2p", p2p("+254700000002", "1000", "4826"), key, "alice", TENANT, 200);
        p2pTxn = t.get("id").asString();
        assertThat(t.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(t.get("fee_amount").asString()).startsWith("5.00");
        assertThat(t.get("total_debit_amount").asString()).startsWith("1005.5");
        assertThat(t.get("reference").asString()).startsWith("WT");
        JsonNode replay = send("POST", "/v1/wallet-transactions/p2p", p2p("+254700000002", "1000", "4826"), key, "alice", TENANT, 200);
        assertThat(replay.get("id").asString()).isEqualTo(p2pTxn);
        assertThat(ledger.balanceOf(aliceLedger)).isEqualByComparingTo("18994.50");
        assertThat(ledger.balanceOf(bobLedger)).isEqualByComparingTo("1000");
        assertThat(ledger.glNet(FEE_GL)).isEqualByComparingTo("5.00");
        assertThat(ledger.glNet(TAX_GL)).isEqualByComparingTo("0.50");
        JsonNode posting = ledger.lastPosting().body();
        assertThat(posting.get("entries")).hasSize(4);
        assertThat(posting.get("source_type").asString()).isEqualTo("WALLET_TRANSACTION");
        JsonNode legs = get("/v1/wallet-transactions/" + p2pTxn + "/legs");
        assertThat(legs.get("data")).hasSize(4);

        // per-transaction tier limit, risk limits and fraud screening
        post("/v1/wallet-transactions/p2p", p2p("+254700000002", "15000", "4826"), "alice", 422, "limit_exceeded");
        post("/v1/wallet-transactions/p2p", p2p("+254700000002", "771.50", "4826"), "alice", 422, "limit_exceeded");
        post("/v1/wallet-transactions/p2p", p2p("+254700000002", "666", "4826"), "alice", 422, "fraud_blocked");
        assertThat(limitCalls).anyMatch(p -> p.endsWith("/release"));
        assertThat(limitCalls).anyMatch(p -> p.endsWith("/commit"));

        // same wallet, unknown payee, currency mismatch
        post("/v1/wallet-transactions/p2p", """
                {"payer_wallet_id":"%s","payee_wallet_id":"%s","amount":"10","currency":"KES","pin":"4826"}""".formatted(alice, alice),
                "alice", 422, "same_wallet");
        post("/v1/wallet-transactions/p2p", p2p("+254799999999", "10", "4826"), "alice", 422, "payee_not_found");
        post("/v1/wallet-transactions/p2p", """
                {"payer_wallet_id":"%s","payee_wallet_id":"%s","amount":"10","currency":"USD","pin":"4826"}""".formatted(alice, bob),
                "alice", 422, "currency_mismatch");
    }

    // ------------------------------------------------------------------ 3. failures are recorded

    @Test
    @Order(3)
    void ledgerRejectionIsRecordedAsFailedAndReplayedConsistently() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = """
                {"payer_wallet_id":"%s","payee_wallet_id":"%s","amount":"5000","currency":"KES","channel":"APP"}""".formatted(bob, alice);
        HttpResponse<String> r = raw("POST", "/v1/wallet-transactions/p2p", body, key, "bob", TENANT);
        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(json.readTree(r.body()).get("code").asString()).isEqualTo("insufficient_funds");
        HttpResponse<String> again = raw("POST", "/v1/wallet-transactions/p2p", body, key, "bob", TENANT);
        assertThat(again.statusCode()).isEqualTo(422);
        assertThat(json.readTree(again.body()).get("code").asString()).isEqualTo("insufficient_funds");
        JsonNode failed = get("/v1/wallets/" + bob + "/transactions?status=FAILED");
        assertThat(failed.get("data")).hasSize(1);
        assertThat(failed.get("data").get(0).get("failure_code").asString()).isEqualTo("insufficient_funds");
        assertThat(ledger.balanceOf(bobLedger)).isEqualByComparingTo("1000");
    }

    // ------------------------------------------------------------------ 4. cash-out with hold and confirmation

    @Test
    @Order(4)
    void cashOutHoldsFundsUntilTheCustomerConfirms() throws Exception {
        JsonNode co = post("/v1/wallet-transactions/cash-out", """
                {"agent_wallet_id":"%s","payer_wallet_id":"%s","amount":"2000","currency":"KES"}""".formatted(agent, alice),
                "agent-1", 200);
        String id = co.get("id").asString();
        assertThat(co.get("status").asString()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(co.get("confirmation_method").asString()).isEqualTo("PIN");
        assertThat(co.get("confirmation_expires_at").isNull()).isFalse();
        assertThat(ledger.pendingOf(aliceLedger)).isEqualByComparingTo("2000");
        JsonNode bal = get("/v1/wallets/" + alice + "/balance");
        assertThat(new BigDecimal(bal.get("available_balance").asString())).isEqualByComparingTo("16994.50");

        post("/v1/wallet-transactions/" + id + "/confirm", "{}", "agent-1", 422, "pin_required");
        post("/v1/wallet-transactions/" + id + "/confirm", "{\"pin\":\"1111\"}", "agent-1", 422, "invalid_pin");
        JsonNode done = post("/v1/wallet-transactions/" + id + "/confirm", "{\"pin\":\"4826\"}", "agent-1", 200);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(ledger.pendingOf(aliceLedger)).isEqualByComparingTo("0");
        assertThat(ledger.balanceOf(aliceLedger)).isEqualByComparingTo("16994.50");
        assertThat(ledger.balanceOf(agentLedger)).isEqualByComparingTo("82000");
        assertThat(ledger.calls("POST", "/v1/holds/")).anyMatch(c -> c.path().endsWith("/capture"));
        post("/v1/wallet-transactions/" + id + "/cancel", "{}", "agent-1", 409);

        // a second cash-out is cancelled: the hold is released
        JsonNode co2 = post("/v1/wallet-transactions/cash-out", """
                {"agent_wallet_id":"%s","payer_wallet_id":"%s","amount":"300","currency":"KES"}""".formatted(agent, alice),
                "agent-1", 200);
        JsonNode cancelled = post("/v1/wallet-transactions/" + co2.get("id").asString() + "/cancel",
                "{\"reason\":\"Customer changed mind\"}", "agent-1", 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(ledger.pendingOf(aliceLedger)).isEqualByComparingTo("0");

        // Bob has no PIN: confirmation by OTP sent to his phone
        JsonNode co3 = post("/v1/wallet-transactions/cash-out", """
                {"agent_wallet_id":"%s","payer_wallet_id":"%s","amount":"200","currency":"KES"}""".formatted(agent, bob),
                "agent-1", 200);
        assertThat(co3.get("confirmation_method").asString()).isEqualTo("OTP");
        String otp = lastCode("+254700000002");
        post("/v1/wallet-transactions/" + co3.get("id").asString() + "/confirm", "{\"otp\":\"000000\"}", "agent-1", 422, "invalid_otp");
        JsonNode ok = post("/v1/wallet-transactions/" + co3.get("id").asString() + "/confirm", "{\"otp\":\"" + otp + "\"}", "agent-1", 200);
        assertThat(ok.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(ledger.balanceOf(bobLedger)).isEqualByComparingTo("800");
    }

    // ------------------------------------------------------------------ 5. two-phase for upstream services + expiry

    @Test
    @Order(5)
    void systemTwoPhaseConfirmAndExpiry() throws Exception {
        String biller = post("/v1/wallets", """
                {"wallet_type":"ESCROW","display_name":"Kenya Power collections","currency":"KES"}""", "ops", 201).get("id").asString();
        post("/v1/wallets/" + biller + "/activate", "{}", "ops", 200);
        JsonNode held = post("/v1/wallet-transactions", """
                {"txn_type":"BILL_PAYMENT","channel":"APP","initiator_type":"CUSTOMER","amount":"150","currency":"KES",
                 "payer_wallet_id":"%s","payee_wallet_id":"%s","pin":"4826","two_phase":true,
                 "biller_id":"0199b000-0000-7000-8000-0000000000b1"}""".formatted(alice, biller), "biller-service", 201);
        assertThat(held.get("status").asString()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(held.get("confirmation_method").asString()).isEqualTo("SYSTEM");
        JsonNode confirmed = post("/v1/wallet-transactions/" + held.get("id").asString() + "/confirm", "{}", "biller-service", 200);
        assertThat(confirmed.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(ledger.balanceOf(ledgerOf(biller))).isEqualByComparingTo("150");

        JsonNode stale = post("/v1/wallet-transactions", """
                {"txn_type":"BILL_PAYMENT","channel":"APP","initiator_type":"CUSTOMER","amount":"50","currency":"KES",
                 "payer_wallet_id":"%s","payee_wallet_id":"%s","pin":"4826","two_phase":true}""".formatted(alice, biller),
                "biller-service", 201);
        asTenant(() -> jdbc.sql("update wallet_transaction set confirmation_expires_at = now() - interval '1 minute' where id = ?::uuid")
                .param(stale.get("id").asString()).update());
        JsonNode expired = post("/v1/wallet-transactions/expire-pending", null, "scheduler", 200);
        assertThat(expired.get("expired").asInt()).isEqualTo(1);
        assertThat(get("/v1/wallet-transactions/" + stale.get("id").asString()).get("status").asString()).isEqualTo("EXPIRED");
        assertThat(ledger.pendingOf(aliceLedger)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ 6. reversal with maker-checker

    @Test
    @Order(6)
    void reversalNeedsADifferentCheckerAndRefundsFees() throws Exception {
        // SEC-21: no direct reversal without a prior request by someone else (customer transactions have no staff
        // initiator, so "not the initiator" alone allowed one staff member to reverse them)
        post("/v1/wallet-transactions/" + p2pTxn + "/reverse", "{\"reason\":\"self\"}", "alice", 422, "reversal_request_required");
        post("/v1/wallet-transactions/" + p2pTxn + "/reverse", "{\"reason\":\"insider\"}", "ops9", 422, "reversal_request_required");
        JsonNode requested = post("/v1/wallet-transactions/" + p2pTxn + "/request-reversal",
                "{\"reason\":\"Sent to wrong number\"}", "ops1", 200);
        assertThat(requested.get("reversal_requested_by").asString()).isEqualTo("ops1");
        post("/v1/wallet-transactions/" + p2pTxn + "/request-reversal", "{\"reason\":\"again\"}", "ops1", 409);
        post("/v1/wallet-transactions/" + p2pTxn + "/reverse", "{\"reason\":\"approve own\"}", "ops1", 422, "four_eyes_violation");
        // Bob already spent part of the money: the ledger refuses, nothing changes
        post("/v1/wallet-transactions/" + p2pTxn + "/reverse", "{\"reason\":\"Approved\"}", "ops2", 422, "insufficient_funds");
        assertThat(get("/v1/wallet-transactions/" + p2pTxn).get("status").asString()).isEqualTo("COMPLETED");
        ledger.credit(bobLedger, "500", null);
        BigDecimal aliceBefore = ledger.balanceOf(aliceLedger);
        JsonNode rev = post("/v1/wallet-transactions/" + p2pTxn + "/reverse", "{\"reason\":\"Approved\"}", "ops2", 200);
        assertThat(rev.get("txn_type").asString()).isEqualTo("REVERSAL");
        assertThat(rev.get("original_transaction_id").asString()).isEqualTo(p2pTxn);
        assertThat(get("/v1/wallet-transactions/" + p2pTxn).get("status").asString()).isEqualTo("REVERSED");
        assertThat(ledger.balanceOf(aliceLedger).subtract(aliceBefore)).isEqualByComparingTo("1005.50");
        assertThat(ledger.balanceOf(bobLedger)).isEqualByComparingTo("300");
        assertThat(ledger.glNet(FEE_GL)).isEqualByComparingTo("0");
        JsonNode revLegs = get("/v1/wallet-transactions/" + rev.get("id").asString() + "/legs");
        assertThat(revLegs.get("data")).hasSize(4);
        post("/v1/wallet-transactions/" + p2pTxn + "/reverse", "{\"reason\":\"again\"}", "ops2", 409);
    }

    // ------------------------------------------------------------------ 7. lifecycle

    @Test
    @Order(7)
    void suspendReactivateBlockAndClose() throws Exception {
        ledger.credit(bobLedger, "1000", null);
        post("/v1/wallets/" + bob + "/suspend", "{\"reason\":\"Investigation\"}", "ops1", 200);
        assertThat(ledger.statusOf(bobLedger)).isEqualTo("FROZEN_DEBIT");
        post("/v1/wallet-transactions/p2p", """
                {"payer_wallet_id":"%s","payee_wallet_id":"%s","amount":"10","currency":"KES"}""".formatted(bob, alice),
                "bob", 422, "wallet_not_active");
        post("/v1/wallets/" + bob + "/reactivate", "{\"reason\":\"cleared\"}", "ops1", 422, "four_eyes_violation");
        JsonNode back = post("/v1/wallets/" + bob + "/reactivate", "{\"reason\":\"cleared\"}", "ops2", 200);
        assertThat(back.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(ledger.statusOf(bobLedger)).isEqualTo("ACTIVE");
        JsonNode history = get("/v1/wallets/" + bob + "/status-history");
        assertThat(history.get("data").size()).isGreaterThanOrEqualTo(4);

        post("/v1/wallets/" + bob + "/close", "{\"reason\":\"Customer request\"}", "ops1", 422, "balance_not_zero");
        JsonNode closed = post("/v1/wallets/" + bob + "/close", """
                {"reason":"Customer request","sweep_to_wallet_id":"%s"}""".formatted(alice), "ops1", 200);
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(ledger.balanceOf(bobLedger)).isEqualByComparingTo("0");
        assertThat(ledger.statusOf(bobLedger)).isEqualTo("CLOSED");
        post("/v1/wallets/" + bob + "/activate", "{}", "ops1", 409);
    }

    // ------------------------------------------------------------------ 8. PIN lockout and reset

    @Test
    @Order(8)
    void pinLockoutAndOtpReset() throws Exception {
        JsonNode v = post("/v1/wallets/" + carol + "/verify-pin", "{\"pin\":\"1111\"}", "carol", 200);
        assertThat(v.get("valid").asBoolean()).isFalse();
        assertThat(v.get("remaining_attempts").asInt()).isEqualTo(2);
        post("/v1/wallets/" + carol + "/verify-pin", "{\"pin\":\"2222\"}", "carol", 200);
        JsonNode locked = post("/v1/wallets/" + carol + "/verify-pin", "{\"pin\":\"3333\"}", "carol", 200);
        assertThat(locked.get("code").asString()).isEqualTo("pin_locked");
        JsonNode stillLocked = post("/v1/wallets/" + carol + "/verify-pin", "{\"pin\":\"5937\"}", "carol", 200);
        assertThat(stillLocked.get("valid").asBoolean()).isFalse();
        assertThat(get("/v1/wallets/" + carol).get("pin_locked_until").isNull()).isFalse();
        post("/v1/wallets/" + carol + "/set-pin", "{\"new_pin\":\"8642\",\"current_pin\":\"5937\"}", "carol", 422, "pin_locked");

        JsonNode reset = post("/v1/wallet-pin-resets", """
                {"wallet_id":"%s","channel":"USSD","verification_method":"OTP"}""".formatted(carol), "carol", 201);
        assertThat(reset.get("status").asString()).isEqualTo("REQUESTED");
        assertThat(reset.get("notification_id").isNull()).isFalse();
        String otp = lastCode("+254700000003");
        String id = reset.get("id").asString();
        post("/v1/wallet-pin-resets/" + id + "/complete", "{\"new_pin\":\"8642\"}", "carol", 409);
        post("/v1/wallet-pin-resets/" + id + "/verify", "{\"otp\":\"999999\"}", "carol", 422, "invalid_otp");
        assertThat(post("/v1/wallet-pin-resets/" + id + "/verify", "{\"otp\":\"" + otp + "\"}", "carol", 200)
                .get("status").asString()).isEqualTo("VERIFIED");
        post("/v1/wallet-pin-resets/" + id + "/complete", "{\"new_pin\":\"1111\"}", "carol", 422, "weak_pin");
        assertThat(post("/v1/wallet-pin-resets/" + id + "/complete", "{\"new_pin\":\"8642\"}", "carol", 200)
                .get("status").asString()).isEqualTo("COMPLETED");
        JsonNode ok = post("/v1/wallets/" + carol + "/verify-pin", "{\"pin\":\"8642\"}", "carol", 200);
        assertThat(ok.get("valid").asBoolean()).isTrue();
        JsonNode changed = post("/v1/wallets/" + carol + "/set-pin", "{\"new_pin\":\"7351\",\"current_pin\":\"8642\"}", "carol", 200);
        assertThat(changed.get("has_pin").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------ 9. bank links

    @Test
    @Order(9)
    void bankLinksAndBankTransfers() throws Exception {
        JsonNode onUs = post("/v1/wallet-bank-links", """
                {"wallet_id":"%s","bank_code":"MERI","account_number":"0012345678","deposit_account_id":"%s",
                 "currency":"KES","verification_method":"NAME_ENQUIRY"}""".formatted(alice, DEPOSIT_ACCOUNT), "alice", 201);
        String link = onUs.get("id").asString();
        post("/v1/wallet-transactions/wallet-to-bank", w2b(link, "500"), "alice", 422, "bank_link_not_verified");
        JsonNode verified = post("/v1/wallet-bank-links/" + link + "/verify", "{}", "alice", 200);
        assertThat(verified.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(verified.get("account_name").asString()).isEqualTo("Alice Wanjiru");
        BigDecimal before = ledger.balanceOf(aliceLedger);
        JsonNode out = post("/v1/wallet-transactions/wallet-to-bank", w2b(link, "500"), "alice", 200);
        assertThat(out.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(out.get("counterparty_ledger_account_id").asString()).isEqualTo(DEPOSIT_LEDGER.toString());
        assertThat(ledger.glNet(DEPOSIT_LEDGER)).isEqualByComparingTo("500");
        assertThat(before.subtract(ledger.balanceOf(aliceLedger))).isEqualByComparingTo("500");
        JsonNode in = post("/v1/wallet-transactions/bank-to-wallet", """
                {"payee_wallet_id":"%s","wallet_bank_link_id":"%s","amount":"200","currency":"KES"}""".formatted(alice, link),
                "alice", 200);
        assertThat(in.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(ledger.glNet(DEPOSIT_LEDGER)).isEqualByComparingTo("300");

        // another bank: manual verification by a checker, transfer through the clearing account + payment-service
        JsonNode ext = post("/v1/wallet-bank-links", """
                {"wallet_id":"%s","bank_code":"KCBLKENX","account_number":"1100223344","currency":"KES",
                 "verification_method":"MANUAL"}""".formatted(alice), "alice", 201);
        post("/v1/wallet-bank-links/" + ext.get("id").asString() + "/verify", "{}", "alice", 422, "four_eyes_violation");
        post("/v1/wallet-bank-links/" + ext.get("id").asString() + "/verify", "{}", "ops2", 200);
        JsonNode ext2 = post("/v1/wallet-transactions/wallet-to-bank", w2b(ext.get("id").asString(), "250"), "alice", 200);
        assertThat(ext2.get("payment_id").isNull()).isFalse();
        assertThat(ledger.glNet(CLEARING_GL)).isEqualByComparingTo("250");
        assertThat(payments).hasSize(1);
        assertThat(payments.getFirst().get("creditor_account_number").asString()).isEqualTo("1100223344");
        // the wallet's ALLOW travels with the payout (server-to-server) so payment-service does not score it twice
        assertThat(ext2.get("fraud_decision").asString()).isEqualTo("ALLOW");
        assertThat(payments.getFirst().get("upstream_fraud_score_id").asString())
                .isEqualTo(ext2.get("fraud_score_id").asString());
        assertThat(payments.getFirst().get("debtor_clearing_ledger_account_id").asString()).isEqualTo(CLEARING_GL.toString());
        assertThat(payments.getFirst().has("debtor_ledger_account_id")).isFalse();
        assertThat(paymentsAsService.getFirst()).isTrue();
        post("/v1/wallet-transactions/bank-to-wallet", """
                {"payee_wallet_id":"%s","wallet_bank_link_id":"%s","amount":"20","currency":"KES"}""".formatted(alice, ext.get("id").asString()),
                "alice", 422, "mandate_required");
        JsonNode removed = post("/v1/wallet-bank-links/" + ext.get("id").asString() + "/remove", "{\"reason\":\"closed\"}", "alice", 200);
        assertThat(removed.get("status").asString()).isEqualTo("REMOVED");
    }

    // ------------------------------------------------------------------ 10. read models

    @Test
    @Order(10)
    void lookupLimitsStatementSummaryAndReceipt() throws Exception {
        JsonNode found = get("/v1/wallets/lookup?msisdn=%2B254700000001");
        assertThat(found.get("id").asString()).isEqualTo(alice);
        assertThat(raw("GET", "/v1/wallets/lookup?msisdn=%2B254711111111", null, null, "ops", TENANT).statusCode()).isEqualTo(404);

        JsonNode limits = get("/v1/wallets/" + alice + "/limits");
        JsonNode l = limits.get("limits").get(0);
        assertThat(l.get("per_txn_max").asString()).startsWith("10000");
        assertThat(new BigDecimal(l.get("daily_used").asString())).isPositive();
        assertThat(new BigDecimal(l.get("daily_remaining").asString())).isLessThan(new BigDecimal("30000"));

        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        JsonNode st = get("/v1/wallets/" + alice + "/statement?from=" + today.minusDays(1) + "&to=" + today);
        assertThat(st.get("entry_count").asInt()).isGreaterThan(3);
        assertThat(new BigDecimal(st.get("closing_balance").asString())).isEqualByComparingTo(ledger.balanceOf(aliceLedger));
        JsonNode req = post("/v1/wallets/" + alice + "/statements", """
                {"from_date":"%s","to_date":"%s","format":"PDF"}""".formatted(today.minusDays(1), today), "alice", 200);
        assertThat(req.get("notification_id").isNull()).isFalse();

        JsonNode summary = get("/v1/wallet-transactions/summary?agent_id=" + AGENT_ID + "&status=COMPLETED");
        assertThat(summary.get("groups")).anyMatch(g -> g.get("txn_type").asString().equals("CASH_IN")
                && new BigDecimal(g.get("amount").asString()).compareTo(new BigDecimal("20000")) == 0);
        assertThat(summary.get("groups")).anyMatch(g -> g.get("txn_type").asString().equals("CASH_OUT"));

        HttpResponse<String> receipt = raw("GET", "/v1/wallet-transactions/" + p2pTxn + "/receipt", null, null, "alice", TENANT);
        assertThat(receipt.statusCode()).isEqualTo(200);
        assertThat(receipt.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
        assertThat(receipt.body()).contains("MERIDIAN WALLET RECEIPT").contains("Bob Otieno");

        JsonNode txns = get("/v1/wallets/" + alice + "/transactions?txn_type=P2P&limit=1");
        assertThat(txns.get("data")).hasSize(1);
    }

    // ------------------------------------------------------------------ 11. dormancy, tier upgrade, isolation, events

    @Test
    @Order(11)
    void dormancyTierUpgradeIsolationAndEvents() throws Exception {
        asTenant(() -> jdbc.sql("""
                update wallet set last_activity_at = now() - interval '400 days', activated_at = now() - interval '400 days'
                where id = ?::uuid""").param(carol).update());
        JsonNode eod = post("/v1/wallets/eod/dormancy?business_date=" + LocalDate.now(java.time.ZoneOffset.UTC), null, "scheduler", 200);
        assertThat(eod.get("marked_dormant").asInt()).isEqualTo(1);
        assertThat(get("/v1/wallets/" + carol).get("status").asString()).isEqualTo("DORMANT");
        assertThat(post("/v1/wallets/" + carol + "/activate", "{\"reason\":\"customer back\"}", "ops", 200)
                .get("status").asString()).isEqualTo("ACTIVE");

        post("/v1/wallets/" + alice + "/upgrade-tier", """
                {"kyc_tier":0,"kyc_profile_id":"%s"}""".formatted(UUID.randomUUID()), "ops", 422, "tier_not_higher");
        JsonNode up = post("/v1/wallets/" + alice + "/upgrade-tier", """
                {"kyc_tier":2,"kyc_profile_id":"%s"}""".formatted(UUID.randomUUID()), "ops", 200);
        assertThat(up.get("kyc_tier").asInt()).isEqualTo(2);

        assertThat(raw("GET", "/v1/wallets/" + alice, null, null, "x", OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(json.readTree(raw("GET", "/v1/wallet-transactions", null, null, "x", OTHER_TENANT).body())
                .get("data")).isEmpty();

        List<String> types = asTenant(() -> jdbc.sql("select distinct event_type from outbox").query(String.class).list());
        assertThat(types).contains("wallets.wallet.created", "wallets.wallet.status_changed", "wallets.wallet.tier_upgraded",
                "wallets.wallet.pin_locked", "wallets.wallet.pin_changed", "wallets.wallet_transaction.initiated",
                "wallets.wallet_transaction.completed", "wallets.wallet_transaction.failed",
                "wallets.wallet_transaction.cancelled", "wallets.wallet_transaction.reversal_requested",
                "wallets.wallet_transaction.reversed", "wallets.wallet_bank_link.verified",
                "wallets.wallet_bank_link.removed", "wallets.wallet_pin_reset.requested",
                "wallets.wallet_pin_reset.completed");
        String payload = asTenant(() -> jdbc.sql("select payload::text from outbox where event_type = 'wallets.wallet.created' limit 1")
                .query(String.class).single());
        assertThat(payload).doesNotContain("pin_hash");
    }

    // ------------------------------------------------------------------ 12. business date

    @Test
    @Order(12)
    void tariffsEffectiveOnTheBusinessDate() throws Exception {
        post("/v1/wallet-fee-rules", """
                {"code":"P2P-2099","txn_type":"P2P","currency":"KES","wallet_type":"CUSTOMER","basis":"FLAT",
                 "flat_fee":"7.00","valid_from":"2099-01-01"}""", "ops", 201);
        String quote = """
                {"txn_type":"P2P","amount":"100","currency":"KES","payer_wallet_id":"%s","payee_msisdn":"+254700000002"}"""
                .formatted(alice);
        assertThat(post("/v1/wallet-transactions/quote", quote, "alice", 200).get("fee_amount").asString())
                .isEqualTo("5.00");
        try {
            businessDate = LocalDate.parse("2099-03-01");
            businessDates.invalidate();
            assertThat(post("/v1/wallet-transactions/quote", quote, "alice", 200).get("fee_amount").asString())
                    .isEqualTo("7.00");
        } finally {
            businessDate = null;
            businessDates.invalidate();
        }
    }

    // ------------------------------------------------------------------ 13. SEC-07 concurrent reversals

    @Test
    @Order(13)
    void concurrentPartialReversalsNeverExceedTheOriginal() throws Exception {
        ledger.credit(aliceLedger, "1000", null);
        String dan = post("/v1/wallets", walletBody("CUSTOMER", "Dan Kamau", "+254700000009", null), "ops", 201)
                .get("id").asString();
        post("/v1/wallets/" + dan + "/activate", "{\"reason\":\"KYC tier 0\"}", "ops", 200);
        UUID danLedger = ledgerOf(dan);
        JsonNode t = post("/v1/wallet-transactions/p2p", p2p("+254700000009", "100", "4826"), "alice", 200);
        String txn = t.get("id").asString();
        assertThat(t.get("status").asString()).isEqualTo("COMPLETED");
        ledger.credit(danLedger, "500", null);
        UUID bobLedger = danLedger; // the payee of this test
        BigDecimal bobBefore = ledger.balanceOf(bobLedger);
        int n = 6;
        List<Integer> codes = Collections.synchronizedList(new ArrayList<>());
        for (int round = 0; round < 2; round++) {
            // SEC-21: each reversal needs its own request; distinct checkers race to reverse 40 of 100
            post("/v1/wallet-transactions/" + txn + "/request-reversal", "{\"reason\":\"race\",\"amount\":\"40\"}",
                    "ops-maker", 200);
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            int r = round;
            try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    String checker = "ops-race-" + r + "-" + i;
                    fs.add(pool.submit(() -> {
                        start.await();
                        codes.add(raw("POST", "/v1/wallet-transactions/" + txn + "/reverse",
                                "{\"reason\":\"race\",\"amount\":\"40\"}", UUID.randomUUID().toString(), checker, TENANT)
                                .statusCode());
                        return null;
                    }));
                }
                start.countDown();
                for (var f : fs) {
                    f.get();
                }
            }
        }
        assertThat(codes).filteredOn(c -> c == 200).hasSize(2);
        assertThat(codes).filteredOn(c -> c == 422).hasSize(2 * n - 2);
        JsonNode orig = get("/v1/wallet-transactions/" + txn);
        assertThat(new BigDecimal(orig.get("reversed_amount").asString())).isEqualByComparingTo("80");
        assertThat(orig.get("status").asString()).isEqualTo("COMPLETED");
        Long completed = asTenant(() -> jdbc.sql("""
                select count(*) from wallet_transaction where original_transaction_id = ?::uuid and txn_type = 'REVERSAL'
                and status = 'COMPLETED'""").param(txn).query(Long.class).single());
        assertThat(completed).isEqualTo(2L);
        assertThat(bobBefore.subtract(ledger.balanceOf(bobLedger))).isEqualByComparingTo("80");

        // the remaining 20 races too: exactly one full reversal completes the transaction
        post("/v1/wallet-transactions/" + txn + "/request-reversal", "{\"reason\":\"rest\"}", "ops-maker", 200);
        codes.clear();
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String checker = "ops-full-" + i;
                fs.add(pool.submit(() -> {
                    go.await();
                    codes.add(raw("POST", "/v1/wallet-transactions/" + txn + "/reverse", "{\"reason\":\"rest\"}",
                            UUID.randomUUID().toString(), checker, TENANT).statusCode());
                    return null;
                }));
            }
            go.countDown();
            for (var f : fs) {
                f.get();
            }
        }
        assertThat(codes).filteredOn(c -> c == 200).hasSize(1);
        assertThat(codes).allMatch(c -> c == 200 || c == 409 || c == 422);
        orig = get("/v1/wallet-transactions/" + txn);
        assertThat(new BigDecimal(orig.get("reversed_amount").asString())).isEqualByComparingTo("100");
        assertThat(orig.get("status").asString()).isEqualTo("REVERSED");
        assertThat(bobBefore.subtract(ledger.balanceOf(bobLedger))).isEqualByComparingTo("100");
    }

    // ------------------------------------------------------------------ helpers

    private UUID ledgerOf(String wallet) throws Exception {
        return UUID.fromString(get("/v1/wallets/" + wallet).get("ledger_account_id").asString());
    }

    private String walletBody(String type, String name, String msisdn, String pin) {
        return "{\"wallet_type\":\"" + type + "\",\"display_name\":\"" + name + "\",\"currency\":\"KES\",\"msisdn\":\"" + msisdn
                + "\",\"opened_channel\":\"APP\"" + (pin == null ? "" : ",\"pin\":\"" + pin + "\"") + "}";
    }

    private String p2p(String payeeMsisdn, String amount, String pin) {
        return "{\"payer_wallet_id\":\"" + alice + "\",\"payee_msisdn\":\"" + payeeMsisdn + "\",\"amount\":\"" + amount
                + "\",\"currency\":\"KES\",\"channel\":\"APP\"" + (pin == null ? "" : ",\"pin\":\"" + pin + "\"") + "}";
    }

    private String w2b(String link, String amount) {
        return "{\"payer_wallet_id\":\"" + alice + "\",\"wallet_bank_link_id\":\"" + link + "\",\"amount\":\"" + amount
                + "\",\"currency\":\"KES\",\"pin\":\"4826\"}";
    }

    private String lastCode(String msisdn) {
        for (int i = sms.size() - 1; i >= 0; i--) {
            JsonNode n = sms.get(i);
            if (msisdn.equals(n.path("destination").asString())) {
                Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(n.get("body").asString());
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        throw new AssertionError("no code sent to " + msisdn);
    }

    private <T> T asTenant(java.util.concurrent.Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", body);
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
