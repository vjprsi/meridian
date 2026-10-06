package io.meridian.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.payment.domain.EmvQr;
import io.meridian.payment.domain.Iban;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * payment-service end to end over HTTP against PostgreSQL 18 (RLS enforced). Every downstream service (ledger,
 * deposit, customer, routing, sanctions, limits, notification, fx) is the stateful {@link FakeBank} behind a mocked
 * {@link ServiceClient}; ledger postings are asserted on the fake's balances and journal entries.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.payment.ledger-id=0199b000-0000-7000-8000-0000000000ff",
        "meridian.payment.gl.clearing=0199b000-0000-7000-8000-0000000000c1",
        "meridian.payment.gl.fee-income=0199b000-0000-7000-8000-0000000000c2",
        "meridian.payment.gl.direct-debit-clearing=0199b000-0000-7000-8000-0000000000c3",
        "meridian.payment.fees.DOMESTIC=1.50"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentFlowIT {
    static final String TENANT = "0199b000-0000-7000-8000-00000000e001";
    static final String OTHER_TENANT = "0199b000-0000-7000-8000-00000000e002";
    static final UUID FEE_INCOME = UUID.fromString("0199b000-0000-7000-8000-0000000000c2");
    static final UUID DD_CLEARING = UUID.fromString("0199b000-0000-7000-8000-0000000000c3");
    static final UUID ALICE = UUID.fromString("0199b000-0000-7000-8000-00000000a001");
    static final UUID BOB = UUID.fromString("0199b000-0000-7000-8000-00000000a002");
    static final UUID CAROL = UUID.fromString("0199b000-0000-7000-8000-00000000a003");
    static final String A_IBAN = Iban.of("GB", "MERI00000100000001");
    static final String B_IBAN = Iban.of("GB", "MERI00000100000002");
    static final String C_IBAN = Iban.of("GB", "MERI00000100000003");
    static final String EXT_IBAN = Iban.of("GB", "NWBK60161331926819");
    static final String DE_IBAN = Iban.of("DE", "370400440532013000");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakeBank bank;
    UUID a;
    UUID b;
    UUID c;
    String outboundSettled;

    @BeforeAll
    void world() {
        bank = new FakeBank(json);
        bank.customer(ALICE, "Alice", "Smith");
        bank.customer(BOB, "Bob", "Jones");
        bank.customer(CAROL, "Carol", "White");
        a = bank.openAccount(ALICE, "GBP", A_IBAN, "1000000.00");
        b = bank.openAccount(BOB, "GBP", B_IBAN, "1000.00");
        c = bank.openAccount(CAROL, "GBP", C_IBAN, "500.00");
    }

    @BeforeEach
    void stubServiceClient() {
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> bank.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> bank.get(i.getArgument(0), i.getArgument(1)));
    }

    // ------------------------------------------------------------------ 1. book transfer

    @Test
    @Order(1)
    void internalTransferSettlesWithHoldCaptureAndIsIdempotent() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = """
                {"payment_type":"INTERNAL_TRANSFER","direction":"INTERNAL","channel":"WEB","debtor_account_id":"%s",
                 "creditor_account_id":"%s","amount":"250.00","currency":"GBP","reference":"Rent"}""".formatted(a, b);
        HttpResponse<String> r = send("POST", "/v1/payments", body, key, "maker", TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        JsonNode p = json.readTree(r.body());
        assertThat(p.get("status").asString()).isEqualTo("SETTLED");
        assertThat(p.get("creditor_name").asString()).isEqualTo("Bob Jones");
        assertThat(p.get("end_to_end_id").asString()).startsWith("E2E");
        assertThat(p.get("uetr").asString()).isNotBlank();
        assertThat(p.hasNonNull("journal_entry_id")).isTrue();
        assertThat(bank.balance(a)).isEqualByComparingTo("999750.00");
        assertThat(bank.balance(b)).isEqualByComparingTo("1250.00");
        assertThat(bank.pending(a)).isEqualByComparingTo("0");
        assertThat(bank.calls("sanctions-service", "/")).isEmpty();
        assertThat(bank.calls("limits-service", "/v1/risk/limits/checks/")).anyMatch(cl -> cl.path().endsWith("/commit"));

        HttpResponse<String> replay = send("POST", "/v1/payments", body, key, "maker", TENANT);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(p.get("id").asString());
        assertThat(bank.balance(a)).isEqualByComparingTo("999750.00");

        String id = p.get("id").asString();
        JsonNode history = get("/v1/payments/" + id + "/status-history");
        assertThat(history.get("data")).hasSize(3);
        JsonNode tracker = get("/v1/payments/" + id + "/tracker");
        assertThat(tracker.get("stage").asString()).isEqualTo("COMPLETED");
        assertThat(tracker.get("gpi_status").asString()).isEqualTo("ACCC");
        assertThat(tracker.get("timeline")).hasSize(3);

        HttpResponse<byte[]> pdf = http.send(HttpRequest.newBuilder(URI.create(url("/v1/payments/" + id + "/receipt")))
                .header("X-Tenant-Id", TENANT).header("Accept", "application/pdf").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(pdf.headers().firstValue("Content-Type").orElse("")).startsWith("application/pdf");
        String text = new String(pdf.body(), StandardCharsets.ISO_8859_1);
        assertThat(text).startsWith("%PDF-1.4").contains("Payment advice").contains("%%EOF");
    }

    // ------------------------------------------------------------------ 2. outbound lifecycle

    @Test
    @Order(2)
    void outboundPaymentIsScreenedRoutedSubmittedAndSettledByRailReports() throws Exception {
        JsonNode p = post("/v1/payments", outbound("100.00", "Charlie Brown", EXT_IBAN), "maker", 201);
        String id = p.get("id").asString();
        assertThat(p.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(p.get("rail_code").asString()).isEqualTo("FPS");
        assertThat(new BigDecimal(p.get("fee_amount").asString())).isEqualByComparingTo("1.50");
        assertThat(p.get("transaction_id").asString()).startsWith("TX");
        assertThat(bank.pending(a)).isEqualByComparingTo("101.50");
        assertThat(bank.calls("sanctions-service", "/v1/risk/sanctions/screenings/screen-payment")).isNotEmpty();
        FakeBank.Call pacs = bank.calls("routing-service", "/v1/rail-messages/pacs008").getLast();
        assertThat(pacs.body().get("payment").get("end_to_end_id").asString()).isEqualTo(p.get("end_to_end_id").asString());
        assertThat(pacs.body().get("payment").get("creditor_account_number").asString()).isEqualTo(EXT_IBAN);

        JsonNode accepted = post("/v1/payments/" + id + "/status-report", "{\"status\":\"ACCP\"}", "rail", 200);
        assertThat(accepted.get("status").asString()).isEqualTo("ACCEPTED");
        JsonNode settled = post("/v1/payments/" + id + "/status-report",
                "{\"status\":\"ACSC\",\"clearing_system_ref\":\"FPS-REF-1\"}", "rail", 200);
        assertThat(settled.get("status").asString()).isEqualTo("SETTLED");
        assertThat(settled.get("clearing_system_ref").asString()).isEqualTo("FPS-REF-1");
        assertThat(bank.balance(a)).isEqualByComparingTo("999648.50");
        assertThat(bank.pending(a)).isEqualByComparingTo("0");
        JsonNode je = bank.journal(UUID.fromString(settled.get("journal_entry_id").asString()));
        assertThat(je.get("entries").toString()).contains(FakeBank.RAIL_SETTLEMENT.toString()).contains("100.00");
        assertThat(bank.journalsWithLine(FEE_INCOME)).hasSize(1);
        // a duplicate settlement report is a no-op
        JsonNode again = post("/v1/payments/" + id + "/status-report", "{\"status\":\"ACSC\"}", "rail", 200);
        assertThat(again.get("status").asString()).isEqualTo("SETTLED");
        assertThat(bank.balance(a)).isEqualByComparingTo("999648.50");
        assertThat(get("/v1/payments/" + id + "/tracker").get("timeline").toString())
                .contains("SCREENING").contains("SUBMITTED").contains("ACCEPTED").contains("SETTLED");
        outboundSettled = id;
    }

    @Test
    @Order(3)
    void insufficientFundsRejectsAndRailRejectionReleasesTheHold() throws Exception {
        JsonNode p = post("/v1/payments", """
                {"payment_type":"DOMESTIC","direction":"OUTBOUND","channel":"MOBILE","debtor_account_id":"%s",
                 "creditor_name":"Dan Brown","creditor_account_id_type":"IBAN","creditor_account_number":"%s",
                 "amount":"5000.00","currency":"GBP"}""".formatted(b, EXT_IBAN), "bob", 201);
        assertThat(p.get("status").asString()).isEqualTo("REJECTED");
        assertThat(p.get("rejection_code").asString()).isEqualTo("AM04");
        assertThat(bank.calls("limits-service", "/v1/risk/limits/checks/")).anyMatch(cl -> cl.path().endsWith("/release"));

        JsonNode q = post("/v1/payments", outbound("40.00", "Erin Hall", EXT_IBAN), "maker", 201);
        assertThat(bank.pending(a)).isEqualByComparingTo("41.50");
        JsonNode rejected = post("/v1/payments/" + q.get("id").asString() + "/status-report",
                "{\"status\":\"RJCT\",\"reason_code\":\"AC04\",\"reason\":\"Closed account\"}", "rail", 200);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejection_code").asString()).isEqualTo("AC04");
        assertThat(bank.pending(a)).isEqualByComparingTo("0");
        post("/v1/payments/" + q.get("id").asString() + "/status-report", "{\"status\":\"ACSC\"}", "rail", 409);
        post("/v1/payments/" + q.get("id").asString() + "/status-report", "{\"status\":\"XXXX\"}", "rail", 422);
    }

    // ------------------------------------------------------------------ 3. approvals

    @Test
    @Order(4)
    void multiLevelFourEyesApprovals() throws Exception {
        JsonNode p = post("/v1/payments", outbound("20000.00", "Frank Supplier Ltd", EXT_IBAN), "maker", 201);
        String id = p.get("id").asString();
        assertThat(p.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(get("/v1/payments/" + id + "/approvals").get("data")).hasSize(1);
        JsonNode own = postRaw("/v1/payments/" + id + "/approve", "{}", "maker", 422);
        assertThat(own.get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode approved = post("/v1/payments/" + id + "/approve", "{\"comment\":\"ok\"}", "checker1", 200);
        assertThat(approved.get("status").asString()).isEqualTo("SUBMITTED");

        JsonNode big = post("/v1/payments", outbound("120000.00", "Grace Holdings", EXT_IBAN), "maker", 201);
        String bigId = big.get("id").asString();
        JsonNode levels = get("/v1/payments/" + bigId + "/approvals").get("data");
        assertThat(levels).hasSize(2);
        assertThat(post("/v1/payments/" + bigId + "/approve", "{}", "checker1", 200).get("status").asString())
                .isEqualTo("PENDING_APPROVAL");
        assertThat(postRaw("/v1/payments/" + bigId + "/approve", "{}", "checker1", 422).get("code").asString())
                .isEqualTo("duplicate_approver");
        assertThat(post("/v1/payments/" + bigId + "/approve", "{}", "checker2", 200).get("status").asString())
                .isEqualTo("PENDING_APPROVAL");
        assertThat(post("/v1/payments/" + bigId + "/approve", "{}", "checker3", 200).get("status").asString())
                .isEqualTo("SUBMITTED");

        JsonNode third = post("/v1/payments", outbound("150000.00", "Hank Imports", EXT_IBAN), "maker", 201);
        String thirdId = third.get("id").asString();
        String level2 = null;
        for (JsonNode l : get("/v1/payments/" + thirdId + "/approvals").get("data")) {
            if (l.get("approver_level").asInt() == 2) {
                level2 = l.get("id").asString();
            }
        }
        assertThat(postRaw("/v1/payment-approvals/" + level2 + "/approve", "{}", "checker1", 409).get("code").asString())
                .isEqualTo("approval_out_of_order");
        JsonNode rejected = post("/v1/payments/" + thirdId + "/reject", "{\"reason\":\"Not budgeted\"}", "checker1", 200);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejection_code").asString()).isEqualTo("AG01");
        post("/v1/payments/" + thirdId + "/approve", "{}", "checker2", 409);
    }

    // ------------------------------------------------------------------ 4. screening and repair

    @Test
    @Order(5)
    void sanctionsHitsAreHeldOrRejected() throws Exception {
        JsonNode held = post("/v1/payments", outbound("75.00", "Sanctioned Trading Ltd", EXT_IBAN), "maker", 201);
        assertThat(held.get("status").asString()).isEqualTo("SCREENING");
        JsonNode released = post("/v1/payments/" + held.get("id").asString() + "/screening-decision",
                "{\"status\":\"FALSE_POSITIVE\",\"reason\":\"Different entity\"}", "compliance", 200);
        assertThat(released.get("status").asString()).isEqualTo("SUBMITTED");
        JsonNode blocked = post("/v1/payments", outbound("75.00", "Blocked Entity Corp", EXT_IBAN), "maker", 201);
        assertThat(blocked.get("status").asString()).isEqualTo("REJECTED");
        assertThat(blocked.get("rejection_code").asString()).isEqualTo("RR04");
        post("/v1/payments/" + blocked.get("id").asString() + "/screening-decision", "{\"status\":\"CLEAR\"}", "compliance", 409);
    }

    @Test
    @Order(6)
    void repairQueueSuggestsFixesAndResubmits() throws Exception {
        JsonNode p = post("/v1/payments", outbound("55.00", "Zoë Ångström", EXT_IBAN), "maker", 201);
        String id = p.get("id").asString();
        assertThat(p.get("status").asString()).isEqualTo("IN_REPAIR");
        JsonNode repair = get("/v1/payment-repairs?payment_id=" + id).get("data").get(0);
        assertThat(repair.get("error_code").asString()).isEqualTo("NAME_CHARSET");
        assertThat(repair.get("suggested_fix").get("creditor_name").asString()).isEqualTo("Zoe Angstrom");
        String rid = repair.get("id").asString();
        assertThat(post("/v1/payment-repairs/" + rid + "/assign", "{\"assigned_to\":\"ops-1\"}", "ops", 200)
                .get("status").asString()).isEqualTo("IN_REPAIR");
        postRaw("/v1/payment-repairs/" + rid + "/resubmit", "{\"corrections\":{\"amount\":\"1.00\"}}", "ops", 422);
        JsonNode resubmitted = post("/v1/payment-repairs/" + rid + "/resubmit",
                "{\"corrections\":{\"creditor_name\":\"Zoe Angstrom\"}}", "ops", 200);
        assertThat(resubmitted.get("status").asString()).isEqualTo("RESUBMITTED");
        assertThat(get("/v1/payments/" + id).get("status").asString()).isEqualTo("SUBMITTED");

        JsonNode second = post("/v1/payments", outbound("10.00", "Δημήτρης", EXT_IBAN), "maker", 201);
        String rid2 = get("/v1/payment-repairs?payment_id=" + second.get("id").asString()).get("data").get(0).get("id").asString();
        post("/v1/payment-repairs/" + rid2 + "/cancel", "{\"reason\":\"Customer withdrew\"}", "ops", 200);
        assertThat(get("/v1/payments/" + second.get("id").asString()).get("status").asString()).isEqualTo("CANCELLED");
        post("/v1/payment-repairs/" + rid2 + "/cancel", "{\"reason\":\"again\"}", "ops", 409);
    }

    @Test
    @Order(7)
    void futureDatedPaymentsWaitAndCanBeCancelledOrExecutedWhenDue() throws Exception {
        String future = LocalDate.now(ZoneOffset.UTC).plusDays(3).toString();
        JsonNode p = post("/v1/payments", """
                {"payment_type":"DOMESTIC","direction":"OUTBOUND","channel":"WEB","debtor_account_id":"%s",
                 "creditor_name":"Ivy Lane","creditor_account_id_type":"IBAN","creditor_account_number":"%s",
                 "amount":"20.00","currency":"GBP","requested_execution_date":"%s"}""".formatted(a, EXT_IBAN, future), "maker", 201);
        assertThat(p.get("status").asString()).isEqualTo("APPROVED");
        assertThat(p.hasNonNull("hold_id")).isFalse();
        assertThat(post("/v1/payments/" + p.get("id").asString() + "/cancel", "{\"reason\":\"Changed mind\"}", "maker", 200)
                .get("status").asString()).isEqualTo("CANCELLED");
        post("/v1/payments/" + p.get("id").asString() + "/cancel", "{}", "maker", 409);

        JsonNode later = post("/v1/payments", """
                {"payment_type":"DOMESTIC","direction":"OUTBOUND","channel":"WEB","debtor_account_id":"%s",
                 "creditor_name":"Jack Lane","creditor_account_id_type":"IBAN","creditor_account_number":"%s",
                 "amount":"21.00","currency":"GBP","requested_execution_date":"%s"}""".formatted(a, EXT_IBAN, future), "maker", 201);
        TenantContext.callAs(UUID.fromString(TENANT), "test", () -> jdbc.sql(
                "update payment set requested_execution_date = current_date, value_date = current_date where id = ?::uuid")
                .param(later.get("id").asString()).update());
        JsonNode due = post("/v1/payments/execute-due", null, "scheduler", 200);
        assertThat(due.get("submitted").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(get("/v1/payments/" + later.get("id").asString()).get("status").asString()).isEqualTo("SUBMITTED");
    }

    // ------------------------------------------------------------------ 5. inbound, returns, recalls, refunds

    @Test
    @Order(8)
    void inboundCreditsReturnsRefundsAndRecalls() throws Exception {
        JsonNode in = post("/v1/payments/inbound", inbound("INB-1", "75.00", B_IBAN), "rail:FPS", 200);
        assertThat(in.get("status").asString()).isEqualTo("SETTLED");
        assertThat(in.get("direction").asString()).isEqualTo("INBOUND");
        assertThat(in.get("creditor_name").asString()).isEqualTo("Bob Jones");
        assertThat(bank.balance(b)).isEqualByComparingTo("1325.00");
        JsonNode dup = post("/v1/payments/inbound", inbound("INB-1", "75.00", B_IBAN), "rail:FPS", 200);
        assertThat(dup.get("id").asString()).isEqualTo(in.get("id").asString());
        assertThat(bank.balance(b)).isEqualByComparingTo("1325.00");
        JsonNode unknown = post("/v1/payments/inbound", inbound("INB-X", "5.00", EXT_IBAN), "rail:FPS", 200);
        assertThat(unknown.get("status").asString()).isEqualTo("REJECTED");
        assertThat(unknown.get("rejection_code").asString()).isEqualTo("AC01");

        JsonNode ret = post("/v1/payments/" + in.get("id").asString() + "/return",
                "{\"reason_code\":\"MD06\",\"reason\":\"Customer disputes\"}", "ops", 200);
        assertThat(ret.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(ret.get("direction").asString()).isEqualTo("OUTBOUND");
        assertThat(bank.balance(b)).isEqualByComparingTo("1250.00");
        assertThat(get("/v1/payments/" + in.get("id").asString()).get("status").asString()).isEqualTo("RETURNED");
        post("/v1/payments/" + in.get("id").asString() + "/return", "{\"reason_code\":\"MD06\"}", "ops", 409);

        JsonNode in2 = post("/v1/payments/inbound", inbound("INB-2", "60.00", B_IBAN), "rail:FPS", 200);
        JsonNode refund = post("/v1/payments/" + in2.get("id").asString() + "/refund", "{\"amount\":\"20.00\"}", "bob", 200);
        assertThat(refund.get("payment_type").asString()).isEqualTo("REFUND");
        assertThat(refund.get("original_payment_id").asString()).isEqualTo(in2.get("id").asString());
        assertThat(refund.get("creditor_account_number").asString()).isEqualTo(EXT_IBAN);
        assertThat(refund.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(postRaw("/v1/payments/" + in2.get("id").asString() + "/refund", "{\"amount\":\"50.00\"}", "bob", 422)
                .get("code").asString()).isEqualTo("invalid_refund_amount");

        JsonNode in3 = post("/v1/payments/inbound", inbound("INB-3", "33.00", B_IBAN), "rail:FPS", 200);
        JsonNode recall = post("/v1/payment-recalls/inbound",
                "{\"original_end_to_end_id\":\"INB-3\",\"reason_code\":\"DUPL\"}", "rail:FPS", 200);
        assertThat(recall.get("status").asString()).isEqualTo("REQUESTED");
        post("/v1/payment-recalls/" + recall.get("id").asString() + "/reject", "{\"response_code\":\"ZZZZ\"}", "ops", 422);
        JsonNode accepted = post("/v1/payment-recalls/" + recall.get("id").asString() + "/accept", "{}", "ops", 200);
        assertThat(accepted.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(accepted.hasNonNull("return_id")).isTrue();
        assertThat(get("/v1/payments/" + in3.get("id").asString()).get("status").asString()).isEqualTo("RETURNED");
    }

    @Test
    @Order(9)
    void outboundRecallAcceptedCreditsTheDebtorBack() throws Exception {
        BigDecimal before = bank.balance(a);
        JsonNode recall = post("/v1/payments/" + outboundSettled + "/recall", "{\"reason_code\":\"DUPL\"}", "maker", 200);
        assertThat(recall.get("status").asString()).isEqualTo("PENDING");
        assertThat(postRaw("/v1/payments/" + outboundSettled + "/recall", "{\"reason_code\":\"DUPL\"}", "maker", 409)
                .get("code").asString()).isEqualTo("recall_in_progress");
        JsonNode resolved = post("/v1/payment-recalls/" + recall.get("id").asString() + "/resolve",
                "{\"accepted\":true,\"response_code\":\"CNCL\"}", "rail", 200);
        assertThat(resolved.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(bank.balance(a)).isEqualByComparingTo(before.add(new BigDecimal("100.00")));
        assertThat(get("/v1/payments/" + outboundSettled).get("status").asString()).isEqualTo("RETURNED");
        JsonNode returns = get("/v1/payment-returns?payment_id=" + outboundSettled);
        assertThat(returns.get("data").get(0).get("direction").asString()).isEqualTo("INBOUND");
    }

    @Test
    @Order(10)
    void validationErrors() throws Exception {
        String badIban = EXT_IBAN.substring(0, 21) + ((EXT_IBAN.charAt(21) - '0' + 1) % 10);
        assertThat(postRaw("/v1/payments", outbound("10.00", "Kim", badIban), "maker", 422).get("code").asString())
                .isEqualTo("invalid_iban_checksum");
        assertThat(postRaw("/v1/payments", """
                {"payment_type":"DOMESTIC","direction":"OUTBOUND","channel":"WEB","debtor_account_id":"%s",
                 "creditor_name":"Kim","creditor_account_id_type":"IBAN","creditor_account_number":"%s",
                 "amount":"10.00","currency":"EUR"}""".formatted(a, EXT_IBAN), "maker", 422).get("code").asString())
                .isEqualTo("currency_mismatch");
        assertThat(postRaw("/v1/payments", outbound("10.001", "Kim", EXT_IBAN), "maker", 422).get("code").asString())
                .isEqualTo("invalid_amount_precision");
        assertThat(postRaw("/v1/payments", outbound("10.00", "Kim", EXT_IBAN).replace(a.toString(), UUID.randomUUID().toString()),
                "maker", 422).get("code").asString()).isEqualTo("debtor_account_not_found");
        HttpResponse<String> noKey = send("POST", "/v1/payments", outbound("10.00", "Kim", EXT_IBAN), null, "maker", TENANT);
        assertThat(noKey.statusCode()).isEqualTo(400);
        bank.setAccountStatus(c, "FROZEN");
        assertThat(postRaw("/v1/payments", outbound("10.00", "Kim", EXT_IBAN).replace(a.toString(), c.toString()), "carol", 422)
                .get("code").asString()).isEqualTo("debtor_account_not_active");
        bank.setAccountStatus(c, "ACTIVE");
    }

    // ------------------------------------------------------------------ 6. payees and aliases

    @Test
    @Order(11)
    void payeesWithConfirmationOfPayee() throws Exception {
        JsonNode payee = post("/v1/payees", """
                {"customer_id":"%s","name":"Mr Bob Jones","account_id_type":"IBAN","account_number":"%s","country":"GB",
                 "trusted":true}""".formatted(ALICE, B_IBAN.toLowerCase()), "alice", 201);
        assertThat(payee.get("status").asString()).isEqualTo("PENDING_VERIFICATION");
        assertThat(payee.get("trusted").asBoolean()).isFalse();
        assertThat(payee.get("account_number").asString()).isEqualTo(B_IBAN);
        String pid = payee.get("id").asString();
        postRaw("/v1/payees", """
                {"customer_id":"%s","name":"Bob","account_id_type":"IBAN","account_number":"%s","country":"GB"}"""
                .formatted(ALICE, B_IBAN), "alice", 409);
        JsonNode v = post("/v1/payees/" + pid + "/verify", null, "alice", 200);
        assertThat(v.get("result").asString()).isEqualTo("MATCH");
        assertThat(v.get("provider").asString()).isEqualTo("ON_US");
        assertThat(get("/v1/payees/" + pid).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(post("/v1/payees/" + pid + "/trust", null, "alice", 200).get("trusted").asBoolean()).isTrue();

        JsonNode carol = post("/v1/payees", """
                {"customer_id":"%s","name":"Carol Whyte","account_id_type":"IBAN","account_number":"%s","country":"GB"}"""
                .formatted(ALICE, C_IBAN), "alice", 201);
        JsonNode close = post("/v1/payees/" + carol.get("id").asString() + "/verify", null, "alice", 200);
        assertThat(close.get("result").asString()).isEqualTo("CLOSE_MATCH");
        assertThat(close.get("matched_name").asString()).isEqualTo("Carol White");
        assertThat(postRaw("/v1/payees/" + carol.get("id").asString() + "/trust", null, "alice", 409).get("code").asString())
                .isEqualTo("payee_not_verified");
        String version = get("/v1/payees/" + carol.get("id").asString()).get("row_version").asString();
        HttpResponse<String> stale = send("PATCH", "/v1/payees/" + carol.get("id").asString(), "{\"name\":\"Carol White\"}",
                "\"0\"", "alice", TENANT);
        assertThat(stale.statusCode()).isEqualTo(409);
        HttpResponse<String> patched = send("PATCH", "/v1/payees/" + carol.get("id").asString(), "{\"name\":\"Carol White\"}",
                "\"" + version + "\"", "alice", TENANT);
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        assertThat(post("/v1/payees/" + carol.get("id").asString() + "/verify", null, "alice", 200).get("result").asString())
                .isEqualTo("MATCH");

        JsonNode none = post("/v1/payee-verifications", """
                {"name_provided":"Totally Different","account_id_type":"IBAN","account_number":"%s"}""".formatted(B_IBAN), "alice", 201);
        assertThat(none.get("result").asString()).isEqualTo("NO_MATCH");
        JsonNode offUs = post("/v1/payee-verifications", """
                {"name_provided":"Someone","account_id_type":"IBAN","account_number":"%s"}""".formatted(EXT_IBAN), "alice", 201);
        assertThat(offUs.get("result").asString()).isEqualTo("NOT_AVAILABLE");
        assertThat(offUs.get("provider").asString()).isEqualTo("UK_COP");

        JsonNode viaPayee = post("/v1/payments", """
                {"payment_type":"DOMESTIC","direction":"OUTBOUND","channel":"WEB","debtor_account_id":"%s","payee_id":"%s",
                 "amount":"5.00","currency":"GBP"}""".formatted(a, pid), "alice", 201);
        assertThat(viaPayee.get("creditor_name").asString()).isEqualTo("Mr Bob Jones");
        post("/v1/payees/" + pid + "/block", "{\"reason\":\"Fraud report\"}", "alice", 200);
        assertThat(postRaw("/v1/payments", """
                {"payment_type":"DOMESTIC","direction":"OUTBOUND","channel":"WEB","debtor_account_id":"%s","payee_id":"%s",
                 "amount":"5.00","currency":"GBP"}""".formatted(a, pid), "alice", 422).get("code").asString()).isEqualTo("payee_blocked");
        assertThat(post("/v1/payees/" + pid + "/unblock", "{}", "alice", 200).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    @Order(12)
    void aliasesNeedOtpOwnershipAndResolveMasked() throws Exception {
        assertThat(postRaw("/v1/payee-aliases", """
                {"alias_type":"PHONE","alias_value":"07700 900123","customer_id":"%s","account_id":"%s"}""".formatted(ALICE, a),
                "alice", 422).get("code").asString()).isEqualTo("invalid_alias");
        JsonNode alias = post("/v1/payee-aliases", """
                {"alias_type":"PHONE","alias_value":"+44 7700 900123","customer_id":"%s","account_id":"%s"}""".formatted(ALICE, a),
                "alice", 201);
        assertThat(alias.get("status").asString()).isEqualTo("PENDING");
        assertThat(alias.get("alias_value").asString()).isEqualTo("+447700900123");
        assertThat(alias.has("otp_hash")).isFalse();
        String otp = bank.calls("notification-service", "/v1/notifications/send-templated").getLast()
                .body().get("variables").get("otp").asString();
        String wrong = String.format("%06d", (Integer.parseInt(otp) + 1) % 1_000_000);
        String aid = alias.get("id").asString();
        assertThat(postRaw("/v1/payee-aliases/" + aid + "/verify-ownership", "{\"otp\":\"" + wrong + "\"}", "alice", 422)
                .get("code").asString()).isEqualTo("invalid_otp");
        assertThat(post("/v1/payee-aliases/" + aid + "/verify-ownership", "{\"otp\":\"" + otp + "\"}", "alice", 200)
                .get("status").asString()).isEqualTo("ACTIVE");
        JsonNode resolved = post("/v1/payee-aliases/resolve", "{\"alias_type\":\"PHONE\",\"alias_value\":\"00447700900123\"}", "bob", 200);
        assertThat(resolved.get("display_name").asString()).isEqualTo("A**** S****");
        assertThat(resolved.hasNonNull("customer_id")).isFalse();
        assertThat(resolved.get("account_id").asString()).isEqualTo(a.toString());
        postRaw("/v1/payee-aliases", """
                {"alias_type":"PHONE","alias_value":"+447700900123","customer_id":"%s","account_id":"%s"}""".formatted(BOB, b),
                "bob", 409);
        postRaw("/v1/payee-aliases/resolve", "{\"alias_type\":\"EMAIL\",\"alias_value\":\"nobody@example.com\"}", "bob", 404);

        JsonNode email = post("/v1/payee-aliases", """
                {"alias_type":"EMAIL","alias_value":"Bob.Jones@Example.com","customer_id":"%s","account_id":"%s"}""".formatted(BOB, b),
                "bob", 201);
        assertThat(email.get("alias_value").asString()).isEqualTo("bob.jones@example.com");
        JsonNode porting = post("/v1/payee-aliases/" + email.get("id").asString() + "/port",
                "{\"donor_institution\":\"Other Bank plc\"}", "bob", 200);
        assertThat(porting.get("status").asString()).isEqualTo("PORTING");
        assertThat(post("/v1/payee-aliases/" + email.get("id").asString() + "/complete-port", null, "ops", 200)
                .get("status").asString()).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ 7. bulk payments

    @Test
    @Order(13)
    void bulkCsvUploadValidationApprovalExecutionAndReport() throws Exception {
        String csv = "creditor_name,creditor_account_number,amount,currency,end_to_end_id,remittance\n"
                + "Ann Lee," + EXT_IBAN + ",100.00,GBP,BLK-1,Salary October\n"
                + "\"Kim, Ben\"," + DE_IBAN + ",250.50,GBP,BLK-2,Salary October\n"
                + "Bad Line,GB00XXXX,10.00,GBP,BLK-3,Salary October\n";
        JsonNode bulk = post("/v1/bulk-payments/upload", json.writeValueAsString(json.createObjectNode()
                .put("content", csv).put("file_format", "CSV").put("customer_id", ALICE.toString())
                .put("debtor_account_id", a.toString()).put("name", "Payroll October")), "maker", 201);
        String id = bulk.get("id").asString();
        assertThat(bulk.get("status").asString()).isEqualTo("VALIDATED");
        assertThat(bulk.get("item_count").asInt()).isEqualTo(3);
        assertThat(bulk.get("valid_count").asInt()).isEqualTo(2);
        assertThat(bulk.get("failed_count").asInt()).isEqualTo(1);
        JsonNode items = get("/v1/bulk-payments/" + id + "/items");
        assertThat(items.get("data").toString()).contains("invalid_iban").contains("Kim, Ben");

        assertThat(post("/v1/bulk-payments/" + id + "/submit", null, "maker", 200).get("status").asString())
                .isEqualTo("PENDING_APPROVAL");
        assertThat(postRaw("/v1/bulk-payments/" + id + "/approve", "{}", "maker", 422).get("code").asString())
                .isEqualTo("four_eyes_violation");
        BigDecimal pendingBefore = bank.pending(a);
        JsonNode approved = post("/v1/bulk-payments/" + id + "/approve", "{\"comment\":\"payroll ok\"}", "checker1", 200);
        assertThat(approved.get("status").asString()).isEqualTo("PROCESSING");
        assertThat(approved.hasNonNull("hold_id")).isTrue();
        assertThat(bank.pending(a).subtract(pendingBefore)).isEqualByComparingTo("350.50");

        JsonNode payments = get("/v1/payments?bulk_payment_id=" + id);
        assertThat(payments.get("data")).hasSize(2);
        for (JsonNode p : payments.get("data")) {
            assertThat(p.get("status").asString()).isEqualTo("SUBMITTED");
            assertThat(p.get("end_to_end_id").asString()).startsWith("BLK-");
            post("/v1/payments/" + p.get("id").asString() + "/status-report", "{\"status\":\"ACSC\"}", "rail", 200);
        }
        JsonNode done = get("/v1/bulk-payments/" + id);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("settled_count").asInt()).isEqualTo(2);
        assertThat(bank.pending(a)).isEqualByComparingTo(pendingBefore);

        HttpResponse<String> report = send("GET", "/v1/bulk-payments/" + id + "/report", null, null, "ops", TENANT);
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(report.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(report.body().lines().count()).isEqualTo(5);
        assertThat(report.body()).contains("SETTLED").contains("INVALID").contains("\"Kim, Ben\"");

        String pain = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.09"><CstmrCdtTrfInitn>
                  <GrpHdr><MsgId>PAIN-1</MsgId><NbOfTxs>1</NbOfTxs><CtrlSum>99.00</CtrlSum></GrpHdr>
                  <PmtInf><PmtInfId>P1</PmtInfId><BtchBookg>true</BtchBookg>
                    <CdtTrfTxInf><PmtId><EndToEndId>PX-1</EndToEndId></PmtId><Amt><InstdAmt Ccy="GBP">12.00</InstdAmt></Amt>
                      <Cdtr><Nm>Lou Reed</Nm></Cdtr><CdtrAcct><Id><IBAN>%s</IBAN></Id></CdtrAcct></CdtTrfTxInf>
                  </PmtInf></CstmrCdtTrfInitn></Document>""".formatted(EXT_IBAN);
        assertThat(postRaw("/v1/bulk-payments/upload", json.writeValueAsString(json.createObjectNode()
                .put("content", pain).put("file_format", "PAIN_001").put("customer_id", ALICE.toString())
                .put("debtor_account_id", a.toString())), "maker", 422).get("code").asString()).isEqualTo("control_sum_mismatch");
        JsonNode ok = post("/v1/bulk-payments/upload", json.writeValueAsString(json.createObjectNode()
                .put("content", pain.replace("99.00", "12.00")).put("file_format", "PAIN_001").put("customer_id", ALICE.toString())
                .put("debtor_account_id", a.toString())), "maker", 201);
        assertThat(ok.get("name").asString()).isEqualTo("pain.001 PAIN-1");
        assertThat(ok.get("valid_count").asInt()).isEqualTo(1);
        assertThat(post("/v1/bulk-payments/" + ok.get("id").asString() + "/cancel", "{\"reason\":\"duplicate file\"}", "maker", 200)
                .get("status").asString()).isEqualTo("CANCELLED");
        post("/v1/bulk-payments/" + ok.get("id").asString() + "/submit", null, "maker", 409);
    }

    // ------------------------------------------------------------------ 8. mandates and direct debits

    @Test
    @Order(14)
    void mandatesCollectionsAndRefunds() throws Exception {
        JsonNode m = post("/v1/payment-mandates", """
                {"scheme":"SEPA_CORE","mandate_reference":"MNDT-001","direction":"INBOUND","debtor_account_id":"%s",
                 "debtor_name":"Alice Smith","debtor_account_number":"%s","creditor_name":"Utility Co",
                 "creditor_id":"GB98ZZZSDDBARC0000007495895","frequency":"MONTHLY","max_amount":"500.00","currency":"GBP"}"""
                .formatted(a, A_IBAN), "alice", 201);
        String mid = m.get("id").asString();
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        String collection = """
                {"mandate_id":"%s","amount":"%s","currency":"GBP","due_date":"%s"}""";
        assertThat(postRaw("/v1/direct-debit-collections", collection.formatted(mid, "45.00", today), "creditor", 422)
                .get("code").asString()).isEqualTo("mandate_not_active");
        assertThat(post("/v1/payment-mandates/" + mid + "/activate", null, "ops", 200).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(postRaw("/v1/direct-debit-collections", collection.formatted(mid, "600.00", today), "creditor", 422)
                .get("code").asString()).isEqualTo("amount_exceeds_mandate");
        JsonNode ddc = post("/v1/direct-debit-collections", collection.formatted(mid, "45.00", today), "creditor", 201);
        assertThat(ddc.get("sequence_type").asString()).isEqualTo("FRST");
        BigDecimal before = bank.balance(a);
        JsonNode collected = post("/v1/direct-debit-collections/" + ddc.get("id").asString() + "/collect", null, "scheduler", 200);
        assertThat(collected.get("status").asString()).isEqualTo("COLLECTED");
        assertThat(bank.balance(a)).isEqualByComparingTo(before.subtract(new BigDecimal("45.00")));
        assertThat(bank.journalsWithLine(DD_CLEARING)).hasSize(1);
        JsonNode payment = get("/v1/payments/" + collected.get("payment_id").asString());
        assertThat(payment.get("payment_type").asString()).isEqualTo("DIRECT_DEBIT");
        assertThat(payment.get("status").asString()).isEqualTo("SETTLED");
        post("/v1/direct-debit-collections/" + ddc.get("id").asString() + "/collect", null, "scheduler", 409);

        JsonNode refund = post("/v1/direct-debit-collections/" + ddc.get("id").asString() + "/refund",
                "{\"reason_code\":\"MD06\"}", "alice", 200);
        assertThat(refund.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(bank.balance(a)).isEqualByComparingTo(before);
        assertThat(get("/v1/direct-debit-collections/" + ddc.get("id").asString()).get("status").asString()).isEqualTo("RETURNED");

        post("/v1/payment-mandates/" + mid + "/suspend", "{\"reason\":\"customer request\"}", "ops", 200);
        postRaw("/v1/direct-debit-collections", collection.formatted(mid, "10.00", today), "creditor", 422);
        post("/v1/payment-mandates/" + mid + "/resume", null, "ops", 200);
        assertThat(get("/v1/payment-mandates/" + mid + "/collections").get("data")).hasSize(1);
        post("/v1/payment-mandates/" + mid + "/cancel", "{\"reason\":\"closed contract\"}", "ops", 200);
        post("/v1/payment-mandates/" + mid + "/resume", null, "ops", 409);
    }

    // ------------------------------------------------------------------ 9. request-to-pay, links, QR

    @Test
    @Order(15)
    void requestToPayIsPaidByBookTransfer() throws Exception {
        String expires = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString();
        JsonNode rtp = post("/v1/requests-to-pay", """
                {"requester_customer_id":"%s","requester_account_id":"%s","payer_customer_id":"%s","amount":"30.00",
                 "currency":"GBP","description":"Dinner","expires_at":"%s"}""".formatted(BOB, b, ALICE, expires), "bob", 201);
        assertThat(rtp.get("status").asString()).isEqualTo("SENT");
        BigDecimal bobBefore = bank.balance(b);
        assertThat(postRaw("/v1/requests-to-pay/" + rtp.get("id").asString() + "/accept",
                "{\"debtor_account_id\":\"%s\",\"amount\":\"10.00\"}".formatted(a), "alice", 422).get("code").asString())
                .isEqualTo("invalid_amount");
        JsonNode payment = post("/v1/requests-to-pay/" + rtp.get("id").asString() + "/accept",
                "{\"debtor_account_id\":\"%s\"}".formatted(a), "alice", 200);
        assertThat(payment.get("status").asString()).isEqualTo("SETTLED");
        assertThat(payment.get("payment_type").asString()).isEqualTo("REQUEST_TO_PAY");
        assertThat(bank.balance(b)).isEqualByComparingTo(bobBefore.add(new BigDecimal("30.00")));
        JsonNode paid = get("/v1/requests-to-pay/" + rtp.get("id").asString());
        assertThat(paid.get("status").asString()).isEqualTo("PAID");
        assertThat(paid.get("paid_payment_id").asString()).isEqualTo(payment.get("id").asString());
        post("/v1/requests-to-pay/" + rtp.get("id").asString() + "/accept", "{\"debtor_account_id\":\"%s\"}".formatted(a), "alice", 409);

        JsonNode other = post("/v1/requests-to-pay", """
                {"requester_customer_id":"%s","requester_account_id":"%s","amount":"5.00","currency":"GBP","expires_at":"%s"}"""
                .formatted(BOB, b, expires), "bob", 201);
        assertThat(post("/v1/requests-to-pay/" + other.get("id").asString() + "/decline", "{\"reason\":\"no\"}", "alice", 200)
                .get("status").asString()).isEqualTo("DECLINED");
    }

    @Test
    @Order(16)
    void paymentLinksArePaidOnce() throws Exception {
        JsonNode link = post("/v1/payment-links", """
                {"customer_id":"%s","credit_account_id":"%s","title":"Concert ticket","amount":"12.50","currency":"GBP",
                 "allowed_methods":["ACCOUNT","CARD"]}""".formatted(BOB, b), "bob", 201);
        assertThat(link.get("slug").asString()).hasSize(10);
        assertThat(link.get("url").asString()).endsWith("/l/" + link.get("slug").asString());
        String lid = link.get("id").asString();
        postRaw("/v1/payment-links/" + lid + "/pay", "{\"debtor_account_id\":\"%s\",\"amount\":\"10.00\"}".formatted(a), "alice", 422);
        JsonNode payment = post("/v1/payment-links/" + lid + "/pay", "{\"debtor_account_id\":\"%s\"}".formatted(a), "alice", 200);
        assertThat(payment.get("status").asString()).isEqualTo("SETTLED");
        JsonNode paid = get("/v1/payment-links/" + lid);
        assertThat(paid.get("status").asString()).isEqualTo("PAID");
        assertThat(paid.get("payment_count").asInt()).isEqualTo(1);
        assertThat(get("/v1/payment-links/" + lid + "/payments").get("data")).hasSize(1);
        assertThat(postRaw("/v1/payment-links/" + lid + "/pay", "{\"debtor_account_id\":\"%s\"}".formatted(a), "alice", 409)
                .get("code").asString()).isEqualTo("link_not_active");

        JsonNode open = post("/v1/payment-links", """
                {"credit_account_id":"%s","title":"Donations","currency":"GBP","min_amount":"1.00","max_amount":"50.00",
                 "reusable":true,"max_payments":2}""".formatted(b), "bob", 201);
        postRaw("/v1/payment-links/" + open.get("id").asString() + "/pay",
                "{\"debtor_account_id\":\"%s\",\"amount\":\"75.00\"}".formatted(a), "alice", 422);
        assertThat(post("/v1/payment-links/" + open.get("id").asString() + "/disable", "{}", "bob", 200).get("status").asString())
                .isEqualTo("DISABLED");
    }

    @Test
    @Order(17)
    void emvcoQrCodesAreGeneratedDecodedAndPaid() throws Exception {
        JsonNode qr = post("/v1/payment-qr-codes", """
                {"qr_type":"DYNAMIC","credit_account_id":"%s","scheme":"EMVCO","amount":"9.99","currency":"GBP",
                 "merchant_name":"Bob's Cafe","merchant_city":"London","country_code":"GB","reference":"ORDER-1",
                 "merchant_category_code":"5812"}""".formatted(b), "bob", 201);
        String payload = qr.get("payload").asString();
        assertThat(payload).startsWith("000201010212").contains("5303826").contains("54049.99").contains("5802GB");
        assertThat(payload.substring(payload.length() - 8, payload.length() - 4)).isEqualTo("6304");
        assertThat(EmvQr.crc16Hex(payload.substring(0, payload.length() - 4))).isEqualTo(payload.substring(payload.length() - 4));

        JsonNode decoded = post("/v1/payment-qr-codes/decode", json.writeValueAsString(json.createObjectNode().put("payload", payload)),
                "alice", 200);
        assertThat(decoded.get("crc_valid").asBoolean()).isTrue();
        assertThat(decoded.get("currency").asString()).isEqualTo("GBP");
        assertThat(decoded.get("amount").asString()).isEqualTo("9.99");
        assertThat(decoded.get("merchant_name").asString()).isEqualTo("Bob's Cafe");
        assertThat(decoded.get("reference").asString()).isEqualTo("ORDER-1");
        assertThat(decoded.get("payable").asBoolean()).isTrue();
        assertThat(decoded.get("payment_qr_code_id").asString()).isEqualTo(qr.get("id").asString());
        String tampered = payload.replace("54049.99", "54049.90");
        assertThat(postRaw("/v1/payment-qr-codes/decode", json.writeValueAsString(json.createObjectNode().put("payload", tampered)),
                "alice", 422).get("code").asString()).isEqualTo("invalid_qr_crc");

        JsonNode payment = post("/v1/payment-qr-codes/" + qr.get("id").asString() + "/pay",
                "{\"debtor_account_id\":\"%s\"}".formatted(a), "alice", 200);
        assertThat(payment.get("status").asString()).isEqualTo("SETTLED");
        assertThat(payment.get("payment_type").asString()).isEqualTo("QR_PAYMENT");
        assertThat(get("/v1/payment-qr-codes/" + qr.get("id").asString()).get("status").asString()).isEqualTo("USED");
        post("/v1/payment-qr-codes/" + qr.get("id").asString() + "/pay", "{\"debtor_account_id\":\"%s\"}".formatted(a), "alice", 409);
        JsonNode after = post("/v1/payment-qr-codes/decode", json.writeValueAsString(json.createObjectNode().put("payload", payload)),
                "alice", 200);
        assertThat(after.get("payable").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ 10. isolation and events

    @Test
    @Order(18)
    void tenantIsolationAndEvents() throws Exception {
        String any = get("/v1/payments?limit=1").get("data").get(0).get("id").asString();
        HttpResponse<String> other = send("GET", "/v1/payments/" + any, null, null, "x", OTHER_TENANT);
        assertThat(other.statusCode()).isEqualTo(404);
        HttpResponse<String> list = send("GET", "/v1/payments", null, null, "x", OTHER_TENANT);
        assertThat(json.readTree(list.body()).get("data")).isEmpty();

        List<String> types = TenantContext.callAs(UUID.fromString(TENANT), "test", () ->
                jdbc.sql("select distinct event_type from outbox").query(String.class).list());
        assertThat(types).contains("payments.payment.initiated", "payments.payment.settled", "payments.payment.submitted",
                "payments.payment.received", "payments.payment.returned", "payments.payment.pending_approval",
                "payments.payment_approval.decided", "payments.payment_repair.opened", "payments.payee.verified",
                "payments.payee_alias.verified", "payments.bulk_payment.completed", "payments.direct_debit_collection.collected",
                "payments.request_to_pay.paid", "payments.payment_link.paid", "payments.payment_qr_code.paid",
                "payments.payment_recall.resolved", "payments.payment_return.completed");
        String aliasEvents = TenantContext.callAs(UUID.fromString(TENANT), "test", () ->
                jdbc.sql("select string_agg(payload::text, ',') from outbox where aggregate_type = 'payee_alias'")
                        .query(String.class).single());
        assertThat(aliasEvents).doesNotContain("otp_hash");
    }

    // ------------------------------------------------------------------ helpers

    private String outbound(String amount, String name, String iban) {
        return """
                {"payment_type":"DOMESTIC","direction":"OUTBOUND","channel":"WEB","debtor_account_id":"%s",
                 "creditor_name":"%s","creditor_account_id_type":"IBAN","creditor_account_number":"%s",
                 "amount":"%s","currency":"GBP","remittance_unstructured":"Invoice 42"}""".formatted(a, name, iban, amount);
    }

    private String inbound(String e2e, String amount, String creditorIban) {
        return """
                {"rail_code":"FPS","end_to_end_id":"%s","amount":"%s","currency":"GBP","debtor_name":"Dave Payer",
                 "debtor_account_number":"%s","debtor_agent_bic":"NWBKGB2LXXX","creditor_account_number":"%s",
                 "remittance_unstructured":"Thanks"}""".formatted(e2e, amount, EXT_IBAN, creditorIban);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), user, TENANT);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private JsonNode postRaw(String path, String body, String user, int expected) throws Exception {
        return post(path, body, user, expected);
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, "ops", TENANT);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String user, String tenant)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path)))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if ("PATCH".equals(method)) {
            b.header("If-Match", idemKey == null ? "\"1\"" : idemKey);
        } else if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
