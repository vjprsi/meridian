package io.meridian.disbursement;

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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
 * disbursement-service end to end over HTTP against PostgreSQL 18 (RLS enforced) with wallet-service stubbed behind a
 * mocked {@link ServiceClient} ({@link FakeWalletService}): program lifecycle, CSV upload, beneficiary validation
 * (duplicates, name match, tier, cap, missing / dormant wallets), two-level four-eyes approval, execution as wallet
 * payouts from the funding wallet, partial failure and retry, rejection, cancellation, report and tenant isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.disbursement.default-calling-code=254"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DisbursementFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000d5001";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000d5002";

    static final String FILE = """
            msisdn,amount,full_name,national_id,beneficiary_reference
            +254700000001,1000,John Kamau,ID1,R1
            +254700000002,1500.50,Achieng Otieno,ID2,R2
            +254700000003,700,Mary Wanjiku,ID3,R3
            +254700000001,500,John Kamau,ID9,R9
            +254700000004,300,Dormant Person,ID4,R4
            +254700000009,300,Ghost Person,ID5,R5
            +254700000005,300,Low Tier,ID6,R6
            +254700000006,9000,Big Amount,ID7,R7
            bad-number,100,X Y,ID8,R8
            +254700000007,250,Grace Akinyi,ID2,R10
            0700000008,400,Faith Wambui,ID11,R11
            """;

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
    UUID fund;
    UUID usdFund;
    UUID john;
    UUID achieng;
    UUID faith;
    String program;
    String batch;

    @BeforeAll
    void setUp() {
        wallets = new FakeWalletService(json);
        fund = wallets.wallet("+254799999999", "Ministry of Social Protection", "KES", "ACTIVE", 3, "10000");
        usdFund = wallets.wallet(null, "USD Fund", "USD", "ACTIVE", 3, "10000");
        john = wallets.wallet("+254700000001", "John Kamau", "KES", "ACTIVE", 1, "0");
        achieng = wallets.wallet("+254700000002", "Achieng Otienno", "KES", "ACTIVE", 2, "0");
        wallets.wallet("+254700000003", "Peter Njoroge", "KES", "ACTIVE", 1, "0");
        wallets.wallet("+254700000004", "Dormant Person", "KES", "DORMANT", 1, "0");
        wallets.wallet("+254700000005", "Low Tier", "KES", "ACTIVE", 0, "0");
        wallets.wallet("+254700000007", "Grace Akinyi", "KES", "ACTIVE", 1, "0");
        faith = wallets.wallet("+254700000008", "Faith W. Wambui", "KES", "ACTIVE", 1, "0");
    }

    /** Mockito beans are reset after every test, so the stubs are re-attached to the same fake each time. */
    @BeforeEach
    void stub() {
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> wallets.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        when(serviceClient.get(anyString(), anyString()))
                .thenAnswer(i -> wallets.get(i.getArgument(0), i.getArgument(1)));
    }

    // ------------------------------------------------------------------ 1. programs

    @Test
    @Order(1)
    void programCreationValidationAndActivation() throws Exception {
        HttpResponse<String> bad = send("POST", "/v1/disbursement-programs", programBody("SCT-BAD", fund, 7, false, "KES"),
                key(), "maker", TENANT);
        assertThat(bad.statusCode()).isEqualTo(422);
        assertThat(bad.body()).contains("invalid_approval_levels");

        JsonNode p = post("/v1/disbursement-programs", programBody("SCT-2026", fund, 2, false, "KES"), "maker", 201);
        program = p.get("id").asString();
        assertThat(p.get("status").asString()).isEqualTo("DRAFT");
        assertThat(send("POST", "/v1/disbursement-programs", programBody("SCT-2026", fund, 2, false, "KES"), key(), "maker", TENANT)
                .statusCode()).isEqualTo(409);

        // a batch needs an active program
        HttpResponse<String> early = send("POST", "/v1/disbursement-batches", batchBody(program), key(), "maker", TENANT);
        assertThat(early.statusCode()).isEqualTo(422);
        assertThat(early.body()).contains("program_not_active");

        // funding wallet in another currency is refused
        String usd = post("/v1/disbursement-programs", programBody("SCT-USD", usdFund, 1, false, "KES"), "maker", 201).get("id").asString();
        HttpResponse<String> mismatch = send("POST", "/v1/disbursement-programs/" + usd + "/activate", null, key(), "ops", TENANT);
        assertThat(mismatch.statusCode()).isEqualTo(422);
        assertThat(mismatch.body()).contains("currency_mismatch");

        String k = key();
        HttpResponse<String> activated = send("POST", "/v1/disbursement-programs/" + program + "/activate", null, k, "ops", TENANT);
        assertThat(activated.statusCode()).isEqualTo(200);
        assertThat(json.readTree(activated.body()).get("status").asString()).isEqualTo("ACTIVE");
        HttpResponse<String> replay = send("POST", "/v1/disbursement-programs/" + program + "/activate", null, k, "ops", TENANT);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(send("POST", "/v1/disbursement-programs/" + program + "/activate", null, key(), "ops", TENANT).statusCode())
                .isEqualTo(409);
        assertThat(events("wallets.disbursement_program.status_changed", program)).isEqualTo(1);

        HttpResponse<String> patch = send("PATCH", "/v1/disbursement-programs/" + program, "{\"currency\":\"USD\"}", null, "ops", TENANT);
        assertThat(patch.statusCode()).isEqualTo(422);
        assertThat(patch.body()).contains("field_not_updatable");
    }

    // ------------------------------------------------------------------ 2. upload and validation

    @Test
    @Order(2)
    void uploadParsesAndValidationClassifiesEveryLine() throws Exception {
        JsonNode b = post("/v1/disbursement-batches", batchBody(program), "maker", 201);
        batch = b.get("id").asString();
        assertThat(b.get("batch_reference").asString()).startsWith("DB");
        assertThat(b.get("approvals_required").asInt()).isEqualTo(2);

        HttpResponse<String> empty = send("POST", "/v1/disbursement-batches/" + batch + "/validate", null, key(), "maker", TENANT);
        assertThat(empty.statusCode()).isEqualTo(422);
        assertThat(empty.body()).contains("empty_batch");
        assertThat(send("POST", "/v1/disbursement-batches/" + batch + "/submit", null, key(), "maker", TENANT).statusCode())
                .isEqualTo(409);

        JsonNode up = post("/v1/disbursement-batches/" + batch + "/upload", uploadBody(FILE, "sct-oct.csv"), "maker", 200);
        assertThat(up.get("total_count").asInt()).isEqualTo(11);
        assertThat(up.get("invalid_count").asInt()).isEqualTo(1); // the unparseable msisdn row
        assertThat(up.get("file_sha256").asString()).hasSize(64);
        assertThat(up.get("status").asString()).isEqualTo("DRAFT");

        JsonNode v = post("/v1/disbursement-batches/" + batch + "/validate", null, "maker", 200);
        assertThat(v.get("status").asString()).isEqualTo("VALIDATED");
        assertThat(v.get("valid_count").asInt()).isEqualTo(3);
        assertThat(dec(v, "valid_amount")).isEqualByComparingTo("2900.50");
        assertThat(v.get("duplicate_count").asInt()).isEqualTo(2);
        assertThat(v.get("invalid_count").asInt()).isEqualTo(6);
        assertThat(events("wallets.disbursement_batch.validated", batch)).isEqualTo(1);

        List<JsonNode> lines = lines(batch);
        assertThat(status(lines, 1)).isEqualTo("VALID");
        assertThat(status(lines, 2)).isEqualTo("VALID"); // one-letter typo tolerated
        assertThat(status(lines, 3)).isEqualTo("NAME_MISMATCH");
        assertThat(line(lines, 3).get("matched_name").asString()).isEqualTo("Peter Njoroge");
        assertThat(status(lines, 4)).isEqualTo("DUPLICATE");
        assertThat(line(lines, 4).get("validation_message").asString()).contains("line 1");
        assertThat(line(lines, 5).get("validation_message").asString()).contains("DORMANT");
        assertThat(line(lines, 6).get("validation_message").asString()).contains("No wallet");
        assertThat(line(lines, 7).get("validation_message").asString()).contains("tier");
        assertThat(line(lines, 8).get("validation_message").asString()).contains("cap");
        assertThat(line(lines, 9).get("validation_message").asString()).startsWith("Row error");
        assertThat(status(lines, 10)).isEqualTo("DUPLICATE"); // same national id as line 2
        assertThat(line(lines, 11).get("msisdn").asString()).isEqualTo("+254700000008"); // national format normalised
        assertThat(status(lines, 11)).isEqualTo("VALID");
        assertThat(line(lines, 11).get("wallet_id").asString()).isEqualTo(faith.toString());

        JsonNode dups = get("/v1/disbursement-batches/" + batch + "/beneficiaries?validation_status=DUPLICATE");
        assertThat(dups.get("data")).hasSize(2);

        // lines are frozen once validated; a re-upload sends the batch back to DRAFT
        HttpResponse<String> edit = send("POST", "/v1/disbursement-beneficiaries",
                beneficiaryBody(batch, 99, "+254700000001", "10"), key(), "maker", TENANT);
        assertThat(edit.statusCode()).isEqualTo(409);
        assertThat(edit.body()).contains("batch_not_editable");
    }

    // ------------------------------------------------------------------ 3. approval and execution

    @Test
    @Order(3)
    void twoLevelFourEyesApprovalExecutesPayoutsWithPartialFailure() throws Exception {
        JsonNode s = post("/v1/disbursement-batches/" + batch + "/submit", null, "maker", 200);
        assertThat(s.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(s.get("submitted_by").asString()).isEqualTo("maker");

        HttpResponse<String> self = send("POST", "/v1/disbursement-batches/" + batch + "/approve", "{}", key(), "maker", TENANT);
        assertThat(self.statusCode()).isEqualTo(422);
        assertThat(self.body()).contains("four_eyes_violation");

        JsonNode l1 = post("/v1/disbursement-batches/" + batch + "/approve", "{\"comment\":\"level 1 ok\"}", "checker1", 200);
        assertThat(l1.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(l1.get("approvals_received").asInt()).isEqualTo(1);
        HttpResponse<String> twice = send("POST", "/v1/disbursement-batches/" + batch + "/approve", "{}", key(), "checker1", TENANT);
        assertThat(twice.statusCode()).isEqualTo(422);
        assertThat(twice.body()).contains("four_eyes_violation");
        assertThat(wallets.payouts()).isEmpty();

        // not enough money in the funding wallet: refused, nothing recorded
        wallets.setBalance(fund, "100");
        HttpResponse<String> poor = send("POST", "/v1/disbursement-batches/" + batch + "/approve", "{}", key(), "checker2", TENANT);
        assertThat(poor.statusCode()).isEqualTo(422);
        assertThat(poor.body()).contains("insufficient_funding");
        assertThat(get("/v1/disbursement-batches/" + batch).get("approvals_received").asInt()).isEqualTo(1);
        wallets.setBalance(fund, "10000");

        wallets.failingPayees.add(faith);
        String k = key();
        HttpResponse<String> fin = send("POST", "/v1/disbursement-batches/" + batch + "/approve", "{\"comment\":\"pay\"}", k, "checker2", TENANT);
        assertThat(fin.statusCode()).as(fin.body()).isEqualTo(200);
        JsonNode done = json.readTree(fin.body());
        assertThat(done.get("status").asString()).isEqualTo("PARTIALLY_COMPLETED");
        assertThat(done.get("approved_by").asString()).isEqualTo("checker2");
        assertThat(done.get("paid_count").asInt()).isEqualTo(2);
        assertThat(done.get("failed_count").asInt()).isEqualTo(1);
        assertThat(dec(done, "paid_amount")).isEqualByComparingTo("2500.50");
        assertThat(done.get("started_at").isNull()).isFalse();
        assertThat(done.get("completed_at").isNull()).isFalse();

        // money moved from the funding wallet to the beneficiaries, once per line
        assertThat(wallets.balance(fund)).isEqualByComparingTo("7499.50");
        assertThat(wallets.balance(john)).isEqualByComparingTo("1000");
        assertThat(wallets.balance(achieng)).isEqualByComparingTo("1500.50");
        assertThat(wallets.balance(faith)).isEqualByComparingTo("0");
        List<FakeWalletService.Call> payouts = wallets.payouts();
        assertThat(payouts).hasSize(3);
        for (FakeWalletService.Call c : payouts) {
            assertThat(c.body().get("txn_type").asString()).isEqualTo("G2P");
            assertThat(c.body().get("initiator_type").asString()).isEqualTo("BULK");
            assertThat(c.body().get("channel").asString()).isEqualTo("BULK");
            assertThat(c.body().get("payer_wallet_id").asString()).isEqualTo(fund.toString());
            assertThat(c.key()).startsWith("dbn-").endsWith("-1");
            assertThat(c.key()).contains(c.body().get("disbursement_beneficiary_id").asString());
        }

        List<JsonNode> lines = lines(batch);
        assertThat(line(lines, 1).get("payout_status").asString()).isEqualTo("PAID");
        assertThat(line(lines, 1).get("wallet_transaction_id").isNull()).isFalse();
        assertThat(line(lines, 11).get("payout_status").asString()).isEqualTo("FAILED");
        assertThat(line(lines, 11).get("failure_reason").asString()).contains("wallet_not_active");
        assertThat(line(lines, 3).get("payout_status").asString()).isEqualTo("SKIPPED");
        assertThat(line(lines, 4).get("payout_status").asString()).isEqualTo("SKIPPED");

        // replaying the final approval returns the stored response and pays nobody twice
        HttpResponse<String> replay = send("POST", "/v1/disbursement-batches/" + batch + "/approve", "{\"comment\":\"pay\"}", k, "checker2", TENANT);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(wallets.payouts()).hasSize(3);

        JsonNode approvals = get("/v1/disbursement-batches/" + batch + "/approvals");
        assertThat(approvals.get("data")).hasSize(2);
        assertThat(approvals.get("data")).extracting(a -> a.get("decided_by").asString())
                .containsExactlyInAnyOrder("checker1", "checker2");

        assertThat(events("wallets.disbursement_batch.submitted", batch)).isEqualTo(1);
        assertThat(events("wallets.disbursement_batch.approved", batch)).isEqualTo(1);
        assertThat(events("wallets.disbursement_batch.completed", batch)).isEqualTo(1);
        assertThat(events("wallets.disbursement_beneficiary.paid", null)).isEqualTo(2);
        assertThat(events("wallets.disbursement_beneficiary.failed", null)).isEqualTo(1);
    }

    @Test
    @Order(4)
    void retryFailedPaysTheRemainingLineWithANewAttempt() throws Exception {
        wallets.failingPayees.clear();
        JsonNode r = post("/v1/disbursement-batches/" + batch + "/retry-failed", null, "ops", 200);
        assertThat(r.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(r.get("paid_count").asInt()).isEqualTo(3);
        assertThat(r.get("failed_count").asInt()).isZero();
        assertThat(dec(r, "paid_amount")).isEqualByComparingTo("2900.50");
        assertThat(wallets.balance(faith)).isEqualByComparingTo("400");
        assertThat(wallets.balance(fund)).isEqualByComparingTo("7099.50");
        assertThat(wallets.payouts()).hasSize(4);
        assertThat(wallets.payouts().getLast().key()).endsWith("-2");
        JsonNode line = line(lines(batch), 11);
        assertThat(line.get("payout_status").asString()).isEqualTo("PAID");
        assertThat(line.get("attempt_count").asInt()).isEqualTo(2);

        HttpResponse<String> again = send("POST", "/v1/disbursement-batches/" + batch + "/retry-failed", null, key(), "ops", TENANT);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(events("wallets.disbursement_batch.completed", batch)).isEqualTo(2);
    }

    @Test
    @Order(5)
    void reportListsEveryLineWithItsOutcome() throws Exception {
        HttpResponse<String> r = send("GET", "/v1/disbursement-batches/" + batch + "/report", null, null, "ops", TENANT);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        String[] rows = r.body().split("\r\n");
        assertThat(rows).hasSize(12);
        assertThat(rows[0]).startsWith("line_no,beneficiary_reference,msisdn");
        assertThat(rows[1]).startsWith("1,R1,+254700000001,John Kamau,John Kamau,1000.00,KES,VALID,,PAID,1,");
        assertThat(rows[3]).contains("NAME_MISMATCH").contains("SKIPPED");
        assertThat(rows[9]).contains("\"Row error: msisdn bad-number is not a valid E.164 number\"".replace("\"", ""));
    }

    // ------------------------------------------------------------------ 4. other batch outcomes

    @Test
    @Order(6)
    void duplicateFileRejectionAndCancellation() throws Exception {
        String b2 = post("/v1/disbursement-batches", batchBody(program), "maker", 201).get("id").asString();
        HttpResponse<String> dupFile = send("POST", "/v1/disbursement-batches/" + b2 + "/upload", uploadBody(FILE, "again.csv"),
                key(), "maker", TENANT);
        assertThat(dupFile.statusCode()).isEqualTo(409);
        assertThat(dupFile.body()).contains("duplicate_file");
        HttpResponse<String> broken = send("POST", "/v1/disbursement-batches/" + b2 + "/upload", uploadBody("name,amount\nA,1\n", "x.csv"),
                key(), "maker", TENANT);
        assertThat(broken.statusCode()).isEqualTo(422);
        assertThat(broken.body()).contains("invalid_file");

        post("/v1/disbursement-batches/" + b2 + "/upload", uploadBody("msisdn,amount,full_name\n+254700000001,10,John Kamau\n", "b2.csv"), "maker", 200);
        post("/v1/disbursement-batches/" + b2 + "/validate", null, "maker", 200);
        post("/v1/disbursement-batches/" + b2 + "/submit", null, "maker", 200);
        HttpResponse<String> selfReject = send("POST", "/v1/disbursement-batches/" + b2 + "/reject", "{\"reason\":\"no\"}", key(), "maker", TENANT);
        assertThat(selfReject.statusCode()).isEqualTo(422);
        HttpResponse<String> noReason = send("POST", "/v1/disbursement-batches/" + b2 + "/reject", "{}", key(), "checker1", TENANT);
        assertThat(noReason.statusCode()).isEqualTo(400);
        JsonNode rejected = post("/v1/disbursement-batches/" + b2 + "/reject", "{\"reason\":\"Wrong period\"}", "checker1", 200);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejection_reason").asString()).isEqualTo("Wrong period");
        JsonNode approvals = get("/v1/disbursement-batches/" + b2 + "/approvals");
        assertThat(approvals.get("data").get(0).get("decision").asString()).isEqualTo("REJECTED");
        assertThat(events("wallets.disbursement_batch.rejected", b2)).isEqualTo(1);
        assertThat(send("POST", "/v1/disbursement-batches/" + b2 + "/approve", "{}", key(), "checker2", TENANT).statusCode())
                .isEqualTo(409);

        String b3 = post("/v1/disbursement-batches", batchBody(program), "maker", 201).get("id").asString();
        JsonNode cancelled = post("/v1/disbursement-batches/" + b3 + "/cancel", "{\"reason\":\"duplicate request\"}", "maker", 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(send("POST", "/v1/disbursement-batches/" + b3 + "/cancel", "{}", key(), "maker", TENANT).statusCode()).isEqualTo(409);
        assertThat(events("wallets.disbursement_batch.cancelled", b3)).isEqualTo(1);
        // completed batches cannot be cancelled
        assertThat(send("POST", "/v1/disbursement-batches/" + batch + "/cancel", "{}", key(), "maker", TENANT).statusCode()).isEqualTo(409);
    }

    @Test
    @Order(7)
    void manualLinesOnlyWhileDraftWithOptimisticLocking() throws Exception {
        String b4 = post("/v1/disbursement-batches", batchBody(program), "maker", 201).get("id").asString();
        JsonNode line = post("/v1/disbursement-beneficiaries", beneficiaryBody(b4, 1, "+254700000002", "250.00"), "maker", 201);
        assertThat(line.get("validation_status").asString()).isEqualTo("PENDING");
        assertThat(get("/v1/disbursement-batches/" + b4).get("total_count").asInt()).isEqualTo(1);
        HttpResponse<String> clash = send("POST", "/v1/disbursement-beneficiaries", beneficiaryBody(b4, 1, "+254700000001", "1"),
                key(), "maker", TENANT);
        assertThat(clash.statusCode()).isEqualTo(409);
        HttpResponse<String> badAmount = send("POST", "/v1/disbursement-beneficiaries", beneficiaryBody(b4, 2, "+254700000001", "1.001"),
                key(), "maker", TENANT);
        assertThat(badAmount.statusCode()).isEqualTo(422);

        String id = line.get("id").asString();
        HttpResponse<String> patched = send("PATCH", "/v1/disbursement-beneficiaries/" + id, "{\"amount\":\"300\"}", null, "maker", TENANT);
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        assertThat(dec(json.readTree(patched.body()), "amount")).isEqualByComparingTo("300");
        assertThat(dec(get("/v1/disbursement-batches/" + b4), "total_amount")).isEqualByComparingTo("300");
        HttpResponse<String> stale = send("PATCH", "/v1/disbursement-beneficiaries/" + id, "{\"amount\":\"310\"}", null, "maker", TENANT);
        assertThat(stale.statusCode()).isEqualTo(409);

        // full_name required for name matching; the line is otherwise fine
        JsonNode v = post("/v1/disbursement-batches/" + b4 + "/validate", null, "maker", 200);
        assertThat(v.get("invalid_count").asInt()).isEqualTo(1);
        post("/v1/disbursement-batches/" + b4 + "/upload", uploadBody("msisdn,amount,full_name\n+254700000002,5,Achieng Otieno\n", "b4.csv"), "maker", 200);
        assertThat(post("/v1/disbursement-batches/" + b4 + "/validate", null, "maker", 200).get("valid_count").asInt()).isEqualTo(1);
        post("/v1/disbursement-batches/" + b4 + "/submit", null, "maker", 200);

        // the program cannot close while a batch awaits approval
        HttpResponse<String> close = send("POST", "/v1/disbursement-programs/" + program + "/close", "{}", key(), "ops", TENANT);
        assertThat(close.statusCode()).isEqualTo(409);
        assertThat(close.body()).contains("batches_in_flight");
        post("/v1/disbursement-batches/" + b4 + "/cancel", "{}", "maker", 200);
        JsonNode suspended = post("/v1/disbursement-programs/" + program + "/suspend", "{\"reason\":\"audit\"}", "ops", 200);
        assertThat(suspended.get("status").asString()).isEqualTo("SUSPENDED");
        HttpResponse<String> noBatch = send("POST", "/v1/disbursement-batches", batchBody(program), key(), "maker", TENANT);
        assertThat(noBatch.statusCode()).isEqualTo(422);
        JsonNode closed = post("/v1/disbursement-programs/" + program + "/close", "{\"reason\":\"ended\"}", "ops", 200);
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(get("/v1/disbursement-programs/" + program + "/batches").get("data")).hasSize(4);
    }

    @Test
    @Order(8)
    void autoCreatedTierZeroWalletsAndSingleLevelApproval() throws Exception {
        String p = post("/v1/disbursement-programs", programBody("HUM-1", fund, 1, true, "KES")
                .replace("\"require_kyc_tier\":1,", "").replace("\"require_name_match\":true", "\"require_name_match\":false"),
                "maker", 201).get("id").asString();
        post("/v1/disbursement-programs/" + p + "/activate", null, "ops", 200);
        String b = post("/v1/disbursement-batches", batchBody(p), "maker", 201).get("id").asString();
        post("/v1/disbursement-batches/" + b + "/upload", uploadBody("msisdn,amount,full_name\n+254700000099,50,New Person\n", "hum.csv"), "maker", 200);
        JsonNode v = post("/v1/disbursement-batches/" + b + "/validate", null, "maker", 200);
        assertThat(v.get("valid_count").asInt()).isEqualTo(1);
        UUID created = wallets.byMsisdn("+254700000099");
        assertThat(created).isNotNull();
        assertThat(lines(b).getFirst().get("wallet_id").asString()).isEqualTo(created.toString());
        post("/v1/disbursement-batches/" + b + "/submit", null, "maker", 200);
        JsonNode done = post("/v1/disbursement-batches/" + b + "/approve", "{}", "checker1", 200);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(wallets.balance(created)).isEqualByComparingTo("50");
        assertThat(wallets.payouts().getLast().body().get("txn_type").asString()).isEqualTo("G2P");
    }

    @Test
    @Order(9)
    void tenantIsolation() throws Exception {
        assertThat(send("GET", "/v1/disbursement-batches/" + batch, null, null, "ops", OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/v1/disbursement-batches/" + batch + "/report", null, null, "ops", OTHER_TENANT).statusCode()).isEqualTo(404);
        HttpResponse<String> list = send("GET", "/v1/disbursement-programs", null, null, "ops", OTHER_TENANT);
        assertThat(json.readTree(list.body()).get("data")).isEmpty();
        HttpResponse<String> approve = send("POST", "/v1/disbursement-batches/" + batch + "/approve", "{}", key(), "x", OTHER_TENANT);
        assertThat(approve.statusCode()).isEqualTo(404);
        HttpResponse<String> noTenant = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/v1/disbursement-programs")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noTenant.statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ helpers

    private String programBody(String code, UUID wallet, int levels, boolean autoCreate, String currency) {
        return """
                {"code":"%s","name":"Social cash transfer","sponsor_name":"Ministry of Social Protection","program_type":"SOCIAL_TRANSFER",
                 "funding_wallet_id":"%s","currency":"%s","max_amount_per_beneficiary":"5000","require_kyc_tier":1,
                 "approval_levels":%d,"auto_create_wallets":%s,"require_name_match":true}""".formatted(code, wallet, currency, levels, autoCreate);
    }

    private static String batchBody(String program) {
        return "{\"disbursement_program_id\":\"" + program + "\",\"currency\":\"KES\",\"description\":\"October payments\"}";
    }

    private String uploadBody(String content, String name) {
        return json.writeValueAsString(java.util.Map.of("content", content, "file_name", name));
    }

    private static String beneficiaryBody(String batch, int lineNo, String msisdn, String amount) {
        return """
                {"disbursement_batch_id":"%s","line_no":%d,"msisdn":"%s","amount":"%s","currency":"KES"}"""
                .formatted(batch, lineNo, msisdn, amount);
    }

    private List<JsonNode> lines(String batchId) throws Exception {
        JsonNode page = get("/v1/disbursement-batches/" + batchId + "/beneficiaries?limit=200");
        java.util.ArrayList<JsonNode> out = new java.util.ArrayList<>();
        page.get("data").forEach(out::add);
        return out;
    }

    private static JsonNode line(List<JsonNode> lines, int no) {
        return lines.stream().filter(l -> l.get("line_no").asInt() == no).findFirst().orElseThrow();
    }

    private static String status(List<JsonNode> lines, int no) {
        return line(lines, no).get("validation_status").asString();
    }

    private <T> T asTenant(Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", body);
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

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, key(), user, TENANT);
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
