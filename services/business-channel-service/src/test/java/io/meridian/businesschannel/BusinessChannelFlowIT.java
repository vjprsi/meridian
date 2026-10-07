package io.meridian.businesschannel;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
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
 * business-channel-service over HTTP (dev security mode: X-Tenant-Id, X-Business-Customer-Id, X-Business-User-Id,
 * X-Auth-Time) against PostgreSQL 18, with the domain services replaced by a stateful {@link FakeBank} behind the
 * mocked {@link ServiceClient}. Two businesses of one tenant, A and B, each own one object of every family; A has an
 * admin, a maker, three approvers and a viewer with per-account entitlements.
 *
 * <p>Proves: bootstrap of the first admin and refusal of unknown users; user, entitlement and matrix administration
 * (ADMIN only, step-up); the approval matrix (amount bands, N approvers, any-order and sequential, self-approval
 * forbidden, designated approvers); release to payment-service only after the final approval, as the maker, with a
 * deterministic key (idempotent retry after a failure); business approvals relayed to payment-service's own levels;
 * bulk / payroll CSV validation; FX quote binding; entitlement, role, cap and daily-limit denials; 404 for every family
 * of the other business with nothing written downstream; audit and denial events.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev", "meridian.business.step-up-max-age=PT5M",
        "meridian.business-date.source=clock"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BusinessChannelFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000b501";
    static final String BIZ_A = "0199a000-0000-7000-8000-0000000b00a1";
    static final String BIZ_B = "0199a000-0000-7000-8000-0000000b00b1";
    static final String PERSON = "0199a000-0000-7000-8000-0000000c00c1";
    static final String ADMIN_A = "0199a000-0000-7000-8000-0000000ba001";
    static final String ADMIN_B = "0199a000-0000-7000-8000-0000000bb001";

    static final String DEP = "deposit-service";
    static final String PAY = "payment-service";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    FakeBank bank;
    final HttpClient http = HttpClient.newHttpClient();

    record Who(String business, String user) {}

    Who adminA = new Who(BIZ_A, ADMIN_A);
    Who adminB = new Who(BIZ_B, ADMIN_B);
    Who maker;
    Who maker2;
    Who approver1;
    Who approver2;
    Who approver3;
    Who viewer;

    /** A's accounts: a1 (KES, everything), a2 (KES, view only for the maker), usd (USD, nobody but the admin). */
    String a1;
    String a2;
    String usd;
    String b1;
    String payeeA;
    String payeeAusd;
    String payeeB;
    String mandateB;
    String vaB;
    String linkB;
    String cardB;
    String loanA;
    String loanB;
    String lineA;
    String lineB;
    String statementB;
    String quoteB;
    String ruleB;
    String productId;

    @BeforeAll
    void seed() {
        bank = new FakeBank(json);
        bank.add("customer-service", "/v1/customers", """
                {"id":"%s","customer_type":"COMPANY","status":"ACTIVE","display_name":"Acme Ltd","legal_name":"Acme Limited"}"""
                .formatted(BIZ_A));
        bank.add("customer-service", "/v1/customers", """
                {"id":"%s","customer_type":"COMPANY","status":"ACTIVE","display_name":"Beta Ltd","legal_name":"Beta Limited"}"""
                .formatted(BIZ_B));
        bank.add("customer-service", "/v1/customers", """
                {"id":"%s","customer_type":"INDIVIDUAL","status":"ACTIVE","display_name":"Jane"}""".formatted(PERSON));
        a1 = account(BIZ_A, "100001", "KES");
        a2 = account(BIZ_A, "100002", "KES");
        usd = account(BIZ_A, "100003", "USD");
        b1 = account(BIZ_B, "200001", "KES");
        payeeA = id(bank.add(PAY, "/v1/payees", """
                {"customer_id":"%s","name":"Supplier One","account_id_type":"ACCOUNT_NUMBER","account_number":"5551",
                 "country":"KE","currency":"KES","status":"ACTIVE"}""".formatted(BIZ_A)));
        payeeAusd = id(bank.add(PAY, "/v1/payees", """
                {"customer_id":"%s","name":"US Vendor","account_id_type":"ACCOUNT_NUMBER","account_number":"7771",
                 "country":"US","currency":"USD","status":"ACTIVE"}""".formatted(BIZ_A)));
        payeeB = id(bank.add(PAY, "/v1/payees", """
                {"customer_id":"%s","name":"B Supplier","account_id_type":"ACCOUNT_NUMBER","account_number":"6661",
                 "country":"KE","currency":"KES","status":"ACTIVE"}""".formatted(BIZ_B)));
        mandateB = id(bank.add(PAY, "/v1/payment-mandates", """
                {"customer_id":"%s","direction":"OUTBOUND","creditor_account_id":"%s","currency":"KES","status":"ACTIVE",
                 "mandate_reference":"M-B"}""".formatted(BIZ_B, b1)));
        bank.add(PAY, "/v1/direct-debit-collections", "{\"mandate_id\":\"%s\",\"amount\":\"5.00\"}".formatted(mandateB));
        bank.rows(PAY, "/v1/bulk-payments");
        bank.rows(PAY, "/v1/payments");
        vaB = id(bank.add(DEP, "/v1/virtual-accounts", """
                {"master_account_id":"%s","currency":"KES","purpose":"COLLECTION","status":"ACTIVE"}""".formatted(b1)));
        linkB = id(bank.add(PAY, "/v1/payment-links", """
                {"customer_id":"%s","credit_account_id":"%s","title":"B link","currency":"KES","status":"ACTIVE"}"""
                .formatted(BIZ_B, b1)));
        productId = id(bank.add("card-service", "/v1/card-products", """
                {"card_program_id":"%s","code":"BIZ","name":"Business debit","status":"ACTIVE"}"""
                .formatted(UUID.randomUUID())));
        cardB = id(bank.add("card-service", "/v1/cards", """
                {"customer_id":"%s","business_customer_id":"%s","deposit_account_id":"%s","status":"ACTIVE","last4":"9999"}"""
                .formatted(BIZ_B, BIZ_B, b1)));
        bank.rows("card-service", "/v1/card-controls");
        bank.rows("card-service", "/v1/card-spend-limits");
        loanA = id(bank.add("loan-service", "/v1/loans", "{\"customer_id\":\"%s\",\"status\":\"ACTIVE\",\"currency\":\"KES\"}"
                .formatted(BIZ_A)));
        loanB = id(bank.add("loan-service", "/v1/loans", "{\"customer_id\":\"%s\",\"status\":\"ACTIVE\",\"currency\":\"KES\"}"
                .formatted(BIZ_B)));
        lineA = id(bank.add("loan-service", "/v1/credit-lines", """
                {"customer_id":"%s","status":"ACTIVE","currency":"KES","credit_limit":"50000.00"}""".formatted(BIZ_A)));
        lineB = id(bank.add("loan-service", "/v1/credit-lines", """
                {"customer_id":"%s","status":"ACTIVE","currency":"KES","credit_limit":"50000.00"}""".formatted(BIZ_B)));
        bank.rows("loan-service", "/v1/credit-line-drawdowns");
        statementB = id(bank.add("statement-service", "/v1/statements",
                "{\"deposit_account_id\":\"%s\",\"status\":\"READY\",\"format\":\"PDF\"}".formatted(b1)));
        bank.rows("fx-service", "/v1/fx/quotes");
        bank.rows("fx-service", "/v1/fx/deals");
        bank.rows("identity-service", "/v1/api-clients");
        bank.rows("webhook-service", "/v1/webhook-endpoints");
        bank.rows("webhook-service", "/v1/webhook-subscriptions");
        bank.rows("webhook-service", "/v1/webhook-deliveries");
        bank.rows(DEP, "/v1/deposit-accounts/" + a1 + "/transactions");
        bank.add(DEP, "/v1/deposit-accounts/" + a1 + "/transactions", """
                {"journal_entry_id":"%s","account_version":1,"direction":"CREDIT","amount":"500.00","currency":"KES",
                 "effective_at":"2026-10-01T10:00:00Z","value_date":"2026-10-01","balance_after":"1500.00",
                 "reference":"INV-1","description":"Customer payment"}""".formatted(UUID.randomUUID()));
        bank.add(DEP, "/v1/deposit-accounts/" + a1 + "/transactions", """
                {"journal_entry_id":"%s","account_version":2,"direction":"DEBIT","amount":"300.00","currency":"KES",
                 "effective_at":"2026-10-02T10:00:00Z","value_date":"2026-10-02","balance_after":"1200.00",
                 "reference":"=cmd","description":"Supplier <one> & co"}""".formatted(UUID.randomUUID()));
    }

    @BeforeEach
    void wire() {
        bank.wire(serviceClient);
    }

    private String account(String business, String number, String ccy) {
        return id(bank.add(DEP, "/v1/deposit-accounts", """
                {"customer_id":"%s","account_number":"%s","currency":"%s","status":"ACTIVE","ledger_account_id":"%s"}"""
                .formatted(business, number, ccy, UUID.randomUUID())));
    }

    // ------------------------------------------------------------------ identity

    @Test
    @Order(1)
    void theFirstUserOfABusinessBecomesItsAdminAndUnknownUsersAreRefused() throws Exception {
        JsonNode me = ok(call(adminA, "GET", "/v1/business/me", null));
        assertThat(me.path("user").path("role").asString()).isEqualTo("ADMIN");
        assertThat(me.path("user").path("id").asString()).isEqualTo(ADMIN_A);
        assertThat(me.path("business").path("legal_name").asString()).isEqualTo("Acme Limited");
        assertThat(ok(call(adminB, "GET", "/v1/business/me", null)).path("user").path("role").asString()).isEqualTo("ADMIN");

        // a second, unregistered user of A is not bootstrapped
        assertProblem(call(new Who(BIZ_A, UUID.randomUUID().toString()), "GET", "/v1/business/me", null), 403,
                "business_user_unknown");
        // A's admin presenting B as the business (claims that do not belong together)
        assertProblem(call(new Who(BIZ_B, ADMIN_A), "GET", "/v1/business/me", null), 403, "business_user_unknown");
        // an individual customer is no business
        assertProblem(call(new Who(PERSON, UUID.randomUUID().toString()), "GET", "/v1/business/me", null), 403,
                "business_not_eligible");
        // no business identity at all (e.g. a customer header)
        HttpResponse<String> anon = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/business/me"))
                .header("X-Tenant-Id", TENANT).header("X-Customer-Id", BIZ_A).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertProblem(anon, 403, "business_identity_required");
        assertThat(rows("select count(*) from business_user where business_customer_id = '" + BIZ_A + "'")).isEqualTo(1);
    }

    @Test
    @Order(2)
    void adminsManageUsersAndEntitlementsWithStepUp() throws Exception {
        String body = "{\"display_name\":\"Mo Maker\",\"email\":\"mo@acme.test\",\"role\":\"MAKER\","
                + "\"daily_limit\":\"75000\",\"limit_currency\":\"KES\"}";
        assertProblem(call(adminA, "POST", "/v1/business/users", body, false), 401, "step_up_required");
        maker = new Who(BIZ_A, id(ok(call(adminA, "POST", "/v1/business/users", body))));
        maker2 = user("Mia Maker", "MAKER");
        approver1 = user("Ann Approver", "APPROVER");
        approver2 = user("Ben Approver", "APPROVER");
        approver3 = user("Cy Approver", "APPROVER");
        viewer = user("Val Viewer", "VIEWER");
        assertThat(ok(call(maker, "GET", "/v1/business/me", null)).path("user").path("role").asString()).isEqualTo("MAKER");

        // only admins administer
        assertProblem(call(maker, "POST", "/v1/business/users",
                "{\"display_name\":\"X\",\"email\":\"x@acme.test\",\"role\":\"ADMIN\"}"), 403, "role_not_allowed");

        entitle(maker, a1, true, true, false, "100000");
        entitle(maker, a2, true, false, false, null);
        entitle(maker2, a1, true, true, false, null);
        entitle(approver1, a1, true, false, true, null);
        entitle(approver2, a1, true, false, true, null);
        entitle(approver3, a1, true, false, true, null);
        entitle(approver1, a2, true, false, false, null);
        entitle(viewer, a1, true, false, false, null);
        entitle(new Who(BIZ_A, ADMIN_A), a1, true, true, true, null);
        // an approver cannot be entitled to initiate, a maker not to approve
        assertProblem(call(adminA, "PUT", "/v1/business/users/" + approver1.user() + "/entitlements/" + a1,
                "{\"can_view\":true,\"can_initiate\":true,\"can_approve\":true}"), 422, "role_cannot_initiate");
        assertProblem(call(adminA, "PUT", "/v1/business/users/" + maker.user() + "/entitlements/" + a1,
                "{\"can_view\":true,\"can_initiate\":true,\"can_approve\":true}"), 422, "role_cannot_approve");
        // entitlements only ever name the business's own accounts
        assertProblem(call(adminA, "PUT", "/v1/business/users/" + maker.user() + "/entitlements/" + b1,
                "{\"can_view\":true,\"can_initiate\":true,\"can_approve\":false}"), 404, "not_found");
        assertThat(ok(call(adminA, "GET", "/v1/business/users/" + maker.user() + "/entitlements", null))
                .path("data")).hasSize(2);
        // guard rails: no own role change, the last admin stays
        assertProblem(call(adminA, "PATCH", "/v1/business/users/" + ADMIN_A, "{\"role\":\"VIEWER\"}"), 422,
                "own_role_change_forbidden");
        assertProblem(call(adminA, "POST", "/v1/business/users/" + ADMIN_A + "/suspend", "{}"), 422,
                "own_status_change_forbidden");
        // suspend and reactivate a user: a suspended user is refused everywhere
        ok(call(adminA, "POST", "/v1/business/users/" + viewer.user() + "/suspend", "{\"reason\":\"leave\"}"));
        assertProblem(call(viewer, "GET", "/v1/business/accounts", null), 403, "business_user_inactive");
        ok(call(adminA, "POST", "/v1/business/users/" + viewer.user() + "/reactivate", null));
        assertThat(ok(call(viewer, "GET", "/v1/business/accounts", null)).path("data")).hasSize(1);
        // B's admin never sees A's users
        assertProblem(call(adminB, "GET", "/v1/business/users/" + maker.user(), null), 404, "not_found");
        assertThat(ok(call(adminB, "GET", "/v1/business/users", null)).path("data")).hasSize(1);
    }

    @Test
    @Order(3)
    void theApprovalMatrixHasBandsWithoutOverlap() throws Exception {
        ok(call(adminA, "POST", "/v1/business/approval-rules", """
                {"name":"Small","currency":"KES","min_amount":"0","max_amount":"999.99","required_approvals":1}"""));
        ok(call(adminA, "POST", "/v1/business/approval-rules", """
                {"name":"Medium","currency":"KES","min_amount":"1000","max_amount":"49999.99","required_approvals":2}"""));
        ok(call(adminA, "POST", "/v1/business/approval-rules", """
                {"name":"Large","currency":"KES","min_amount":"50000","required_approvals":2,"approval_order":"SEQUENTIAL",
                 "approver_ids":["%s","%s"]}""".formatted(approver2.user(), approver1.user())));
        assertProblem(call(adminA, "POST", "/v1/business/approval-rules", """
                {"name":"Clash","currency":"KES","min_amount":"500","max_amount":"1500","required_approvals":1}"""), 409,
                "approval_rule_overlap");
        assertProblem(call(adminA, "POST", "/v1/business/approval-rules", """
                {"name":"Bad","currency":"USD","min_amount":"0","required_approvals":2,"approval_order":"SEQUENTIAL",
                 "approver_ids":["%s"]}""".formatted(approver1.user())), 422, "invalid_approval_rule");
        assertProblem(call(adminA, "POST", "/v1/business/approval-rules", """
                {"name":"Maker approves","currency":"USD","min_amount":"0","required_approvals":1,
                 "approver_ids":["%s"]}""".formatted(maker.user())), 422, "approver_not_eligible");
        assertProblem(call(maker, "POST", "/v1/business/approval-rules", """
                {"name":"X","currency":"EUR","min_amount":"0","required_approvals":1}"""), 403, "role_not_allowed");
        ruleB = id(ok(call(adminB, "POST", "/v1/business/approval-rules", """
                {"name":"B all","currency":"KES","min_amount":"0","required_approvals":1}""")));
        assertThat(ok(call(adminA, "GET", "/v1/business/approval-rules", null)).path("data")).hasSize(3);
    }

    // ------------------------------------------------------------------ payments and approvals

    @Test
    @Order(10)
    void aPaymentNeedsTwoDistinctApproversAndIsReleasedOnlyAfterTheSecond() throws Exception {
        JsonNode draft = ok(call(maker, "POST", "/v1/business/payments", """
                {"kind":"DOMESTIC","debtor_account_id":"%s","payee_id":"%s","amount":"5000","reference":"INV-77",
                 "customer_id":"%s","status":"RELEASED","initiated_by":"%s"}"""
                .formatted(a1, payeeA, BIZ_B, approver1.user())));
        String id = id(draft);
        // the body is rebuilt server-side: client-supplied business, status and initiator are ignored
        assertThat(draft.path("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.path("business_customer_id").asString()).isEqualTo(BIZ_A);
        assertThat(draft.path("initiated_by").asString()).isEqualTo(maker.user());
        assertThat(draft.path("creditor_name").asString()).isEqualTo("Supplier One");
        assertThat(draft.path("checksum").asString()).hasSize(64);

        // only the initiator submits
        assertProblem(call(maker2, "POST", "/v1/business/payments/" + id + "/submit", null), 403, "not_initiator");
        JsonNode submitted = ok(call(maker, "POST", "/v1/business/payments/" + id + "/submit", null));
        assertThat(submitted.path("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(submitted.path("required_approvals").asInt()).isEqualTo(2);

        // makers do not approve; nothing reached payment-service yet
        assertProblem(call(maker, "POST", "/v1/business/payments/" + id + "/approve", "{}"), 403, "role_not_allowed");
        // step-up on approve: a stale authentication records nothing
        assertProblem(call(approver1, "POST", "/v1/business/payments/" + id + "/approve", "{}", false), 401,
                "step_up_required");
        JsonNode first = ok(call(approver1, "POST", "/v1/business/payments/" + id + "/approve", "{\"comment\":\"ok\"}"));
        assertThat(first.path("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(first.path("approvals_received").asInt()).isEqualTo(1);
        assertThat(bank.writes(PAY, "/v1/payments")).isEmpty();
        assertProblem(call(approver1, "POST", "/v1/business/payments/" + id + "/approve", "{}"), 409, "already_decided");
        assertThat(ok(call(approver2, "GET", "/v1/business/approval-queue", null)).path("data").toString()).contains(id);
        assertThat(ok(call(approver1, "GET", "/v1/business/approval-queue", null)).path("data").toString())
                .doesNotContain(id);

        JsonNode released = ok(call(approver2, "POST", "/v1/business/payments/" + id + "/approve", "{}"));
        assertThat(released.path("status").asString()).isEqualTo("RELEASED");
        assertThat(released.path("released_by").asString()).isEqualTo(approver2.user());
        FakeBank.Call create = bank.lastWrite(PAY, "/v1/payments");
        assertThat(bank.writes(PAY, "/v1/payments")).hasSize(1);
        assertThat(create.key()).isEqualTo("bzp-release:" + id);
        assertThat(create.principal()).isEqualTo("business-user:" + maker.user());
        assertThat(create.body().path("customer_id").asString()).isEqualTo(BIZ_A);
        assertThat(create.body().path("debtor_account_id").asString()).isEqualTo(a1);
        assertThat(create.body().path("payee_id").asString()).isEqualTo(payeeA);
        assertThat(create.body().path("payment_type").asString()).isEqualTo("DOMESTIC");
        assertThat(create.body().path("amount").asString()).isEqualTo("5000");
        assertThat(create.body().path("metadata").path("business_payment_id").asString()).isEqualTo(id);
        assertThat(bank.row(PAY, "/v1/payments", released.path("payment_id").asString())).isNotNull();

        JsonNode approvals = ok(call(maker, "GET", "/v1/business/payments/" + id + "/approvals", null));
        assertThat(approvals.path("data")).hasSize(2);
        assertThat(approvals.path("data").get(0).path("business_user_id").asString()).isEqualTo(approver1.user());
        assertThat(approvals.path("data").get(0).path("checksum").asString()).isEqualTo(draft.path("checksum").asString());
        assertThat(ok(call(maker, "GET", "/v1/business/payments/" + id + "/downstream", null))
                .path("payment").path("status").asString()).isEqualTo("SUBMITTED");
        // released payments cannot be decided again
        assertProblem(call(approver3, "POST", "/v1/business/payments/" + id + "/approve", "{}"), 409,
                "invalid_transition");
        assertThat(events("channels.business_payment.released")).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(11)
    void selfApprovalIsForbiddenEvenForAnAdminWithBothEntitlements() throws Exception {
        String id = submitted(adminA, "DOMESTIC", "1500", payeeA);
        assertProblem(call(adminA, "POST", "/v1/business/payments/" + id + "/approve", "{}"), 403,
                "self_approval_forbidden");
        assertThat(ok(call(adminA, "GET", "/v1/business/approval-queue", null)).path("data").toString()).doesNotContain(id);
        ok(call(approver1, "POST", "/v1/business/payments/" + id + "/approve", "{}"));
        assertThat(ok(call(approver2, "POST", "/v1/business/payments/" + id + "/approve", "{}")).path("status").asString())
                .isEqualTo("RELEASED");
    }

    @Test
    @Order(12)
    void amountBandsDecideTheNumberOfApprovers() throws Exception {
        String small = submitted(maker, "DOMESTIC", "999.99", payeeA);
        assertThat(ok(call(maker, "GET", "/v1/business/payments/" + small, null)).path("required_approvals").asInt())
                .isEqualTo(1);
        assertThat(ok(call(approver3, "POST", "/v1/business/payments/" + small + "/approve", "{}")).path("status")
                .asString()).isEqualTo("RELEASED");
        String medium = submitted(maker, "DOMESTIC", "1000", payeeA);
        assertThat(ok(call(maker, "GET", "/v1/business/payments/" + medium, null)).path("required_approvals").asInt())
                .isEqualTo(2);
        ok(call(maker, "POST", "/v1/business/payments/" + medium + "/cancel", "{\"reason\":\"duplicate\"}"));
    }

    @Test
    @Order(13)
    void sequentialRulesNeedTheNamedApproversInOrder() throws Exception {
        String id = submitted(maker, "DOMESTIC", "60000", payeeA);
        JsonNode p = ok(call(maker, "GET", "/v1/business/payments/" + id, null));
        assertThat(p.path("approval_order").asString()).isEqualTo("SEQUENTIAL");
        assertProblem(call(approver1, "POST", "/v1/business/payments/" + id + "/approve", "{}"), 409, "out_of_sequence");
        assertProblem(call(approver3, "POST", "/v1/business/payments/" + id + "/approve", "{}"), 403,
                "not_designated_approver");
        assertThat(ok(call(approver1, "GET", "/v1/business/approval-queue", null)).path("data").toString())
                .doesNotContain(id);
        ok(call(approver2, "POST", "/v1/business/payments/" + id + "/approve", "{}"));
        assertThat(ok(call(approver1, "POST", "/v1/business/payments/" + id + "/approve", "{}")).path("status").asString())
                .isEqualTo("RELEASED");
    }

    @Test
    @Order(14)
    void aFailedReleaseIsRetriedIdempotentlyAndNeverCreatesTwoPayments() throws Exception {
        String id = submitted(maker, "DOMESTIC", "2000", payeeA);
        ok(call(approver1, "POST", "/v1/business/payments/" + id + "/approve", "{}"));
        bank.failOnce.put("POST payment-service /v1/payments", new ApiException(502, "upstream_error", "rail down"));
        int before = bank.writes(PAY, "/v1/payments").size();
        JsonNode failed = ok(call(approver2, "POST", "/v1/business/payments/" + id + "/approve", "{}"));
        assertThat(failed.path("status").asString()).isEqualTo("RELEASE_FAILED");
        assertThat(failed.path("release_error").asString()).startsWith("upstream_error");
        // makers and uninvolved approvers cannot release
        assertProblem(call(maker, "POST", "/v1/business/payments/" + id + "/release", null), 403, "not_entitled");
        assertProblem(call(approver3, "POST", "/v1/business/payments/" + id + "/release", null), 403, "not_entitled");
        JsonNode released = ok(call(approver1, "POST", "/v1/business/payments/" + id + "/release", null));
        assertThat(released.path("status").asString()).isEqualTo("RELEASED");
        JsonNode again = ok(call(adminA, "POST", "/v1/business/payments/" + id + "/release", null));
        assertThat(again.path("payment_id").asString()).isEqualTo(released.path("payment_id").asString());
        List<FakeBank.Call> creates = bank.writes(PAY, "/v1/payments").subList(before, bank.writes(PAY, "/v1/payments").size());
        assertThat(creates).hasSize(2);                               // the failed attempt and its retry
        assertThat(creates).allSatisfy(c -> assertThat(c.key()).isEqualTo("bzp-release:" + id));
        assertThat(creates).allSatisfy(c -> assertThat(c.principal()).isEqualTo("business-user:" + maker.user()));
        assertThat(bank.rows(PAY, "/v1/payments").values().stream()
                .filter(r -> id.equals(r.path("metadata").path("business_payment_id").asString(null)))).hasSize(1);
    }

    @Test
    @Order(15)
    void businessApprovalsAreRelayedToPaymentServiceApprovalLevelsAsTheApprovers() throws Exception {
        bank.paymentStatusOnCreate = "PENDING_APPROVAL";
        bank.bankApprovals = 2;
        try {
            String id = submitted(maker, "DOMESTIC", "3000", payeeA);
            ok(call(approver1, "POST", "/v1/business/payments/" + id + "/approve", "{}"));
            JsonNode released = ok(call(approver2, "POST", "/v1/business/payments/" + id + "/approve", "{}"));
            assertThat(released.path("status").asString()).isEqualTo("RELEASED");
            assertThat(released.path("downstream_status").asString()).isEqualTo("APPROVED");
            String pid = released.path("payment_id").asString();
            List<FakeBank.Call> approvals = bank.writes(PAY, "/v1/payments/" + pid + "/approve");
            assertThat(approvals).extracting(FakeBank.Call::principal).containsExactly(
                    "business-user:" + approver1.user(), "business-user:" + approver2.user());
            assertThat(approvals.get(0).key()).isEqualTo("bzp-approve:" + id + ":" + approver1.user());
        } finally {
            bank.paymentStatusOnCreate = "SUBMITTED";
            bank.bankApprovals = 1;
        }
    }

    @Test
    @Order(16)
    void rejectionsEndThePaymentAndOnlyTheInitiatorOrAnAdminCancels() throws Exception {
        String id = submitted(maker, "DOMESTIC", "1200", payeeA);
        assertProblem(call(approver1, "POST", "/v1/business/payments/" + id + "/reject", "{}"), 400, "validation_failed");
        JsonNode rejected = ok(call(approver1, "POST", "/v1/business/payments/" + id + "/reject",
                "{\"reason\":\"wrong supplier\"}"));
        assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.path("rejected_by").asString()).isEqualTo(approver1.user());
        assertProblem(call(approver2, "POST", "/v1/business/payments/" + id + "/approve", "{}"), 409, "invalid_transition");
        assertThat(events("channels.business_payment.rejected")).isGreaterThanOrEqualTo(1);

        String draft = id(ok(call(maker, "POST", "/v1/business/payments", """
                {"kind":"DOMESTIC","debtor_account_id":"%s","payee_id":"%s","amount":"10"}""".formatted(a1, payeeA))));
        assertProblem(call(maker2, "POST", "/v1/business/payments/" + draft + "/cancel", "{}"), 403, "not_initiator");
        assertThat(ok(call(adminA, "POST", "/v1/business/payments/" + draft + "/cancel", "{}")).path("status").asString())
                .isEqualTo("CANCELLED");
    }

    @Test
    @Order(17)
    void entitlementsRolesCapsAndDailyLimitsAreEnforced() throws Exception {
        String domestic = "{\"kind\":\"DOMESTIC\",\"debtor_account_id\":\"%s\",\"payee_id\":\"%s\",\"amount\":\"%s\"}";
        // a viewer reads but never initiates
        assertThat(ok(call(viewer, "GET", "/v1/business/accounts/" + a1, null)).path("id").asString()).isEqualTo(a1);
        assertProblem(call(viewer, "POST", "/v1/business/payments", domestic.formatted(a1, payeeA, "10")), 403,
                "role_not_allowed");
        // the maker sees a2 but may not initiate on it, and does not see the USD account at all
        assertThat(ok(call(maker, "GET", "/v1/business/accounts/" + a2, null)).path("id").asString()).isEqualTo(a2);
        assertProblem(call(maker, "POST", "/v1/business/payments", domestic.formatted(a2, payeeA, "10")), 403,
                "not_entitled");
        assertProblem(call(maker, "GET", "/v1/business/accounts/" + usd, null), 404, "not_found");
        assertThat(ok(call(maker, "GET", "/v1/business/accounts", null)).path("data")).hasSize(2);
        assertThat(ok(call(adminA, "GET", "/v1/business/accounts", null)).path("data")).hasSize(3);
        // per-payment cap of the entitlement (100,000 on a1)
        assertProblem(call(maker, "POST", "/v1/business/payments", domestic.formatted(a1, payeeA, "100000.01")), 422,
                "amount_above_entitlement");
        // an approver without the approve entitlement on the debit account
        assertProblem(call(adminA, "POST", "/v1/business/payments", domestic.formatted(a2, payeeA, "20")), 403,
                "not_entitled");
        entitle(new Who(BIZ_A, ADMIN_A), a2, true, true, false, null);
        String onA2 = id(ok(call(adminA, "POST", "/v1/business/payments", domestic.formatted(a2, payeeA, "20"))));
        ok(call(adminA, "POST", "/v1/business/payments/" + onA2 + "/submit", null));
        assertProblem(call(approver1, "POST", "/v1/business/payments/" + onA2 + "/approve", "{}"), 403, "not_entitled");
        // daily limit of the maker: 75,000 KES; 5,000 + 999.99 + 60,000 + 2,000 + 3,000 count today (the cancelled
        // 1,000 and the rejected 1,200 do not): 70,999.99 + 5,000 is above it
        assertProblem(call(maker, "POST", "/v1/business/payments/" + draft(maker, "5000") + "/submit", null), 422,
                "daily_limit_exceeded");
        assertThat(rows("select count(*) from business_action where outcome = 'DENIED' and error_code = 'daily_limit_exceeded'"))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ bulk and payroll

    @Test
    @Order(20)
    void bulkUploadsAreValidatedLineByLineAndReleasedAsCsvAfterApproval() throws Exception {
        String bad = """
                creditor_name,creditor_account_number,amount,creditor_agent_bic,currency
                Alice,12345678,100.00,,
                ,999,abc,NOTABIC,
                Bob,GB82WEST12345698765433,50.123,,USD
                """;
        JsonNode draft = ok(call(maker2, "POST", "/v1/business/bulk-payments", json.writeValueAsString(java.util.Map.of(
                "kind", "PAYROLL", "debtor_account_id", a1, "name", "October payroll", "content", bad))));
        assertThat(draft.path("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.path("item_count").asInt()).isEqualTo(3);
        assertThat(draft.path("invalid_count").asInt()).isEqualTo(2);
        assertThat(draft.path("validation_errors").toString()).contains("missing_field", "invalid_amount", "invalid_bic",
                "invalid_iban", "currency_mismatch");
        assertProblem(call(maker2, "POST", "/v1/business/payments/" + id(draft) + "/submit", null), 422,
                "bulk_has_invalid_items");
        assertProblem(call(maker2, "POST", "/v1/business/bulk-payments", json.writeValueAsString(java.util.Map.of(
                "kind", "BULK", "debtor_account_id", a1, "name", "x", "content", "name,amount\nA,1\n"))), 422,
                "missing_csv_column");

        String good = """
                creditor_name,creditor_account_number,amount,remittance
                "Alice, A.",12345678,100.00,Salary Oct
                Bob,GB82 WEST 1234 5698 7654 32,250.50,Salary Oct
                """;
        String id = id(ok(call(maker2, "POST", "/v1/business/bulk-payments", json.writeValueAsString(java.util.Map.of(
                "kind", "PAYROLL", "debtor_account_id", a1, "name", "October payroll", "content", good)))));
        JsonNode submitted = ok(call(maker2, "POST", "/v1/business/payments/" + id + "/submit", null));
        assertThat(new java.math.BigDecimal(submitted.path("amount").asString())).isEqualByComparingTo("350.50");
        assertThat(submitted.path("required_approvals").asInt()).isEqualTo(1);
        assertProblem(call(maker2, "GET", "/v1/business/bulk-payments/" + id + "/report", null), 409, "not_released");
        JsonNode released = ok(call(approver1, "POST", "/v1/business/payments/" + id + "/approve", "{}"));
        assertThat(released.path("status").asString()).isEqualTo("RELEASED");
        FakeBank.Call upload = bank.lastWrite(PAY, "/v1/bulk-payments/upload");
        assertThat(upload.principal()).isEqualTo("business-user:" + maker2.user());
        assertThat(upload.key()).isEqualTo("bzp-release:" + id);
        assertThat(upload.body().path("customer_id").asString()).isEqualTo(BIZ_A);
        assertThat(upload.body().path("content").asString()).contains("\"Alice, A.\"", "GB82WEST12345698765432", "\"250.50\"");
        String bulkId = released.path("bulk_payment_id").asString();
        assertThat(bank.lastWrite(PAY, "/v1/bulk-payments/" + bulkId + "/submit").principal())
                .isEqualTo("business-user:" + maker2.user());
        assertThat(bank.lastWrite(PAY, "/v1/bulk-payments/" + bulkId + "/approve").principal())
                .isEqualTo("business-user:" + approver1.user());
        assertThat(released.path("downstream_status").asString()).isEqualTo("APPROVED");
        HttpResponse<String> report = call(maker2, "GET", "/v1/business/bulk-payments/" + id + "/report", null);
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(report.body()).contains("SETTLED");
    }

    // ------------------------------------------------------------------ FX

    @Test
    @Order(21)
    void internationalPaymentsUseAQuoteOfTheBusinessThatSellsExactlyTheAmount() throws Exception {
        entitle(maker, usd, true, true, false, null);
        JsonNode quote = ok(call(maker, "POST", "/v1/business/fx-quotes",
                "{\"sell_currency\":\"KES\",\"buy_currency\":\"USD\",\"sell_amount\":\"10000\"}"));
        assertThat(quote.path("customer_id").asString()).isEqualTo(BIZ_A);
        FakeBank.Call qc = bank.lastWrite("fx-service", "/v1/fx/quotes");
        assertThat(qc.body().path("customer_id").asString()).isEqualTo(BIZ_A);
        quoteB = id(bank.add("fx-service", "/v1/fx/quotes", """
                {"customer_id":"%s","sell_currency":"KES","buy_currency":"USD","sell_amount":"10000","buy_amount":"75.00",
                 "status":"ACTIVE","expires_at":"%s"}""".formatted(BIZ_B, java.time.OffsetDateTime.now().plusMinutes(5))));
        String intl = "{\"kind\":\"INTERNATIONAL\",\"debtor_account_id\":\"%s\",\"payee_id\":\"%s\",\"amount\":\"%s\","
                + "\"fx_quote_id\":\"%s\"}";
        assertProblem(call(maker, "POST", "/v1/business/payments", intl.formatted(a1, payeeAusd, "10000", quoteB)), 404,
                "not_found");
        assertProblem(call(maker, "POST", "/v1/business/payments", intl.formatted(a1, payeeAusd, "1.00", id(quote))), 422,
                "fx_quote_amount_mismatch");
        assertProblem(call(maker, "POST", "/v1/business/payments", intl.formatted(a1, payeeA, "10000", id(quote))), 422,
                "fx_quote_mismatch");
        JsonNode draft = ok(call(maker, "POST", "/v1/business/payments", intl.formatted(a1, payeeAusd, "10000", id(quote))));
        assertThat(draft.path("instructed_currency").asString()).isEqualTo("USD");
        assertThat(new java.math.BigDecimal(draft.path("instructed_amount").asString())).isEqualByComparingTo("75.00");
        assertProblem(call(maker, "POST", "/v1/business/payments", intl.formatted(a1, payeeAusd, "10000", id(quote))), 409,
                "fx_quote_in_use");
        // a domestic payment to a USD payee from a KES account needs FX
        assertProblem(call(maker, "POST", "/v1/business/payments",
                "{\"kind\":\"DOMESTIC\",\"debtor_account_id\":\"%s\",\"payee_id\":\"%s\",\"amount\":\"5\"}"
                        .formatted(a1, payeeAusd)), 422, "currency_mismatch");
        assertProblem(call(maker, "GET", "/v1/business/fx-quotes/" + quoteB, null), 404, "not_found");
        assertThat(ok(call(maker, "GET", "/v1/business/fx-quotes", null)).path("data")).hasSize(1);
    }

    // ------------------------------------------------------------------ cross-business

    @Test
    @Order(30)
    void everyFamilyOfAnotherBusinessAnswers404AndNothingIsWritten() throws Exception {
        ok(call(adminB, "PUT", "/v1/business/users/" + ADMIN_B + "/entitlements/" + b1,
                "{\"can_view\":true,\"can_initiate\":true,\"can_approve\":true}"));
        String bPayment = id(ok(call(adminB, "POST", "/v1/business/payments",
                "{\"kind\":\"DOMESTIC\",\"debtor_account_id\":\"%s\",\"payee_id\":\"%s\",\"amount\":\"10\"}"
                        .formatted(b1, payeeB))));
        String bClient = id(ok(call(adminB, "POST", "/v1/business/api-clients", "{\"name\":\"B ERP\"}")));
        String bEndpoint = id(ok(call(adminB, "POST", "/v1/business/webhook-endpoints",
                "{\"url\":\"https://erp.beta.test/hooks\",\"api_client_id\":\"%s\"}".formatted(bClient))));
        String bUser = id(ok(call(adminB, "POST", "/v1/business/users",
                "{\"display_name\":\"B Maker\",\"email\":\"m@beta.test\",\"role\":\"MAKER\"}")));
        int writesBefore = bank.writes().size();
        long deniedBefore = rows("select count(*) from business_action where outcome = 'DENIED' and error_code = 'not_owned'");

        String[][] reads = {
                {"/v1/business/accounts/" + b1}, {"/v1/business/accounts/" + b1 + "/balance"},
                {"/v1/business/accounts/" + b1 + "/transactions"}, {"/v1/business/accounts/" + b1 + "/statements"},
                {"/v1/business/accounts/" + a1 + "/statements/" + statementB + "/download"},
                {"/v1/business/accounts/" + b1 + "/export?from=2026-10-01&to=2026-10-02"},
                {"/v1/business/payments/" + bPayment}, {"/v1/business/payments/" + bPayment + "/approvals"},
                {"/v1/business/payments?account_id=" + b1},
                {"/v1/business/mandates/" + mandateB}, {"/v1/business/mandates/" + mandateB + "/collections"},
                {"/v1/business/virtual-accounts/" + vaB}, {"/v1/business/virtual-accounts?master_account_id=" + b1},
                {"/v1/business/payment-links/" + linkB}, {"/v1/business/payment-links/" + linkB + "/payments"},
                {"/v1/business/cards/" + cardB}, {"/v1/business/cards/" + cardB + "/limits"},
                {"/v1/business/loans/" + loanB}, {"/v1/business/loans/" + loanB + "/schedule"},
                {"/v1/business/credit-lines/" + lineB}, {"/v1/business/credit-lines/" + lineB + "/drawdowns"},
                {"/v1/business/fx-quotes/" + quoteB}, {"/v1/business/api-clients/" + bClient},
                {"/v1/business/api-clients/" + bClient + "/keys"}, {"/v1/business/webhook-endpoints/" + bEndpoint},
                {"/v1/business/webhook-endpoints/" + bEndpoint + "/deliveries"}, {"/v1/business/users/" + bUser}};
        for (String[] r : reads) {
            assertProblem(call(adminA, "GET", r[0], null), 404, "not_found");
        }
        String[][] writes = {
                {"POST", "/v1/business/payments", "{\"kind\":\"DOMESTIC\",\"debtor_account_id\":\"" + b1
                        + "\",\"payee_id\":\"" + payeeB + "\",\"amount\":\"1\"}"},
                {"POST", "/v1/business/payments", "{\"kind\":\"DOMESTIC\",\"debtor_account_id\":\"" + a1
                        + "\",\"payee_id\":\"" + payeeB + "\",\"amount\":\"1\"}"},
                {"POST", "/v1/business/payments", "{\"kind\":\"OWN_TRANSFER\",\"debtor_account_id\":\"" + a1
                        + "\",\"creditor_account_id\":\"" + b1 + "\",\"amount\":\"1\"}"},
                {"POST", "/v1/business/payments/" + bPayment + "/submit", null},
                {"POST", "/v1/business/payments/" + bPayment + "/approve", "{}"},
                {"POST", "/v1/business/payments/" + bPayment + "/cancel", "{}"},
                {"DELETE", "/v1/business/payees/" + payeeB, null},
                {"POST", "/v1/business/mandates/" + mandateB + "/cancel", "{\"reason\":\"x\"}"},
                {"POST", "/v1/business/mandates/" + mandateB + "/collections", "{\"amount\":\"1\",\"due_date\":\"2030-01-01\"}"},
                {"POST", "/v1/business/virtual-accounts/" + vaB + "/close", "{}"},
                {"POST", "/v1/business/virtual-accounts", "{\"master_account_id\":\"" + b1 + "\",\"purpose\":\"COLLECTION\"}"},
                {"POST", "/v1/business/payment-links/" + linkB + "/disable", "{}"},
                {"POST", "/v1/business/payment-links", "{\"credit_account_id\":\"" + b1 + "\",\"title\":\"t\"}"},
                {"POST", "/v1/business/cards/" + cardB + "/freeze", null},
                {"PUT", "/v1/business/cards/" + cardB + "/limits", "{\"period\":\"DAILY\",\"max_amount\":\"5\"}"},
                {"POST", "/v1/business/cards", "{\"card_product_id\":\"" + productId + "\",\"deposit_account_id\":\"" + b1
                        + "\",\"cardholder_name\":\"EVE\"}"},
                {"POST", "/v1/business/loans/" + loanB + "/repay", "{\"amount\":\"1\",\"source_account_id\":\"" + a1 + "\"}"},
                {"POST", "/v1/business/loans/" + loanA + "/repay", "{\"amount\":\"1\",\"source_account_id\":\"" + b1 + "\"}"},
                {"POST", "/v1/business/credit-lines/" + lineB + "/draw", "{\"amount\":\"1\",\"destination_account_id\":\"" + a1 + "\"}"},
                {"POST", "/v1/business/credit-lines/" + lineA + "/draw", "{\"amount\":\"1\",\"destination_account_id\":\"" + b1 + "\"}"},
                {"POST", "/v1/business/api-clients/" + bClient + "/keys", "{\"name\":\"k\"}"},
                {"POST", "/v1/business/api-clients/" + bClient + "/suspend", "{\"reason\":\"x\"}"},
                {"POST", "/v1/business/webhook-endpoints", "{\"url\":\"https://a.test/h\",\"api_client_id\":\"" + bClient + "\"}"},
                {"POST", "/v1/business/webhook-endpoints/" + bEndpoint + "/subscriptions", "{\"event_type\":\"payments.payment.*\"}"},
                {"DELETE", "/v1/business/webhook-endpoints/" + bEndpoint, null},
                {"PATCH", "/v1/business/users/" + bUser, "{\"role\":\"ADMIN\"}"},
                {"PUT", "/v1/business/users/" + bUser + "/entitlements/" + a1, "{\"can_view\":true,\"can_initiate\":false,\"can_approve\":false}"},
                {"PUT", "/v1/business/approval-rules/" + ruleB, "{\"name\":\"x\",\"currency\":\"KES\",\"min_amount\":\"0\",\"required_approvals\":1}"},
                {"DELETE", "/v1/business/approval-rules/" + ruleB, null},
                {"POST", "/v1/business/accounts/" + b1 + "/statements", "{\"period_start\":\"2026-09-01\",\"period_end\":\"2026-09-30\"}"}};
        for (String[] w : writes) {
            HttpResponse<String> r = call(adminA, w[0], w[1], w[2]);
            assertThat(r.statusCode()).as(w[0] + " " + w[1] + " " + r.body()).isEqualTo(404);
        }
        assertThat(bank.writes().subList(writesBefore, bank.writes().size())).isEmpty();
        assertThat(rows("select count(*) from business_action where outcome = 'DENIED' and error_code = 'not_owned'")
                - deniedBefore).isGreaterThanOrEqualTo(reads.length + writes.length - 2);
        assertThat(events("channels.business_action.denied")).isGreaterThan(20);
        // B's lists never contain A's objects and vice versa
        assertThat(ok(call(adminA, "GET", "/v1/business/payments", null)).path("data").toString()).doesNotContain(bPayment);
        assertThat(ok(call(adminA, "GET", "/v1/business/payees", null)).path("data").toString()).doesNotContain(payeeB);
        assertThat(ok(call(adminA, "GET", "/v1/business/cards", null)).path("data").toString()).doesNotContain(cardB);
        assertThat(ok(call(adminA, "GET", "/v1/business/api-clients", null)).path("data")).isEmpty();
    }

    // ------------------------------------------------------------------ collections, cards, lending, integrations

    @Test
    @Order(40)
    void collectionsCardsLendingAndIntegrationsAreBuiltForTheBusiness() throws Exception {
        JsonNode mandate = ok(call(maker, "POST", "/v1/business/mandates", """
                {"scheme":"INTERNAL","creditor_account_id":"%s","creditor_id":"CI-1","mandate_reference":"M-1",
                 "debtor_name":"Customer X","debtor_account_number":"12 34","customer_id":"%s","direction":"INBOUND"}"""
                .formatted(a1, BIZ_B)));
        FakeBank.Call mc = bank.lastWrite(PAY, "/v1/payment-mandates");
        assertThat(mc.body().path("customer_id").asString()).isEqualTo(BIZ_A);
        assertThat(mc.body().path("direction").asString()).isEqualTo("OUTBOUND");
        assertThat(mc.body().path("currency").asString()).isEqualTo("KES");
        assertThat(mc.body().path("creditor_name").asString()).isEqualTo("Acme Limited");
        ok(call(maker, "POST", "/v1/business/mandates/" + id(mandate) + "/collections",
                "{\"amount\":\"25\",\"due_date\":\"2030-01-01\"}"));
        assertThat(ok(call(maker, "GET", "/v1/business/mandates/" + id(mandate) + "/collections", null)).path("data")).hasSize(1);
        assertProblem(call(viewer, "POST", "/v1/business/mandates", """
                {"scheme":"INTERNAL","creditor_account_id":"%s","creditor_id":"CI-1","mandate_reference":"M-2",
                 "debtor_name":"Customer Y","debtor_account_number":"99"}""".formatted(a1)), 403, "role_not_allowed");

        JsonNode va = ok(call(maker, "POST", "/v1/business/virtual-accounts",
                "{\"master_account_id\":\"%s\",\"purpose\":\"INVOICE\",\"owner_reference\":\"INV-9\"}".formatted(a1)));
        assertThat(bank.lastWrite(DEP, "/v1/virtual-accounts").body().path("currency").asString()).isEqualTo("KES");
        assertThat(ok(call(maker, "GET", "/v1/business/virtual-accounts", null)).path("data").toString()).contains(id(va));
        JsonNode link = ok(call(maker, "POST", "/v1/business/payment-links",
                "{\"credit_account_id\":\"%s\",\"title\":\"Invoice 9\",\"amount\":\"99.50\"}".formatted(a1)));
        assertThat(bank.lastWrite(PAY, "/v1/payment-links").body().path("customer_id").asString()).isEqualTo(BIZ_A);
        assertThat(ok(call(maker, "GET", "/v1/business/payment-links/" + id(link), null)).path("title").asString())
                .isEqualTo("Invoice 9");

        assertProblem(call(maker, "POST", "/v1/business/cards", """
                {"card_product_id":"%s","deposit_account_id":"%s","cardholder_name":"Mo"}""".formatted(productId, a1)),
                403, "role_not_allowed");
        JsonNode card = ok(call(adminA, "POST", "/v1/business/cards", """
                {"card_product_id":"%s","deposit_account_id":"%s","cardholder_name":"Eve Employee","nickname":"Travel"}"""
                .formatted(productId, a1)));
        FakeBank.Call issue = bank.lastWrite("card-service", "/v1/cards");
        assertThat(issue.body().path("business_customer_id").asString()).isEqualTo(BIZ_A);
        assertThat(issue.body().path("customer_id").asString()).isEqualTo(BIZ_A);
        assertThat(issue.body().path("form_factor").asString()).isEqualTo("VIRTUAL");
        assertThat(issue.body().path("cardholder_name").asString()).isEqualTo("EVE EMPLOYEE");
        assertThat(issue.body().path("ledger_account_id").asString()).isNotBlank();
        ok(call(adminA, "PUT", "/v1/business/cards/" + id(card) + "/limits", "{\"period\":\"DAILY\",\"max_amount\":\"200\"}"));
        assertThat(bank.lastWrite("card-service", "/v1/card-spend-limits").body().path("currency").asString()).isEqualTo("KES");
        ok(call(adminA, "PUT", "/v1/business/cards/" + id(card) + "/channels/ATM", "{\"blocked\":true}"));
        assertThat(ok(call(viewer, "GET", "/v1/business/cards", null)).path("data").toString()).contains(id(card));
        assertProblem(call(viewer, "POST", "/v1/business/cards/" + id(card) + "/freeze", null), 403, "role_not_allowed");

        ok(call(maker, "POST", "/v1/business/loans/" + loanA + "/repay",
                "{\"amount\":\"50\",\"source_account_id\":\"%s\"}".formatted(a1)));
        assertProblem(call(maker, "POST", "/v1/business/credit-lines/" + lineA + "/draw",
                "{\"amount\":\"50\",\"destination_account_id\":\"%s\"}".formatted(a1)), 403, "role_not_allowed");
        ok(call(adminA, "POST", "/v1/business/credit-lines/" + lineA + "/draw",
                "{\"amount\":\"50\",\"destination_account_id\":\"%s\"}".formatted(a1)));
        assertThat(bank.lastWrite("loan-service", "/v1/credit-lines/" + lineA + "/draw").body().path("draw_type").asString())
                .isEqualTo("TRANSFER_TO_ACCOUNT");

        String client = id(ok(call(adminA, "POST", "/v1/business/api-clients", "{\"name\":\"Acme ERP\"}")));
        assertThat(bank.lastWrite("identity-service", "/v1/api-clients").body().path("allowed_scopes").toString())
                .isEqualTo("[\"channels:business\"]");
        JsonNode key = ok(call(adminA, "POST", "/v1/business/api-clients/" + client + "/keys", "{\"name\":\"prod\"}"));
        assertThat(key.path("api_client_id").asString()).isEqualTo(client);
        String endpoint = id(ok(call(adminA, "POST", "/v1/business/webhook-endpoints",
                "{\"url\":\"https://erp.acme.test/hooks\",\"api_client_id\":\"%s\"}".formatted(client))));
        ok(call(adminA, "POST", "/v1/business/webhook-endpoints/" + endpoint + "/subscriptions",
                "{\"event_type\":\"payments.payment.settled\"}"));
        assertThat(bank.lastWrite("webhook-service", "/v1/webhook-subscriptions").body().path("filter_expression").asString())
                .isEqualTo("customer_id == \"" + BIZ_A + "\"");
        assertProblem(call(adminA, "POST", "/v1/business/webhook-endpoints/" + endpoint + "/subscriptions",
                "{\"event_type\":\"payments.*\"}"), 422, "event_type_not_allowed");
        assertProblem(call(adminA, "POST", "/v1/business/webhook-endpoints",
                "{\"url\":\"http://erp.acme.test/hooks\",\"api_client_id\":\"%s\"}".formatted(client)), 422, "https_required");
        assertProblem(call(maker, "GET", "/v1/business/api-clients", null), 403, "role_not_allowed");
        assertThat(ok(call(adminA, "GET", "/v1/business/api-clients", null)).path("data")).hasSize(1);
        assertThat(ok(call(adminA, "GET", "/v1/business/webhook-endpoints", null)).path("data")).hasSize(1);
    }

    // ------------------------------------------------------------------ reporting

    @Test
    @Order(50)
    void cashPositionAndAccountingExports() throws Exception {
        JsonNode cash = ok(call(adminA, "GET", "/v1/business/cash-position", null));
        assertThat(cash.path("accounts")).hasSize(3);
        assertThat(cash.path("totals").toString()).contains("\"currency\":\"KES\"", "\"available_balance\":\"2000.00\"");
        assertThat(ok(call(maker, "GET", "/v1/business/cash-position", null)).path("accounts")).hasSize(3);  // a1, a2, usd

        HttpResponse<String> csv = call(maker, "GET", "/v1/business/accounts/" + a1 + "/export?from=2026-10-01&to=2026-10-31",
                null);
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(csv.body()).startsWith("booking_date,value_date").contains("2026-10-01,2026-10-01", "500.00",
                "-300.00", "\"'=cmd\"");
        HttpResponse<String> camt = call(maker, "GET", "/v1/business/accounts/" + a1
                + "/export?from=2026-10-01&to=2026-10-31&format=CAMT_053", null);
        assertThat(camt.statusCode()).isEqualTo(200);
        assertThat(camt.body()).contains("urn:iso:std:iso:20022:tech:xsd:camt.053.001.08", "<Cd>OPBD</Cd>",
                "<Amt Ccy=\"KES\">1000.00</Amt>", "<Cd>CLBD</Cd>", "<CdtDbtInd>CRDT</CdtDbtInd>",
                "<CdtDbtInd>DBIT</CdtDbtInd>", "Supplier &lt;one&gt; &amp; co", "<Nm>Acme Limited</Nm>");
        assertProblem(call(maker, "GET", "/v1/business/accounts/" + a1 + "/export?from=2026-10-31&to=2026-10-01", null),
                422, "invalid_period");
    }

    @Test
    @Order(60)
    void theActivityLogShowsAdminsEverythingAndUsersTheirOwn() throws Exception {
        JsonNode all = ok(call(adminA, "GET", "/v1/business/activity?limit=200", null));
        assertThat(all.path("data").toString()).contains(maker.user(), approver1.user());
        JsonNode mine = ok(call(maker, "GET", "/v1/business/activity?limit=200", null));
        assertThat(mine.path("data")).allSatisfy(a -> assertThat(a.path("business_user_id").asString())
                .isEqualTo(maker.user()));
        assertThat(rows("select count(*) from business_action where outcome = 'STEP_UP_REQUIRED'")).isGreaterThanOrEqualTo(2);
        // B's admin never sees A's activity
        assertThat(ok(call(adminB, "GET", "/v1/business/activity?limit=200", null)).path("data"))
                .allSatisfy(a -> assertThat(a.path("business_customer_id").asString()).isEqualTo(BIZ_B));
    }

    @Test
    @Order(61)
    void idempotentReplaysNeverCrossUsers() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"kind\":\"DOMESTIC\",\"debtor_account_id\":\"%s\",\"payee_id\":\"%s\",\"amount\":\"7\"}"
                .formatted(a1, payeeA);
        JsonNode first = ok(send(maker, "POST", "/v1/business/payments", body, true, key));
        JsonNode replay = ok(send(maker, "POST", "/v1/business/payments", body, true, key));
        assertThat(id(replay)).isEqualTo(id(first));
        assertProblem(send(maker2, "POST", "/v1/business/payments", body, true, key), 422, "idempotency_key_reused");
    }

    // ------------------------------------------------------------------ helpers

    private Who user(String name, String role) throws Exception {
        return new Who(BIZ_A, id(ok(call(adminA, "POST", "/v1/business/users",
                "{\"display_name\":\"%s\",\"email\":\"%s@acme.test\",\"role\":\"%s\"}"
                        .formatted(name, name.toLowerCase().replace(' ', '.'), role)))));
    }

    private void entitle(Who who, String account, boolean view, boolean initiate, boolean approve, String max)
            throws Exception {
        ok(call(adminA, "PUT", "/v1/business/users/" + who.user() + "/entitlements/" + account,
                "{\"can_view\":%s,\"can_initiate\":%s,\"can_approve\":%s%s}".formatted(view, initiate, approve,
                        max == null ? "" : ",\"max_amount\":\"" + max + "\"")));
    }

    private String draft(Who who, String amount) throws Exception {
        return id(ok(call(who, "POST", "/v1/business/payments",
                "{\"kind\":\"DOMESTIC\",\"debtor_account_id\":\"%s\",\"payee_id\":\"%s\",\"amount\":\"%s\"}"
                        .formatted(a1, payeeA, amount))));
    }

    private String submitted(Who who, String kind, String amount, String payee) throws Exception {
        String id = id(ok(call(who, "POST", "/v1/business/payments",
                "{\"kind\":\"%s\",\"debtor_account_id\":\"%s\",\"payee_id\":\"%s\",\"amount\":\"%s\"}"
                        .formatted(kind, a1, payee, amount))));
        ok(call(who, "POST", "/v1/business/payments/" + id + "/submit", null));
        return id;
    }

    /** Queries run as the test tenant (row-level security). */
    private long rows(String sql) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", () -> jdbc.sql(sql).query(Long.class).single());
    }

    private long events(String type) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test",
                () -> jdbc.sql("select count(*) from outbox where event_type = ?").param(type).query(Long.class).single());
    }

    private String base() {
        return "http://localhost:" + port;
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
        String[] parts = path.split("\\?", 2);
        String uri = base() + parts[0] + (parts.length > 1 ? "?" + parts[1] : "");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(uri))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", TENANT)
                .header("X-Business-Customer-Id", who.business())
                .header("X-Business-User-Id", who.user())
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
        assertThat(json.readTree(r.body()).path("code").asString()).as(r.body()).isEqualTo(code);
    }

    private static String id(JsonNode n) {
        return n.path("id").asString();
    }

    @SuppressWarnings("unused")
    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unused")
    private static LocalDate today() {
        return LocalDate.now();
    }
}
