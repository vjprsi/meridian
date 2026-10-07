package io.meridian.merchantchannel;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
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
 * merchant-channel-service over HTTP (dev security mode: X-Tenant-Id, X-Merchant-Id, X-Merchant-User-Id, X-Auth-Time)
 * against PostgreSQL 18, with the domain services replaced by a stateful {@link FakeMerchantBank} behind the mocked
 * {@link ServiceClient}. Two merchants of one tenant, A (stores s1, s2) and B (store sb), each own objects of every
 * family; A has an owner, a manager of s1, cashiers of s1 and s2 and an all-stores viewer.
 *
 * <p>Proves: first-owner bootstrap and refusal of unknown users and ineligible merchants; staff administration (OWNER,
 * step-up); cashier store scoping (stores, terminals, sales, transactions, refunds); sale -> dynamic QR (body built
 * server-side) -> paid; refunds with four-eyes (requester never decides, executed downstream as requester then
 * approver) and the cumulative cap; payout account change with step-up and approval by another user; settlements, fee
 * statement, payment links, integrations; idempotent replays; 404 for every family of the other merchant with nothing
 * written downstream; audit and denial events.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev", "meridian.merchant.step-up-max-age=PT5M",
        "meridian.business-date.source=clock"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MerchantChannelFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000d501";
    static final String MA = "0199a000-0000-7000-8000-0000000d00a1";
    static final String MB = "0199a000-0000-7000-8000-0000000d00b1";
    static final String MX = "0199a000-0000-7000-8000-0000000d00c1";
    static final String CUST_A = "0199a000-0000-7000-8000-0000000c00a1";
    static final String CUST_B = "0199a000-0000-7000-8000-0000000c00b1";
    static final String OWNER_A = "0199a000-0000-7000-8000-0000000da001";
    static final String OWNER_B = "0199a000-0000-7000-8000-0000000db001";
    static final String MS = FakeMerchantBank.MS;
    static final String WS = FakeMerchantBank.WS;
    static final String DEP = "deposit-service";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    FakeMerchantBank bank;
    final HttpClient http = HttpClient.newHttpClient();

    record Who(String merchant, String user) {}

    final Who ownerA = new Who(MA, OWNER_A);
    final Who ownerB = new Who(MB, OWNER_B);
    Who manager;
    Who cashier1;
    Who cashier2;
    Who viewer;

    String s1;
    String s2;
    String sb;
    String t1;
    String t2;
    String t3;
    String tb;
    String accA1;
    String accA2;
    String accAusd;
    String accB;
    String accB2;
    String setA;
    String setB;
    String linkB;
    String txnB;
    String txnUnattributed;
    String sale1;
    String paidTxn;

    @BeforeAll
    void seed() {
        bank = new FakeMerchantBank(json);
        accA1 = account(CUST_A, "0011223344", "KES");
        accA2 = account(CUST_A, "0011225566", "KES");
        accAusd = account(CUST_A, "0011227788", "USD");
        accB = account(CUST_B, "0099887766", "KES");
        accB2 = account(CUST_B, "0099881122", "KES");
        String plan = id(bank.add(MS, "/v1/merchant-fee-plans", """
                {"code":"STD","name":"Standard","currency":"KES","mdr_percentage":"0.01","fixed_fee":"0","refund_fee":"5"}"""));
        merchant(MA, "M0001", "Duka A", CUST_A, accA1, plan, "ACTIVE");
        merchant(MB, "M0002", "Duka B", CUST_B, accB, plan, "ACTIVE");
        merchant(MX, "M0003", "Duka X", CUST_A, null, plan, "SUSPENDED");
        s1 = store(MA, "S1");
        s2 = store(MA, "S2");
        sb = store(MB, "SB");
        t1 = terminal(MA, s1, "T0001");
        t2 = terminal(MA, s2, "T0002");
        t3 = terminal(MA, s1, "T0003");
        tb = terminal(MB, sb, "T0009");
        setA = settlement(MA, "2026-09-01", "2026-09-30", "PAID", "1000.00", "10.00", "0.00", "990.00", 5);
        settlement(MA, "2026-10-01", "2026-10-01", "APPROVED", "200.00", "2.00", "20.00", "178.00", 2);
        settlement(MA, "2026-08-01", "2026-08-31", "REJECTED", "999.00", "9.99", "0.00", "989.01", 3);
        setB = settlement(MB, "2026-09-01", "2026-09-30", "PAID", "500.00", "5.00", "0.00", "495.00", 2);
        linkB = id(bank.add(MS, "/v1/merchant-payment-links", """
                {"merchant_id":"%s","link_code":"LB","currency":"KES","description":"B link","is_active":true}""".formatted(MB)));
        txnB = id(bank.payment(MB, "70.00", null));
        txnUnattributed = id(bank.payment(MA, "40.00", null));
    }

    @BeforeEach
    void wire() {
        bank.wire(serviceClient);
    }

    // ------------------------------------------------------------------ identity and staff

    @Test
    @Order(1)
    void firstOwnerIsBootstrappedUnknownUsersAndIneligibleMerchantsAreRefused() throws Exception {
        assertProblem(call(new Who(MX, UUID.randomUUID().toString()), "GET", "/v1/merchant/me", null), 403,
                "merchant_not_eligible");
        JsonNode me = ok(call(ownerA, "GET", "/v1/merchant/me", null));
        assertThat(me.path("user").path("role").asString()).isEqualTo("OWNER");
        assertThat(me.path("user").path("id").asString()).isEqualTo(OWNER_A);
        assertThat(me.path("merchant").path("merchant_code").asString()).isEqualTo("M0001");
        assertThat(me.path("merchant").has("customer_id")).isFalse();             // only public merchant fields
        assertThat(me.path("stores")).hasSize(2);
        assertProblem(call(new Who(MA, UUID.randomUUID().toString()), "GET", "/v1/merchant/me", null), 403,
                "merchant_user_unknown");
        ok(call(ownerB, "GET", "/v1/merchant/me", null));
        // the owner of A presenting merchant B: the two claims do not belong together
        assertProblem(call(new Who(MB, OWNER_A), "GET", "/v1/merchant/me", null), 403, "merchant_user_unknown");
        assertThat(rows("select count(*) from merchant_user where merchant_id = '" + MA + "'")).isEqualTo(1);
    }

    @Test
    @Order(2)
    void theOwnerManagesStaffWithStepUp() throws Exception {
        String body = "{\"display_name\":\"Mia Manager\",\"email\":\"mia@a.test\",\"role\":\"MANAGER\",\"store_ids\":[\"%s\"]}"
                .formatted(s1);
        assertProblem(call(ownerA, "POST", "/v1/merchant/staff", body, false), 401, "step_up_required");
        manager = new Who(MA, id(ok(call(ownerA, "POST", "/v1/merchant/staff", body))));
        cashier1 = new Who(MA, id(ok(call(ownerA, "POST", "/v1/merchant/staff",
                "{\"display_name\":\"Cash One\",\"email\":\"c1@a.test\",\"role\":\"CASHIER\",\"store_ids\":[\"%s\"]}"
                        .formatted(s1)))));
        cashier2 = new Who(MA, id(ok(call(ownerA, "POST", "/v1/merchant/staff",
                "{\"display_name\":\"Cash Two\",\"email\":\"c2@a.test\",\"role\":\"CASHIER\",\"store_ids\":[\"%s\"]}"
                        .formatted(s2)))));
        viewer = new Who(MA, id(ok(call(ownerA, "POST", "/v1/merchant/staff",
                "{\"display_name\":\"Vic Viewer\",\"email\":\"v@a.test\",\"role\":\"VIEWER\",\"all_stores\":true}"))));
        // a store of another merchant cannot be assigned
        assertProblem(call(ownerA, "POST", "/v1/merchant/staff",
                "{\"display_name\":\"X\",\"email\":\"x@a.test\",\"role\":\"CASHIER\",\"store_ids\":[\"%s\"]}".formatted(sb)),
                404, "not_found");
        assertProblem(call(cashier1, "POST", "/v1/merchant/staff",
                "{\"display_name\":\"X\",\"email\":\"x@a.test\",\"role\":\"CASHIER\"}"), 403, "role_not_allowed");
        assertProblem(call(ownerA, "PATCH", "/v1/merchant/staff/" + OWNER_A, "{\"role\":\"MANAGER\"}"), 422,
                "own_role_change_forbidden");
        assertProblem(call(ownerA, "POST", "/v1/merchant/staff/" + OWNER_A + "/suspend", "{}"), 422,
                "own_status_change_forbidden");
        JsonNode stores = ok(call(cashier1, "GET", "/v1/merchant/staff/" + cashier1.user() + "/stores", null));
        assertThat(stores.path("data")).hasSize(1);
        assertProblem(call(cashier1, "GET", "/v1/merchant/staff", null), 403, "role_not_allowed");
        assertThat(ok(call(manager, "GET", "/v1/merchant/staff", null)).path("data")).hasSize(5);
        assertThat(events("channels.merchant_user.changed")).isGreaterThanOrEqualTo(5);
    }

    // ------------------------------------------------------------------ store scoping

    @Test
    @Order(3)
    void cashiersSeeOnlyTheirStoresAndTerminals() throws Exception {
        JsonNode mine = ok(call(cashier1, "GET", "/v1/merchant/stores", null));
        assertThat(ids(mine)).containsExactly(s1);
        assertProblem(call(cashier1, "GET", "/v1/merchant/stores/" + s2, null), 404, "not_found");
        assertProblem(call(cashier1, "GET", "/v1/merchant/stores/" + sb, null), 404, "not_found");
        assertThat(ids(ok(call(cashier1, "GET", "/v1/merchant/terminals", null)))).containsExactlyInAnyOrder(t1, t3);
        assertProblem(call(cashier1, "GET", "/v1/merchant/terminals/" + t2, null), 404, "not_found");
        assertProblem(call(cashier1, "GET", "/v1/merchant/terminals?store_id=" + s2, null), 404, "not_found");
        assertThat(ids(ok(call(ownerA, "GET", "/v1/merchant/stores", null)))).containsExactlyInAnyOrder(s1, s2);
        assertThat(ids(ok(call(viewer, "GET", "/v1/merchant/terminals", null)))).containsExactlyInAnyOrder(t1, t2, t3);
        assertThat(ok(call(cashier1, "GET", "/v1/merchant/me", null)).path("stores")).hasSize(1);
        assertThat(rows("select count(*) from merchant_action where outcome = 'DENIED' and error_code = 'not_in_scope'"))
                .isGreaterThanOrEqualTo(3);

        // terminal deactivation: OWNER or MANAGER of the store, step-up
        assertProblem(call(cashier1, "POST", "/v1/merchant/terminals/" + t3 + "/deactivate", "{\"reason\":\"lost\"}"),
                403, "role_not_allowed");
        assertProblem(call(manager, "POST", "/v1/merchant/terminals/" + t2 + "/deactivate", "{\"reason\":\"lost\"}"),
                404, "not_found");
        assertProblem(call(manager, "POST", "/v1/merchant/terminals/" + t3 + "/deactivate", "{\"reason\":\"lost\"}", false),
                401, "step_up_required");
        assertThat(ok(call(manager, "POST", "/v1/merchant/terminals/" + t3 + "/deactivate", "{\"reason\":\"lost\"}"))
                .path("status").asString()).isEqualTo("INACTIVE");
    }

    // ------------------------------------------------------------------ sales

    @Test
    @Order(4)
    void aSaleIssuesADynamicQrAndTurnsPaid() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"store_id\":\"%s\",\"terminal_id\":\"%s\",\"amount\":\"150.00\",\"reference\":\"INV-1\"}"
                .formatted(s1, t1);
        JsonNode sale = ok(send(cashier1, "POST", "/v1/merchant/sales", body, true, key));
        sale1 = id(sale);
        assertThat(sale.path("status").asString()).isEqualTo("PENDING");
        assertThat(sale.path("qr_payload").asString()).startsWith("000201");
        assertThat(sale.path("currency").asString()).isEqualTo("KES");
        assertThat(sale.path("cashier_user_id").asString()).isEqualTo(cashier1.user());
        FakeMerchantBank.Call qr = bank.writes(MS, "/v1/merchant-qr-codes").getLast();
        assertThat(qr.body().path("merchant_id").asString()).isEqualTo(MA);
        assertThat(qr.body().path("merchant_store_id").asString()).isEqualTo(s1);
        assertThat(qr.body().path("merchant_terminal_id").asString()).isEqualTo(t1);
        assertThat(qr.body().path("qr_type").asString()).isEqualTo("DYNAMIC");
        assertThat(qr.body().path("currency").asString()).isEqualTo("KES");
        assertThat(qr.body().path("amount").asString()).isEqualTo("150.00");
        assertThat(qr.principal()).isEqualTo("merchant-user:" + cashier1.user());

        // idempotent replay: same sale, one QR
        int qrs = bank.writes(MS, "/v1/merchant-qr-codes").size();
        assertThat(id(ok(send(cashier1, "POST", "/v1/merchant/sales", body, true, key)))).isEqualTo(sale1);
        assertThat(bank.writes(MS, "/v1/merchant-qr-codes")).hasSize(qrs);
        assertProblem(send(cashier1, "POST", "/v1/merchant/sales", body.replace("150.00", "151.00"), true, key), 422,
                "idempotency_key_reused");

        // scope, role and terminal checks
        assertProblem(call(cashier1, "POST", "/v1/merchant/sales",
                "{\"store_id\":\"%s\",\"amount\":\"10.00\"}".formatted(s2)), 404, "not_found");
        assertProblem(call(cashier1, "POST", "/v1/merchant/sales",
                "{\"store_id\":\"%s\",\"terminal_id\":\"%s\",\"amount\":\"10.00\"}".formatted(s1, t2)), 404, "not_found");
        assertProblem(call(ownerA, "POST", "/v1/merchant/sales",
                "{\"store_id\":\"%s\",\"terminal_id\":\"%s\",\"amount\":\"10.00\"}".formatted(s1, t2)), 422,
                "terminal_store_mismatch");
        assertProblem(call(ownerA, "POST", "/v1/merchant/sales",
                "{\"store_id\":\"%s\",\"terminal_id\":\"%s\",\"amount\":\"10.00\"}".formatted(s1, t3)), 422,
                "terminal_not_active");
        assertProblem(call(viewer, "POST", "/v1/merchant/sales",
                "{\"store_id\":\"%s\",\"amount\":\"10.00\"}".formatted(s1)), 403, "role_not_allowed");
        assertProblem(call(cashier1, "POST", "/v1/merchant/sales",
                "{\"store_id\":\"%s\",\"amount\":\"10.001\"}".formatted(s1)), 422, "invalid_amount");

        // live status: pending until the customer pays the QR
        assertThat(ok(call(cashier1, "GET", "/v1/merchant/sales/" + sale1, null)).path("status").asString())
                .isEqualTo("PENDING");
        paidTxn = id(bank.payQr(sale.path("merchant_qr_id").asString()));
        JsonNode paid = ok(call(cashier1, "GET", "/v1/merchant/sales/" + sale1, null));
        assertThat(paid.path("status").asString()).isEqualTo("PAID");
        assertThat(paid.path("wallet_transaction_id").asString()).isEqualTo(paidTxn);
        assertThat(paid.hasNonNull("paid_at")).isTrue();
        assertThat(events("channels.merchant_sale.paid")).isEqualTo(1);
        assertProblem(call(cashier2, "GET", "/v1/merchant/sales/" + sale1, null), 404, "not_found");
        // cancelling a paid sale leaves it paid
        assertThat(ok(call(cashier1, "POST", "/v1/merchant/sales/" + sale1 + "/cancel", null)).path("status").asString())
                .isEqualTo("PAID");

        // a second sale is cancelled: its QR is deactivated
        JsonNode second = ok(call(cashier1, "POST", "/v1/merchant/sales",
                "{\"store_id\":\"%s\",\"amount\":\"20.00\"}".formatted(s1)));
        assertProblem(call(viewer, "POST", "/v1/merchant/sales/" + id(second) + "/cancel", null), 403, "role_not_allowed");
        JsonNode cancelled = ok(call(cashier1, "POST", "/v1/merchant/sales/" + id(second) + "/cancel", null));
        assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(bank.row(MS, "/v1/merchant-qr-codes", second.path("merchant_qr_id").asString()).path("is_active")
                .asBoolean()).isFalse();

        assertThat(ids(ok(call(cashier1, "GET", "/v1/merchant/sales", null)))).contains(sale1, id(second));
        assertThat(ok(call(cashier2, "GET", "/v1/merchant/sales", null)).path("data")).isEmpty();
        assertThat(ids(ok(call(cashier1, "GET", "/v1/merchant/sales?status=PAID", null)))).containsExactly(sale1);
        assertProblem(call(cashier1, "GET", "/v1/merchant/sales?store_id=" + s2, null), 404, "not_found");
    }

    @Test
    @Order(5)
    void transactionsFollowTheStoreScope() throws Exception {
        assertThat(ids(ok(call(cashier1, "GET", "/v1/merchant/transactions", null)))).containsExactly(paidTxn);
        assertThat(ok(call(cashier2, "GET", "/v1/merchant/transactions", null)).path("data")).isEmpty();
        List<String> owner = ids(ok(call(ownerA, "GET", "/v1/merchant/transactions", null)));
        assertThat(owner).contains(paidTxn, txnUnattributed).doesNotContain(txnB);
        assertThat(ids(ok(call(ownerA, "GET", "/v1/merchant/transactions?store_id=" + s1, null)))).containsExactly(paidTxn);
        assertProblem(call(cashier2, "GET", "/v1/merchant/transactions/" + paidTxn, null), 404, "not_found");
        assertProblem(call(cashier1, "GET", "/v1/merchant/transactions/" + txnUnattributed, null), 404, "not_found");
        ok(call(ownerA, "GET", "/v1/merchant/transactions/" + txnUnattributed, null));
        JsonNode refundable = ok(call(cashier1, "GET", "/v1/merchant/transactions/" + paidTxn + "/refundable", null));
        assertThat(refundable.path("refundable").asString()).isEqualTo("150.00");
    }

    // ------------------------------------------------------------------ refunds

    @Test
    @Order(6)
    void refundsNeedASecondPersonAndNeverExceedThePayment() throws Exception {
        String req = "{\"original_transaction_id\":\"%s\",\"amount\":\"%s\",\"reason\":\"returned goods\"}";
        String key = UUID.randomUUID().toString();
        JsonNode r1 = ok(send(cashier1, "POST", "/v1/merchant/refunds", req.formatted(paidTxn, "100.00"), true, key));
        assertThat(r1.path("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(r1.path("merchant_store_id").asString()).isEqualTo(s1);
        assertThat(r1.path("merchant_sale_id").asString()).isEqualTo(sale1);
        assertThat(r1.path("requested_by").asString()).isEqualTo(cashier1.user());
        assertThat(id(ok(send(cashier1, "POST", "/v1/merchant/refunds", req.formatted(paidTxn, "100.00"), true, key))))
                .isEqualTo(id(r1));                                                 // idempotent replay
        assertThat(bank.writes(MS, "/v1/merchant-refunds")).isEmpty();             // nothing downstream before approval

        // cumulative cap counts pending requests
        assertProblem(call(cashier1, "POST", "/v1/merchant/refunds", req.formatted(paidTxn, "60.00")), 422,
                "refund_exceeds_remaining");
        JsonNode cap = ok(call(cashier1, "GET", "/v1/merchant/transactions/" + paidTxn + "/refundable", null));
        assertThat(cap.path("pending").asString()).isEqualTo("100.00");
        assertThat(cap.path("refundable").asString()).isEqualTo("50.00");
        assertProblem(call(cashier2, "POST", "/v1/merchant/refunds", req.formatted(paidTxn, "1.00")), 404, "not_found");
        assertProblem(call(viewer, "POST", "/v1/merchant/refunds", req.formatted(paidTxn, "1.00")), 403,
                "role_not_allowed");

        // a cashier never approves; the store's manager does, with step-up
        assertProblem(call(cashier1, "POST", "/v1/merchant/refunds/" + id(r1) + "/approve", "{}"), 403,
                "role_not_allowed");
        assertProblem(call(cashier2, "GET", "/v1/merchant/refunds/" + id(r1), null), 404, "not_found");
        assertProblem(call(manager, "POST", "/v1/merchant/refunds/" + id(r1) + "/approve", "{}", false), 401,
                "step_up_required");
        assertThat(bank.writes(MS, "/v1/merchant-refunds")).isEmpty();
        JsonNode done = ok(call(manager, "POST", "/v1/merchant/refunds/" + id(r1) + "/approve", "{\"comment\":\"ok\"}"));
        assertThat(done.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.path("decided_by").asString()).isEqualTo(manager.user());
        assertThat(done.hasNonNull("refund_transaction_id")).isTrue();
        assertThat(done.hasNonNull("approver_auth_age_seconds")).isTrue();
        List<FakeMerchantBank.Call> ms = bank.writes(MS, "/v1/merchant-refunds");
        assertThat(ms).hasSize(2);
        assertThat(ms.get(0).principal()).isEqualTo("merchant-user:" + cashier1.user());   // created as the requester
        assertThat(ms.get(0).key()).isEqualTo("mrq-create:" + id(r1));
        assertThat(ms.get(0).body().path("amount").asString()).isEqualTo("100.00");
        assertThat(ms.get(0).body().path("merchant_id").asString()).isEqualTo(MA);
        assertThat(ms.get(1).principal()).isEqualTo("merchant-user:" + manager.user());    // approved as the approver
        assertProblem(call(manager, "POST", "/v1/merchant/refunds/" + id(r1) + "/approve", "{}"), 409,
                "invalid_transition");

        cap = ok(call(cashier1, "GET", "/v1/merchant/transactions/" + paidTxn + "/refundable", null));
        assertThat(cap.path("refunded").asString()).isEqualTo("100.00");
        assertThat(cap.path("refundable").asString()).isEqualTo("50.00");
        JsonNode r2 = ok(call(cashier1, "POST", "/v1/merchant/refunds", req.formatted(paidTxn, "50.00")));
        assertProblem(call(cashier1, "POST", "/v1/merchant/refunds", req.formatted(paidTxn, "0.01")), 422,
                "refund_exceeds_remaining");
        // rejection frees the amount again; the requester cannot reject their own request
        assertThat(call(manager, "POST", "/v1/merchant/refunds/" + id(r2) + "/reject", "{}").statusCode())
                .isIn(400, 422);                                                    // a reason is required
        JsonNode rejected = ok(call(manager, "POST", "/v1/merchant/refunds/" + id(r2) + "/reject",
                "{\"reason\":\"not returned\"}"));
        assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");
        JsonNode r3 = ok(call(cashier1, "POST", "/v1/merchant/refunds", req.formatted(paidTxn, "10.00")));
        assertThat(ok(call(cashier1, "POST", "/v1/merchant/refunds/" + id(r3) + "/cancel", null)).path("status")
                .asString()).isEqualTo("CANCELLED");

        // four-eyes: a manager's own request needs someone else
        JsonNode r4 = ok(call(manager, "POST", "/v1/merchant/refunds", req.formatted(paidTxn, "5.00")));
        assertProblem(call(manager, "POST", "/v1/merchant/refunds/" + id(r4) + "/approve", "{}"), 422,
                "four_eyes_violation");
        assertProblem(call(manager, "POST", "/v1/merchant/refunds/" + id(r4) + "/reject", "{\"reason\":\"x\"}"), 422,
                "four_eyes_violation");
        assertThat(ok(call(ownerA, "POST", "/v1/merchant/refunds/" + id(r4) + "/approve", "{}")).path("status").asString())
                .isEqualTo("COMPLETED");
        assertThat(ok(call(cashier1, "GET", "/v1/merchant/transactions/" + paidTxn + "/refundable", null))
                .path("refundable").asString()).isEqualTo("45.00");
        assertThat(ids(ok(call(cashier1, "GET", "/v1/merchant/refunds", null)))).hasSize(4);
        assertThat(ok(call(cashier2, "GET", "/v1/merchant/refunds", null)).path("data")).isEmpty();
        assertThat(ids(ok(call(ownerA, "GET", "/v1/merchant/refunds?status=COMPLETED", null)))).hasSize(2);
        // a refund transaction is not refundable, and only payments are
        assertProblem(call(ownerA, "POST", "/v1/merchant/refunds",
                req.formatted(done.path("refund_transaction_id").asString(), "1.00")), 422, "not_refundable");
        assertThat(events("channels.merchant_refund_request.completed")).isEqualTo(2);
    }

    @Test
    @Order(7)
    void aDownstreamRefusalMarksTheRefundFailedAndAnOutageKeepsItPending() throws Exception {
        String pay = id(bank.payQr(ok(call(cashier1, "POST", "/v1/merchant/sales",
                "{\"store_id\":\"%s\",\"amount\":\"30.00\"}".formatted(s1))).path("merchant_qr_id").asString()));
        JsonNode r = ok(call(cashier1, "POST", "/v1/merchant/refunds",
                "{\"original_transaction_id\":\"%s\",\"amount\":\"30.00\",\"reason\":\"x\"}".formatted(pay)));
        bank.failOnce.put("POST " + MS + " /v1/merchant-refunds",
                new io.meridian.common.error.ApiException(503, "upstream_error", "down"));
        assertThat(call(manager, "POST", "/v1/merchant/refunds/" + id(r) + "/approve", "{}").statusCode()).isEqualTo(503);
        assertThat(ok(call(manager, "GET", "/v1/merchant/refunds/" + id(r), null)).path("status").asString())
                .isEqualTo("PENDING_APPROVAL");
        assertThat(ok(call(manager, "POST", "/v1/merchant/refunds/" + id(r) + "/approve", "{}")).path("status").asString())
                .isEqualTo("COMPLETED");

        JsonNode r2pay = bank.payment(MA, "12.00", null);
        // an unattributed payment: only all-stores users may refund it
        assertProblem(call(cashier1, "POST", "/v1/merchant/refunds",
                "{\"original_transaction_id\":\"%s\",\"amount\":\"12.00\",\"reason\":\"x\"}".formatted(id(r2pay))), 404,
                "not_found");
        JsonNode r2 = ok(call(ownerA, "POST", "/v1/merchant/refunds",
                "{\"original_transaction_id\":\"%s\",\"amount\":\"12.00\",\"reason\":\"x\"}".formatted(id(r2pay))));
        bank.failOnce.put("POST " + MS + " /v1/merchant-refunds/",
                io.meridian.common.error.ApiException.unprocessable("insufficient_funds", "wallet balance too low"));
        assertProblem(call(manager, "POST", "/v1/merchant/refunds/" + id(r2) + "/approve", "{}"), 404, "not_found");
        JsonNode failed = ok(call(ownerA, "GET",
                "/v1/merchant/refunds/" + id(r2), null));
        assertThat(failed.path("status").asString()).isEqualTo("PENDING_APPROVAL");
        // the manager of s1 does not see an unattributed refund; another all-stores approver is needed
        String manager2 = id(ok(call(ownerA, "POST", "/v1/merchant/staff",
                "{\"display_name\":\"Max\",\"email\":\"max@a.test\",\"role\":\"MANAGER\",\"all_stores\":true}")));
        JsonNode f = ok(call(new Who(MA, manager2), "POST", "/v1/merchant/refunds/" + id(r2) + "/approve", "{}"));
        assertThat(f.path("status").asString()).isEqualTo("FAILED");
        assertThat(f.path("failure_reason").asString()).startsWith("insufficient_funds");
    }

    // ------------------------------------------------------------------ payout account

    @Test
    @Order(8)
    void payoutAccountChangesNeedStepUpAndAnotherApprover() throws Exception {
        JsonNode current = ok(call(manager, "GET", "/v1/merchant/payout-account", null));
        assertThat(current.path("settlement_account").path("account_number_masked").asString()).isEqualTo("****3344");
        assertThat(current.path("settlement_account").has("account_number")).isFalse();
        assertProblem(call(cashier1, "GET", "/v1/merchant/payout-account", null), 403, "role_not_allowed");
        assertThat(ids(ok(call(ownerA, "GET", "/v1/merchant/payout-account/eligible-accounts", null))))
                .containsExactlyInAnyOrder(accA1, accA2);
        assertProblem(call(manager, "GET", "/v1/merchant/payout-account/eligible-accounts", null), 403, "role_not_allowed");

        String body = "{\"settlement_account_id\":\"%s\",\"reason\":\"new bank\"}";
        assertProblem(call(manager, "POST", "/v1/merchant/payout-changes", body.formatted(accA2)), 403, "role_not_allowed");
        assertProblem(call(ownerA, "POST", "/v1/merchant/payout-changes", body.formatted(accB)), 404, "not_found");
        assertProblem(call(ownerA, "POST", "/v1/merchant/payout-changes", body.formatted(accAusd)), 422,
                "currency_mismatch");
        assertProblem(call(ownerA, "POST", "/v1/merchant/payout-changes", body.formatted(accA1)), 422, "no_change");
        assertProblem(call(ownerA, "POST", "/v1/merchant/payout-changes", body.formatted(accA2), false), 401,
                "step_up_required");
        JsonNode change = ok(call(ownerA, "POST", "/v1/merchant/payout-changes", body.formatted(accA2)));
        assertThat(change.path("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(change.path("account_number_masked").asString()).isEqualTo("****5566");
        assertThat(change.path("previous_settlement_account_id").asString()).isEqualTo(accA1);
        assertProblem(call(ownerA, "POST", "/v1/merchant/payout-changes", body.formatted(accA2)), 409, "change_pending");
        assertThat(ok(call(manager, "GET", "/v1/merchant/payout-account", null)).path("pending_change").path("id")
                .asString()).isEqualTo(id(change));

        assertProblem(call(ownerA, "POST", "/v1/merchant/payout-changes/" + id(change) + "/approve", "{}"), 422,
                "four_eyes_violation");
        assertProblem(call(cashier1, "POST", "/v1/merchant/payout-changes/" + id(change) + "/approve", "{}"), 403,
                "role_not_allowed");
        assertProblem(call(manager, "POST", "/v1/merchant/payout-changes/" + id(change) + "/approve", "{}", false), 401,
                "step_up_required");
        assertThat(bank.writes(MS, "/v1/merchants/")).isEmpty();
        JsonNode applied = ok(call(manager, "POST", "/v1/merchant/payout-changes/" + id(change) + "/approve",
                "{\"comment\":\"verified by phone\"}"));
        assertThat(applied.path("status").asString()).isEqualTo("APPLIED");
        FakeMerchantBank.Call patch = bank.writes(MS, "/v1/merchants/").getLast();
        assertThat(patch.method()).isEqualTo("PATCH");
        assertThat(patch.path()).isEqualTo("/v1/merchants/" + MA);
        assertThat(patch.body().path("settlement_account_id").asString()).isEqualTo(accA2);
        assertThat(patch.body().size()).isEqualTo(1);                                // nothing else is changed
        assertThat(patch.key()).isEqualTo("1");                                     // If-Match
        assertThat(patch.principal()).isEqualTo("merchant-user:" + manager.user());
        assertThat(bank.row(MS, "/v1/merchants", MA).path("settlement_account_id").asString()).isEqualTo(accA2);
        assertThat(ok(call(manager, "GET", "/v1/merchant/payout-account", null)).path("pending_change").isNull()).isTrue();

        // a second change: rejected by another user, withdrawn by its requester
        JsonNode back = ok(call(ownerA, "POST", "/v1/merchant/payout-changes", body.formatted(accA1)));
        assertProblem(call(ownerA, "POST", "/v1/merchant/payout-changes/" + id(back) + "/reject", "{\"reason\":\"x\"}"),
                422, "four_eyes_violation");
        assertProblem(call(manager, "POST", "/v1/merchant/payout-changes/" + id(back) + "/cancel", null), 403,
                "not_requester");
        assertThat(ok(call(ownerA, "POST", "/v1/merchant/payout-changes/" + id(back) + "/cancel", null)).path("status")
                .asString()).isEqualTo("CANCELLED");
        assertThat(ids(ok(call(manager, "GET", "/v1/merchant/payout-changes", null)))).hasSize(2);
        assertThat(events("channels.merchant_change_request.applied")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ settlements, fees, links, integrations

    @Test
    @Order(9)
    void settlementsReportsAndFeeStatement() throws Exception {
        assertThat(ids(ok(call(viewer, "GET", "/v1/merchant/settlements", null)))).hasSize(3).contains(setA)
                .doesNotContain(setB);
        assertProblem(call(cashier1, "GET", "/v1/merchant/settlements", null), 403, "role_not_allowed");
        assertProblem(call(cashier1, "GET", "/v1/merchant/fees", null), 403, "role_not_allowed");
        HttpResponse<String> report = call(viewer, "GET", "/v1/merchant/settlements/" + setA + "/report", null);
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(report.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(report.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(report.body()).contains(setA);

        JsonNode fees = ok(call(manager, "GET", "/v1/merchant/fees?from=2026-08-01&to=2026-10-31", null));
        assertThat(fees.path("fee_plan").path("code").asString()).isEqualTo("STD");
        assertThat(fees.path("settlements")).hasSize(2);                            // REJECTED left out
        assertThat(fees.path("totals").path("gross_amount").asString()).isEqualTo("1200.00");
        assertThat(fees.path("totals").path("mdr_amount").asString()).isEqualTo("12.00");
        assertThat(fees.path("totals").path("net_amount").asString()).isEqualTo("1168.00");
        assertThat(fees.path("totals").path("effective_mdr_rate").asString()).isEqualTo("0.0100");
        assertThat(ok(call(manager, "GET", "/v1/merchant/fees?from=2026-10-01&to=2026-10-31", null)).path("settlements"))
                .hasSize(1);
        HttpResponse<String> csv = call(viewer, "GET", "/v1/merchant/fees/statement?from=2026-08-01&to=2026-10-31", null);
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.body()).startsWith("merchant_code,settlement_id").contains("M0001,TOTAL,").contains("1200.00");
    }

    @Test
    @Order(10)
    void paymentLinksAreBuiltServerSide() throws Exception {
        assertProblem(call(cashier1, "POST", "/v1/merchant/payment-links", "{\"description\":\"Deposit\"}"), 403,
                "role_not_allowed");
        JsonNode link = ok(call(manager, "POST", "/v1/merchant/payment-links",
                "{\"description\":\"Deposit\",\"amount\":\"500.00\",\"max_uses\":1}"));
        FakeMerchantBank.Call c = bank.writes(MS, "/v1/merchant-payment-links").getLast();
        assertThat(c.body().path("merchant_id").asString()).isEqualTo(MA);
        assertThat(c.body().path("currency").asString()).isEqualTo("KES");
        assertThat(ids(ok(call(viewer, "GET", "/v1/merchant/payment-links", null)))).containsExactly(id(link));
        assertProblem(call(cashier1, "GET", "/v1/merchant/payment-links", null), 403, "role_not_allowed");
        assertProblem(call(viewer, "POST", "/v1/merchant/payment-links/" + id(link) + "/cancel", null), 403,
                "role_not_allowed");
        assertThat(ok(call(manager, "POST", "/v1/merchant/payment-links/" + id(link) + "/cancel", null))
                .path("is_active").asBoolean()).isFalse();
    }

    @Test
    @Order(11)
    void integrationsAreTheOwnersAndFilteredToTheMerchant() throws Exception {
        String client = "{\"name\":\"Shop backend\",\"environment\":\"SANDBOX\"}";
        assertProblem(call(manager, "POST", "/v1/merchant/api-clients", client), 403, "role_not_allowed");
        assertProblem(call(ownerA, "POST", "/v1/merchant/api-clients", client, false), 401, "step_up_required");
        JsonNode c = ok(call(ownerA, "POST", "/v1/merchant/api-clients", client));
        assertThat(bank.writes("identity-service", "/v1/api-clients").getLast().body().path("allowed_scopes").toString())
                .isEqualTo("[\"channels:merchant\"]");
        JsonNode key = ok(call(ownerA, "POST", "/v1/merchant/api-clients/" + id(c) + "/keys", "{\"name\":\"k1\"}"));
        assertThat(key.path("api_key").asString()).isNotBlank();
        assertThat(ids(ok(call(ownerA, "GET", "/v1/merchant/api-clients", null)))).containsExactly(id(c));
        assertProblem(call(ownerA, "POST", "/v1/merchant/webhook-endpoints",
                "{\"url\":\"http://shop.example/hook\",\"api_client_id\":\"%s\"}".formatted(id(c))), 422, "https_required");
        JsonNode ep = ok(call(ownerA, "POST", "/v1/merchant/webhook-endpoints",
                "{\"url\":\"https://shop.example/hook\",\"api_client_id\":\"%s\"}".formatted(id(c))));
        ok(call(ownerA, "POST", "/v1/merchant/webhook-endpoints/" + id(ep) + "/subscriptions",
                "{\"event_type\":\"wallets.merchant_qr.paid\"}"));
        assertThat(bank.writes("webhook-service", "/v1/webhook-subscriptions").getLast().body().path("filter_expression")
                .asString()).isEqualTo("merchant_id == \"" + MA + "\"");
        assertProblem(call(ownerA, "POST", "/v1/merchant/webhook-endpoints/" + id(ep) + "/subscriptions",
                "{\"event_type\":\"payments.payment.*\"}"), 422, "event_type_not_allowed");
    }

    // ------------------------------------------------------------------ isolation

    @Test
    @Order(12)
    void everyObjectOfAnotherMerchantIs404AndNothingIsWritten() throws Exception {
        // B's own objects, created through the app by B's owner
        String saleB = id(ok(call(ownerB, "POST", "/v1/merchant/sales", "{\"store_id\":\"%s\",\"amount\":\"9.00\"}"
                .formatted(sb))));
        String refundB = id(ok(call(ownerB, "POST", "/v1/merchant/refunds",
                "{\"original_transaction_id\":\"%s\",\"amount\":\"1.00\",\"reason\":\"x\"}".formatted(txnB))));
        String changeB = id(ok(call(ownerB, "POST", "/v1/merchant/payout-changes",
                "{\"settlement_account_id\":\"%s\"}".formatted(accB2))));
        String userB = id(ok(call(ownerB, "POST", "/v1/merchant/staff",
                "{\"display_name\":\"B cashier\",\"email\":\"bc@b.test\",\"role\":\"CASHIER\"}")));
        String clientB = id(ok(call(ownerB, "POST", "/v1/merchant/api-clients", "{\"name\":\"B app\"}")));
        String endpointB = id(ok(call(ownerB, "POST", "/v1/merchant/webhook-endpoints",
                "{\"url\":\"https://b.example/hook\",\"api_client_id\":\"%s\"}".formatted(clientB))));

        long writesBefore = bank.writes().size();
        long deniedBefore = rows("select count(*) from merchant_action where outcome = 'DENIED' and error_code = 'not_owned'");
        List<String[]> probes = new ArrayList<>();
        probes.add(new String[] {"GET", "/v1/merchant/stores/" + sb, null});
        probes.add(new String[] {"GET", "/v1/merchant/terminals/" + tb, null});
        probes.add(new String[] {"GET", "/v1/merchant/terminals?store_id=" + sb, null});
        probes.add(new String[] {"POST", "/v1/merchant/terminals/" + tb + "/deactivate", "{\"reason\":\"x\"}"});
        probes.add(new String[] {"POST", "/v1/merchant/sales", "{\"store_id\":\"" + sb + "\",\"amount\":\"1.00\"}"});
        probes.add(new String[] {"GET", "/v1/merchant/sales/" + saleB, null});
        probes.add(new String[] {"POST", "/v1/merchant/sales/" + saleB + "/cancel", null});
        probes.add(new String[] {"GET", "/v1/merchant/sales?store_id=" + sb, null});
        probes.add(new String[] {"GET", "/v1/merchant/transactions/" + txnB, null});
        probes.add(new String[] {"GET", "/v1/merchant/transactions/" + txnB + "/refundable", null});
        probes.add(new String[] {"GET", "/v1/merchant/transactions?store_id=" + sb, null});
        probes.add(new String[] {"POST", "/v1/merchant/refunds",
                "{\"original_transaction_id\":\"" + txnB + "\",\"amount\":\"1.00\",\"reason\":\"x\"}"});
        probes.add(new String[] {"GET", "/v1/merchant/refunds/" + refundB, null});
        probes.add(new String[] {"POST", "/v1/merchant/refunds/" + refundB + "/approve", "{}"});
        probes.add(new String[] {"POST", "/v1/merchant/refunds/" + refundB + "/reject", "{\"reason\":\"x\"}"});
        probes.add(new String[] {"POST", "/v1/merchant/refunds/" + refundB + "/cancel", null});
        probes.add(new String[] {"GET", "/v1/merchant/payment-links/" + linkB, null});
        probes.add(new String[] {"POST", "/v1/merchant/payment-links/" + linkB + "/cancel", null});
        probes.add(new String[] {"GET", "/v1/merchant/settlements/" + setB, null});
        probes.add(new String[] {"GET", "/v1/merchant/settlements/" + setB + "/report", null});
        probes.add(new String[] {"GET", "/v1/merchant/payout-changes/" + changeB, null});
        probes.add(new String[] {"POST", "/v1/merchant/payout-changes/" + changeB + "/approve", "{}"});
        probes.add(new String[] {"POST", "/v1/merchant/payout-changes/" + changeB + "/reject", "{\"reason\":\"x\"}"});
        probes.add(new String[] {"POST", "/v1/merchant/payout-changes/" + changeB + "/cancel", null});
        probes.add(new String[] {"POST", "/v1/merchant/payout-changes", "{\"settlement_account_id\":\"" + accB2 + "\"}"});
        probes.add(new String[] {"GET", "/v1/merchant/staff/" + userB, null});
        probes.add(new String[] {"PATCH", "/v1/merchant/staff/" + userB, "{\"role\":\"OWNER\"}"});
        probes.add(new String[] {"GET", "/v1/merchant/staff/" + userB + "/stores", null});
        probes.add(new String[] {"PUT", "/v1/merchant/staff/" + userB + "/stores", "{\"store_ids\":[]}"});
        probes.add(new String[] {"POST", "/v1/merchant/staff/" + userB + "/suspend", "{}"});
        probes.add(new String[] {"PUT", "/v1/merchant/staff/" + cashier1.user() + "/stores",
                "{\"store_ids\":[\"" + sb + "\"]}"});
        probes.add(new String[] {"GET", "/v1/merchant/api-clients/" + clientB, null});
        probes.add(new String[] {"GET", "/v1/merchant/api-clients/" + clientB + "/keys", null});
        probes.add(new String[] {"POST", "/v1/merchant/api-clients/" + clientB + "/keys", "{\"name\":\"x\"}"});
        probes.add(new String[] {"POST", "/v1/merchant/api-clients/" + clientB + "/suspend", "{\"reason\":\"x\"}"});
        probes.add(new String[] {"POST", "/v1/merchant/webhook-endpoints",
                "{\"url\":\"https://a.example/x\",\"api_client_id\":\"" + clientB + "\"}"});
        probes.add(new String[] {"GET", "/v1/merchant/webhook-endpoints/" + endpointB, null});
        probes.add(new String[] {"GET", "/v1/merchant/webhook-endpoints/" + endpointB + "/subscriptions", null});
        probes.add(new String[] {"POST", "/v1/merchant/webhook-endpoints/" + endpointB + "/subscriptions",
                "{\"event_type\":\"wallets.merchant_qr.paid\"}"});
        probes.add(new String[] {"DELETE", "/v1/merchant/webhook-endpoints/" + endpointB, null});
        for (String[] p : probes) {
            HttpResponse<String> r = call(ownerA, p[0], p[1], p[2]);
            assertThat(r.statusCode()).as(p[0] + " " + p[1] + " -> " + r.body()).isEqualTo(404);
            assertThat(json.readTree(r.body()).path("code").asString()).as(p[1]).isEqualTo("not_found");
        }
        assertThat(bank.writes()).as("nothing reached a domain service").hasSize((int) writesBefore);
        assertThat(rows("select count(*) from merchant_action where outcome = 'DENIED' and error_code = 'not_owned'")
                - deniedBefore).isGreaterThanOrEqualTo(probes.size() - 2);
        assertThat(events("channels.merchant_action.denied")).isGreaterThan(30);
        // lists never show the other merchant's rows
        assertThat(ids(ok(call(ownerA, "GET", "/v1/merchant/sales", null)))).doesNotContain(saleB);
        assertThat(ids(ok(call(ownerA, "GET", "/v1/merchant/refunds", null)))).doesNotContain(refundB);
        assertThat(ids(ok(call(ownerA, "GET", "/v1/merchant/payout-changes", null)))).doesNotContain(changeB);
        assertThat(ids(ok(call(ownerA, "GET", "/v1/merchant/staff", null)))).doesNotContain(userB);
        assertThat(ids(ok(call(ownerA, "GET", "/v1/merchant/payment-links", null)))).doesNotContain(linkB);
        assertThat(ids(ok(call(ownerA, "GET", "/v1/merchant/webhook-endpoints", null)))).doesNotContain(endpointB);
    }

    @Test
    @Order(13)
    void storeReassignmentAndSuspensionTakeEffectImmediately() throws Exception {
        assertProblem(call(cashier2, "GET", "/v1/merchant/sales/" + sale1, null), 404, "not_found");
        assertProblem(call(ownerA, "PUT", "/v1/merchant/staff/" + cashier2.user() + "/stores",
                "{\"store_ids\":[\"%s\"]}".formatted(s1), false), 401, "step_up_required");
        ok(call(ownerA, "PUT", "/v1/merchant/staff/" + cashier2.user() + "/stores", "{\"store_ids\":[\"%s\"]}".formatted(s1)));
        ok(call(cashier2, "GET", "/v1/merchant/sales/" + sale1, null));
        assertProblem(call(cashier2, "GET", "/v1/merchant/stores/" + s2, null), 404, "not_found");
        ok(call(ownerA, "POST", "/v1/merchant/staff/" + cashier2.user() + "/suspend", "{\"reason\":\"left\"}"));
        assertProblem(call(cashier2, "GET", "/v1/merchant/me", null), 403, "merchant_user_inactive");
        ok(call(ownerA, "POST", "/v1/merchant/staff/" + cashier2.user() + "/reactivate", null));
        ok(call(cashier2, "GET", "/v1/merchant/me", null));
        ok(call(ownerA, "POST", "/v1/merchant/staff/" + cashier2.user() + "/remove", "{}"));
        assertProblem(call(cashier2, "GET", "/v1/merchant/me", null), 403, "merchant_user_inactive");
        assertThat(rows("select count(*) from merchant_user_store where merchant_user_id = '" + cashier2.user() + "'"))
                .isZero();
    }

    @Test
    @Order(14)
    void theActivityLogShowsOwnersEverythingAndOthersTheirOwn() throws Exception {
        JsonNode all = ok(call(ownerA, "GET", "/v1/merchant/activity?limit=200", null));
        JsonNode mine = ok(call(cashier1, "GET", "/v1/merchant/activity?limit=200", null));
        assertThat(all.path("data").size()).isGreaterThan(mine.path("data").size());
        for (JsonNode a : mine.path("data")) {
            assertThat(a.path("merchant_user_id").asString()).isEqualTo(cashier1.user());
        }
        for (JsonNode a : all.path("data")) {
            assertThat(a.path("merchant_id").asString()).isEqualTo(MA);
        }
        assertThat(rows("select count(*) from merchant_action where outcome = 'STEP_UP_REQUIRED'")).isGreaterThanOrEqualTo(5);
    }

    // ------------------------------------------------------------------ helpers

    private String account(String customer, String number, String currency) {
        return id(bank.add(DEP, "/v1/deposit-accounts", """
                {"customer_id":"%s","account_number":"%s","currency":"%s","status":"ACTIVE"}"""
                .formatted(customer, number, currency)));
    }

    private void merchant(String id, String code, String name, String customer, String account, String plan,
                          String status) {
        bank.add(MS, "/v1/merchants", """
                {"id":"%s","merchant_code":"%s","trading_name":"%s","legal_name":"%s Ltd","merchant_type":"COMPANY",
                 "mcc":"5411","country":"KE","currency":"KES","city":"Nairobi","status":"%s","customer_id":"%s",
                 "settlement_account_id":%s,"merchant_fee_plan_id":"%s","settlement_frequency":"DAILY"}"""
                .formatted(id, code, name, name, status, customer, account == null ? "null" : "\"" + account + "\"", plan));
    }

    private String store(String merchant, String code) {
        return id(bank.add(MS, "/v1/merchant-stores", """
                {"merchant_id":"%s","store_code":"%s","name":"Store %s","country":"KE","status":"ACTIVE"}"""
                .formatted(merchant, code, code)));
    }

    private String terminal(String merchant, String store, String tid) {
        return id(bank.add(MS, "/v1/merchant-terminals", """
                {"merchant_id":"%s","merchant_store_id":"%s","terminal_id":"%s","terminal_type":"SOFT_POS","status":"ACTIVE"}"""
                .formatted(merchant, store, tid)));
    }

    private String settlement(String merchant, String start, String end, String status, String gross, String mdr,
                              String refunds, String net, int count) {
        return id(bank.add(MS, "/v1/merchant-settlements", """
                {"merchant_id":"%s","period_start":"%s","period_end":"%s","currency":"KES","status":"%s",
                 "gross_amount":"%s","mdr_amount":"%s","refund_amount":"%s","net_amount":"%s","payment_count":%d}"""
                .formatted(merchant, start, end, status, gross, mdr, refunds, net, count)));
    }

    private HttpResponse<String> call(Who who, String method, String path, String body) throws Exception {
        return send(who, method, path, body, true, UUID.randomUUID().toString());
    }

    private HttpResponse<String> call(Who who, String method, String path, String body, boolean fresh) throws Exception {
        return send(who, method, path, body, fresh, UUID.randomUUID().toString());
    }

    private HttpResponse<String> send(Who who, String method, String path, String body, boolean fresh, String key)
            throws Exception {
        long authTime = Instant.now().minusSeconds(fresh ? 30 : 3600).getEpochSecond();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", TENANT)
                .header("X-Merchant-Id", who.merchant())
                .header("X-Merchant-User-Id", who.user())
                .header("X-Auth-Time", String.valueOf(authTime));
        if (!method.equals("GET")) {
            b.header("Idempotency-Key", key);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isBetween(200, 299);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private void assertProblem(HttpResponse<String> r, int status, String code) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        if (code != null) {
            assertThat(json.readTree(r.body()).path("code").asString()).as(r.body()).isEqualTo(code);
        }
    }

    private static String id(JsonNode n) {
        return n.path("id").asString();
    }

    private static List<String> ids(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.path("data").forEach(r -> out.add(r.path("id").asString()));
        return out;
    }

    /** Queries run as the test tenant (row-level security). */
    private long rows(String sql) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", () -> jdbc.sql(sql).query(Long.class).single());
    }

    private long events(String type) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test",
                () -> jdbc.sql("select count(*) from outbox where event_type = ?").param(type).query(Long.class).single());
    }
}
