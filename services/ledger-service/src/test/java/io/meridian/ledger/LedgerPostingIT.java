package io.meridian.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.test.MeridianTestContainers;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end over HTTP against PostgreSQL 18 with row-level security (service connects as meridian_app).
 * Scenario: a USD ledger with a cash account (asset), two customer deposit accounts (liabilities) and a fee
 * income account. Balances are in normal-balance terms.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LedgerPostingIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000000a";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-00000000000b";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    final HttpClient http = HttpClient.newHttpClient();
    String ledger;
    String cash;
    String alice;
    String bob;
    String fees;

    @BeforeAll
    void setUp() throws Exception {
        ledger = post("/v1/ledgers", """
                {"code":"MAIN","name":"Main book","legal_entity_id":"%s","base_currency":"USD"}"""
                .formatted(UUID.randomUUID()), 201).get("id").asString();
        cash = account("1000", "Cash", "ASSET", "DEBIT", true);
        alice = account("2000-A", "Alice deposits", "LIABILITY", "CREDIT", false);
        bob = account("2000-B", "Bob deposits", "LIABILITY", "CREDIT", false);
        fees = account("4000", "Fee income", "INCOME", "CREDIT", true);
    }

    @Test
    @Order(1)
    void depositAndTransferMoveBalances() throws Exception {
        JsonNode deposit = postEntry("dep-1", line(cash, "DEBIT", "1000.00"), line(alice, "CREDIT", "1000.00"));
        assertThat(deposit.get("entries")).hasSize(2);
        assertThat(deposit.get("entry_count").asInt()).isEqualTo(2);
        assertThat(deposit.get("hash").asString()).hasSize(64);
        postEntry("tr-1", line(alice, "DEBIT", "300.00"), line(bob, "CREDIT", "300.00"));
        assertBalance(alice, "700", "700");
        assertBalance(bob, "300", "300");
        assertBalance(cash, "1000", "1000");
    }

    @Test
    @Order(2)
    void rejectsOverdraftUnbalancedAndWrongCurrency() throws Exception {
        JsonNode over = postEntry("tr-over", 422, line(alice, "DEBIT", "800.00"), line(bob, "CREDIT", "800.00"));
        assertThat(over.get("code").asString()).isEqualTo("insufficient_funds");
        JsonNode unbalanced = postEntry("tr-unb", 422, line(alice, "DEBIT", "10.00"), line(bob, "CREDIT", "9.99"));
        assertThat(unbalanced.get("code").asString()).isEqualTo("unbalanced_entry");
        JsonNode ccy = postEntry("tr-eur", 422, lineCcy(alice, "DEBIT", "10.00", "EUR"), lineCcy(bob, "CREDIT", "10.00", "EUR"));
        assertThat(ccy.get("code").asString()).isIn("fx_rate_required", "currency_mismatch");
        assertBalance(alice, "700", "700");
    }

    @Test
    @Order(3)
    void replayWithSameKeyDoesNotPostTwice() throws Exception {
        HttpResponse<String> r = send("POST", "/v1/journal-entries", entryBody(line(alice, "DEBIT", "300.00"),
                line(bob, "CREDIT", "300.00")), "tr-1", TENANT);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertBalance(alice, "700", "700");
    }

    @Test
    @Order(4)
    void holdReducesAvailableThenCaptureAndRelease() throws Exception {
        JsonNode hold = post("/v1/holds", """
                {"ledger_account_id":"%s","hold_type":"CARD_AUTHORIZATION","amount":"200.00","currency":"USD"}"""
                .formatted(alice), 201);
        String holdId = hold.get("id").asString();
        assertBalance(alice, "700", "500");
        JsonNode blocked = postEntry("tr-blocked", 422, line(alice, "DEBIT", "600.00"), line(bob, "CREDIT", "600.00"));
        assertThat(blocked.get("code").asString()).isEqualTo("insufficient_funds");

        JsonNode captured = post("/v1/holds/" + holdId + "/capture", """
                {"amount":"150.00","counter_account_id":"%s","description":"Card purchase"}""".formatted(fees), 200);
        assertThat(captured.get("status").asString()).isEqualTo("PARTIALLY_CAPTURED");
        assertBalance(alice, "550", "500");
        assertBalance(fees, "150", "150");

        JsonNode released = post("/v1/holds/" + holdId + "/release", "{\"reason\":\"auth expired\"}", 200);
        assertThat(released.get("status").asString()).isEqualTo("RELEASED");
        assertBalance(alice, "550", "550");
        assertThat(get("/v1/holds/" + holdId + "/events", TENANT).get("data")).hasSize(3);
    }

    @Test
    @Order(5)
    void reversalMirrorsEntryOnlyOnce() throws Exception {
        String transferId = findEntryByKey("tr-1");
        JsonNode rev = post("/v1/journal-entries/" + transferId + "/reverse", "{\"reason\":\"customer error\"}", 201);
        assertThat(rev.get("entry_type").asString()).isEqualTo("REVERSAL");
        assertThat(rev.get("reversal_of_id").asString()).isEqualTo(transferId);
        assertBalance(alice, "850", "850");
        assertBalance(bob, "0", "0");
        HttpResponse<String> again = send("POST", "/v1/journal-entries/" + transferId + "/reverse",
                "{\"reason\":\"again\"}", "rev-2", TENANT);
        assertThat(again.statusCode()).isEqualTo(409);
    }

    @Test
    @Order(6)
    void debitFreezeBlocksOutgoingOnly() throws Exception {
        post("/v1/ledger-accounts/" + alice + "/freeze", "{\"status\":\"FROZEN_DEBIT\",\"reason\":\"court order\"}", 200);
        JsonNode out = postEntry("frz-out", 422, line(alice, "DEBIT", "10.00"), line(bob, "CREDIT", "10.00"));
        assertThat(out.get("code").asString()).isEqualTo("account_frozen");
        postEntry("frz-in", line(cash, "DEBIT", "10.00"), line(alice, "CREDIT", "10.00"));
        post("/v1/ledger-accounts/" + alice + "/unfreeze", "{\"reason\":\"lifted\"}", 200);
        assertBalance(alice, "860", "860");
    }

    @Test
    @Order(7)
    void otherTenantSeesNothing() throws Exception {
        HttpResponse<String> r = send("GET", "/v1/ledger-accounts/" + alice, null, null, OTHER_TENANT);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(get("/v1/journal-entries", OTHER_TENANT).get("data")).isEmpty();
    }

    @Test
    @Order(8)
    void concurrentTransfersStayConsistentAndVersionsGapless() throws Exception {
        int n = 50;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String key = "conc-" + i;
                results.add(pool.submit(() -> send("POST", "/v1/journal-entries",
                        entryBody(line(alice, "DEBIT", "1.00"), line(bob, "CREDIT", "1.00")), key, TENANT).statusCode()));
            }
            for (Future<Integer> f : results) {
                assertThat(f.get()).isEqualTo(201);
            }
        }
        assertBalance(alice, "810", "810");
        assertBalance(bob, "50", "50");
        JsonNode entries = get("/v1/ledger-accounts/" + alice + "/entries?limit=200", TENANT).get("data");
        List<Long> versions = new ArrayList<>();
        entries.forEach(e -> versions.add(e.get("account_version").asLong()));
        versions.sort(Long::compare);
        for (int i = 0; i < versions.size(); i++) {
            assertThat(versions.get(i)).isEqualTo(i + 1L);
        }
    }

    @Test
    @Order(9)
    void trialBalanceDebitsEqualCredits() throws Exception {
        BigDecimal debitNormal = bal(cash);
        BigDecimal creditNormal = bal(alice).add(bal(bob)).add(bal(fees));
        assertThat(debitNormal).isEqualByComparingTo(creditNormal);
    }

    @Test
    @Order(10)
    void verificationPassesOnUntouchedLedger() throws Exception {
        JsonNode v = post("/v1/ledgers/" + ledger + "/verify", "", 200);
        assertThat(v.get("passed").asBoolean()).as(v.toString()).isTrue();
        assertThat(v.get("accounts_checked").asInt()).isEqualTo(4);
        assertThat(v.get("entries_checked").asInt()).isGreaterThan(50);
        assertThat(v.get("chain_head").asString()).hasSize(64);
    }

    @Test
    @Order(11)
    void verificationDetectsTampering() throws Exception {
        // Simulate a malicious/buggy direct DB change that bypasses the posting engine: a superuser disables
        // the append-only trigger and alters one posted amount, and also corrupts a stored balance.
        try (var c = java.sql.DriverManager.getConnection(MeridianTestContainersAccess.adminUrl(), "meridian", "meridian")) {
            var st = c.createStatement();
            st.execute("set search_path to ledger");
            st.execute("alter table ledger_entry disable trigger trg_ledger_entry_append_only");
            st.execute("update ledger_entry set amount = amount + 1 where id = (select id from ledger_entry order by id limit 1)");
            st.execute("alter table ledger_entry enable trigger trg_ledger_entry_append_only");
            st.execute("update account_balance set posted_balance = posted_balance + 5 where ledger_account_id = '" + bob + "'");
        }
        JsonNode v = post("/v1/ledgers/" + ledger + "/verify", "", 200);
        assertThat(v.get("passed").asBoolean()).isFalse();
        assertThat(v.get("hash_mismatches").asInt()).isEqualTo(1);
        assertThat(v.get("unbalanced_entries").asInt()).isEqualTo(1);
        assertThat(v.get("balance_mismatches").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(12)
    void endOfDayWritesSnapshotsOnceAndExpiresHolds() throws Exception {
        String hold = post("/v1/holds", """
                {"ledger_account_id":"%s","hold_type":"PAYMENT","amount":"5.00","currency":"USD",
                 "expires_at":"2000-01-01T00:00:00Z"}""".formatted(alice), 201).get("id").asString();
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        JsonNode run = post("/v1/ledgers/" + ledger + "/eod", "{\"business_date\":\"" + today + "\"}", 201);
        assertThat(run.get("holds_expired").asInt()).isEqualTo(1);
        assertThat(run.get("snapshots_written").asInt()).isEqualTo(4);
        assertThat(get("/v1/holds/" + hold, TENANT).get("status").asString()).isEqualTo("EXPIRED");
        JsonNode again = post("/v1/ledgers/" + ledger + "/eod", "{\"business_date\":\"" + today + "\"}", 201);
        assertThat(again.get("snapshots_written").asInt()).isZero();
        assertThat(again.get("snapshots_skipped").asInt()).isEqualTo(4);
        JsonNode snaps = get("/v1/ledger-accounts/" + alice + "/balance-history", TENANT).get("data");
        assertThat(snaps).hasSize(1);
    }

    @Test
    @Order(13)
    void referenceAndValueDateAreCopiedToLedgerEntries() throws Exception {
        JsonNode je = post("/v1/journal-entries", """
                {"ledger_id":"%s","description":"wire in","value_date":"2026-03-02","reference":"E2E-REF-1",
                 "entries":[%s,%s]}""".formatted(ledger, line(cash, "DEBIT", "5.00"), line(alice, "CREDIT", "5.00")), 201);
        assertThat(je.get("reference").asString()).isEqualTo("E2E-REF-1");
        for (JsonNode e : je.get("entries")) {
            assertThat(e.get("value_date").asString()).isEqualTo("2026-03-02");
            assertThat(e.get("reference").asString()).isEqualTo("E2E-REF-1");
        }
        JsonNode lines = get("/v1/journal-entries/" + je.get("id").asString() + "/entries", TENANT).get("data");
        assertThat(lines).hasSize(2);
        lines.forEach(e -> assertThat(e.get("value_date").asString()).isEqualTo("2026-03-02"));
        assertThat(get("/v1/journal-entries?reference=E2E-REF-1", TENANT).get("data")).hasSize(1);

        JsonNode rev = post("/v1/journal-entries/" + je.get("id").asString() + "/reverse", "{\"reason\":\"returned\"}", 201);
        assertThat(rev.get("reference").asString()).isEqualTo("E2E-REF-1");
        assertThat(rev.get("entries").get(0).get("reference").asString()).isEqualTo("E2E-REF-1");
    }

    @Test
    @Order(14)
    void batchPostsEachEntryOnItsOwnAndReplays() throws Exception {
        String body = """
                {"entries":[
                  {"ledger_id":"%1$s","description":"b0","entries":[%2$s,%3$s]},
                  {"ledger_id":"%1$s","description":"b1","entries":[%4$s,%5$s]},
                  {"ledger_id":"%1$s","description":"b2","entries":[%2$s,%3$s]}]}"""
                .formatted(ledger, line(cash, "DEBIT", "2.00"), line(fees, "CREDIT", "2.00"),
                        line(cash, "DEBIT", "2.00"), line(fees, "CREDIT", "1.99"));
        BigDecimal feesBefore = bal(fees);
        HttpResponse<String> r = send("POST", "/v1/journal-entries/batch", body, "batch-1", TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode res = json.readTree(r.body());
        assertThat(res.get("posted").asInt()).isEqualTo(2);
        assertThat(res.get("failed").asInt()).isEqualTo(1);
        assertThat(res.get("results").get(1).get("code").asString()).isEqualTo("unbalanced_entry");
        assertThat(res.get("results").get(0).get("journal_entry").get("idempotency_key").asString()).isEqualTo("batch-1:0");
        assertThat(bal(fees)).isEqualByComparingTo(feesBefore.add(new BigDecimal("4.00")));

        JsonNode replay = json.readTree(send("POST", "/v1/journal-entries/batch", body, "batch-1", TENANT).body());
        assertThat(replay.get("posted").asInt()).isZero();
        assertThat(replay.get("replayed").asInt()).isEqualTo(2);
        assertThat(bal(fees)).isEqualByComparingTo(feesBefore.add(new BigDecimal("4.00")));
    }

    @Test
    @Order(15)
    void overdraftLimitAndFreezeAreIdempotent() throws Exception {
        String carol = account("2000-C", "Carol deposits", "LIABILITY", "CREDIT", false);
        postEntry("carol-fund", line(cash, "DEBIT", "100.00"), line(carol, "CREDIT", "100.00"));
        // SEC-12: a user cannot set a credit limit; only a service identity can
        assertThat(post("/v1/ledger-accounts/" + carol + "/overdraft-limit", "{\"limit\":\"50.00\"}", 403).get("code")
                .asString()).isEqualTo("service_identity_required");
        JsonNode limited = postAs("deposit-service", "/v1/ledger-accounts/" + carol + "/overdraft-limit",
                "{\"limit\":\"50.00\",\"reason\":\"arranged\"}", 200);
        assertThat(new BigDecimal(limited.get("overdraft_limit").asString())).isEqualByComparingTo("50");
        assertBalance(carol, "100", "150");
        JsonNode same = postAs("deposit-service", "/v1/ledger-accounts/" + carol + "/overdraft-limit", "{\"limit\":\"50\"}", 200);
        assertThat(same.get("row_version").asInt()).isEqualTo(limited.get("row_version").asInt());
        assertThat(postAs("deposit-service", "/v1/ledger-accounts/" + carol + "/overdraft-limit", "{\"limit\":\"-1\"}", 422)
                .get("code").asString()).isEqualTo("invalid_overdraft_limit");
        postEntry("carol-od", line(carol, "DEBIT", "140.00"), line(cash, "CREDIT", "140.00"));
        assertBalance(carol, "-40", "10");

        String frz = "{\"status\":\"FROZEN_DEBIT\",\"reason\":\"review\"}";
        assertThat(post("/v1/ledger-accounts/" + carol + "/freeze", frz, 200).get("status").asString()).isEqualTo("FROZEN_DEBIT");
        assertThat(post("/v1/ledger-accounts/" + carol + "/freeze", frz, 200).get("status").asString()).isEqualTo("FROZEN_DEBIT");
        assertThat(post("/v1/ledger-accounts/" + carol + "/freeze", "{\"status\":\"ACTIVE\",\"reason\":\"x\"}", 422)
                .get("code").asString()).isEqualTo("invalid_status");
        assertThat(post("/v1/ledger-accounts/" + carol + "/unfreeze", "{\"reason\":\"ok\"}", 200).get("status").asString())
                .isEqualTo("ACTIVE");
        assertThat(post("/v1/ledger-accounts/" + carol + "/unfreeze", "{\"reason\":\"ok\"}", 200).get("status").asString())
                .isEqualTo("ACTIVE");

        // SEC-12: PATCH cannot change credit terms or the account's identity; descriptive fields still change.
        for (String body : List.of("{\"overdraft_limit\":\"1000000000\"}", "{\"allow_negative\":true}",
                "{\"currency\":\"EUR\"}", "{\"ledger_id\":\"" + UUID.randomUUID() + "\"}", "{\"min_balance\":\"-5\"}",
                "{\"normal_balance\":\"DEBIT\"}", "{\"owner_id\":\"" + UUID.randomUUID() + "\"}")) {
            HttpResponse<String> pr = patch(carol, body);
            assertThat(pr.statusCode()).as(body + " " + pr.body()).isEqualTo(422);
            assertThat(json.readTree(pr.body()).get("code").asString()).isEqualTo("field_not_updatable");
        }
        HttpResponse<String> renamed = patch(carol, "{\"name\":\"Carol main\",\"currency\":\"USD\",\"overdraft_limit\":\"50\"}");
        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(200);
        assertThat(json.readTree(renamed.body()).get("name").asString()).isEqualTo("Carol main");
        assertBalance(carol, "-40", "10");

        // The owning service lowers the limit through the action; the stored available balance follows.
        postAs("deposit-service", "/v1/ledger-accounts/" + carol + "/overdraft-limit", "{\"limit\":\"0\"}", 200);
        assertBalance(carol, "-40", "-40");
    }

    @Test
    @Order(17)
    void overdraftLimitOfOwnedAccountOnlyByOwningService() throws Exception {
        String dave = post("/v1/ledger-accounts", """
                {"ledger_id":"%s","code":"2000-D","name":"Dave","account_type":"LIABILITY","normal_balance":"CREDIT",
                 "currency":"USD","owner_type":"DEPOSIT_ACCOUNT","owner_id":"%s"}""".formatted(ledger, UUID.randomUUID()), 201)
                .get("id").asString();
        assertThat(postAs("wallet-service", "/v1/ledger-accounts/" + dave + "/overdraft-limit", "{\"limit\":\"10\"}", 403)
                .get("code").asString()).isEqualTo("not_account_owner");
        JsonNode set = postAs("deposit-service", "/v1/ledger-accounts/" + dave + "/overdraft-limit",
                "{\"limit\":\"25\",\"allow_negative\":true,\"reason\":\"facility approved\"}", 200);
        assertThat(new BigDecimal(set.get("overdraft_limit").asString())).isEqualByComparingTo("25");
        assertThat(set.get("allow_negative").asBoolean()).isTrue();
        assertBalance(dave, "0", "25");
    }

    @Test
    @Order(18)
    void concurrentReversalsOfOneEntryPostExactlyOnce() throws Exception {
        String erin = account("2000-E", "Erin deposits", "LIABILITY", "CREDIT", false);
        String entry = postEntry("erin-fund", line(cash, "DEBIT", "70.00"), line(erin, "CREDIT", "70.00")).get("id").asString();
        int n = 8;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<Integer> codes = java.util.Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String key = "erin-rev-" + i; // different keys: only one reversal may post
                fs.add(pool.submit(() -> {
                    start.await();
                    codes.add(send("POST", "/v1/journal-entries/" + entry + "/reverse", "{\"reason\":\"race\"}", key,
                            TENANT).statusCode());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : fs) {
                f.get();
            }
        }
        assertThat(codes).filteredOn(c -> c == 201).hasSize(1);
        assertThat(codes).filteredOn(c -> c == 409).hasSize(n - 1);
        assertBalance(erin, "0", "0");
        long reversals = 0;
        for (JsonNode e : get("/v1/journal-entries?limit=200", TENANT).get("data")) {
            if (entry.equals(e.path("reversal_of_id").asString(null))) {
                reversals++;
            }
        }
        assertThat(reversals).isEqualTo(1);

        // Same key raced: one posts, the others replay the same reversal.
        String entry2 = postEntry("erin-fund-2", line(cash, "DEBIT", "5.00"), line(erin, "CREDIT", "5.00")).get("id").asString();
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<String> ids = java.util.Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                fs.add(pool.submit(() -> {
                    go.await();
                    HttpResponse<String> r = send("POST", "/v1/journal-entries/" + entry2 + "/reverse",
                            "{\"reason\":\"race\"}", "erin-rev-same", TENANT);
                    assertThat(r.statusCode()).as(r.body()).isIn(200, 201);
                    ids.add(json.readTree(r.body()).get("id").asString());
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) {
                f.get();
            }
        }
        assertThat(ids).hasSize(4);
        assertThat(new java.util.HashSet<>(ids)).hasSize(1);
        assertBalance(erin, "0", "0");
    }

    @Test
    @Order(19)
    void paymentRefsAreStoredCopiedPseudonymisedAndPublished() throws Exception {
        String nostro = account("1100-N", "Nostro USD", "ASSET", "DEBIT", true);
        String gina = account("2900-G", "Gina deposits", "LIABILITY", "CREDIT", false);
        String uetr = UUID.randomUUID().toString();
        JsonNode je = post("/v1/journal-entries", """
                {"ledger_id":"%s","entry_type":"TRANSFER","description":"Incoming payment","reference":"E2E-PAY-77",
                 "value_date":"2026-03-02","entries":[%s,%s],
                 "external_refs":{"uetr":"%s","end_to_end_id":"E2E-PAY-77","instruction_id":"INS-77",
                   "bank_reference":"CLR-0077","scheme_reference":"RAIL-TX-77","batch_id":"BLK-1",
                   "counterparty_name":"Acme Supplies Ltd","counterparty_account":"de89 3704-0044 0532 0130 00",
                   "purpose_code":"SUPP"}}""".formatted(ledger, line(nostro, "DEBIT", "42.00"), line(gina, "CREDIT", "42.00"),
                uetr.toUpperCase()), 201);
        String expectedHash = new io.meridian.common.refs.CounterpartyAccounts(
                io.meridian.common.refs.CounterpartyAccounts.DEV_KEY).pseudonym(UUID.fromString(TENANT), "DE89370400440532013000");
        assertThat(je.get("uetr").asString()).as("stored lower-case").isEqualTo(uetr);
        assertThat(je.get("end_to_end_id").asString()).isEqualTo("E2E-PAY-77");
        assertThat(je.get("bank_reference").asString()).isEqualTo("CLR-0077");
        assertThat(je.get("scheme_reference").asString()).isEqualTo("RAIL-TX-77");
        assertThat(je.get("batch_id").asString()).isEqualTo("BLK-1");
        assertThat(je.get("purpose_code").asString()).isEqualTo("SUPP");
        assertThat(je.get("counterparty_account_hash").asString()).startsWith("h1:").isEqualTo(expectedHash);
        assertThat(je.get("counterparty_account_last4").asString()).isEqualTo("3000");
        assertThat(je.toString()).as("the account itself is never stored or returned")
                .doesNotContain("0532013000").doesNotContain("0532 0130").doesNotContain("external_refs");
        for (JsonNode line : je.get("entries")) {
            assertThat(line.get("uetr").asString()).isEqualTo(uetr);
            assertThat(line.get("instruction_id").asString()).isEqualTo("INS-77");
            assertThat(line.get("counterparty_name").asString()).isEqualTo("Acme Supplies Ltd");
            assertThat(line.get("counterparty_account_hash").asString()).isEqualTo(expectedHash);
            assertThat(line.get("entry_type").asString()).isEqualTo("TRANSFER");
        }
        // the ledger-entries list (reconciliation's internal side) carries the references
        JsonNode listed = get("/v1/ledger-entries?ledger_account_id=" + nostro, TENANT).get("data").get(0);
        assertThat(listed.get("end_to_end_id").asString()).isEqualTo("E2E-PAY-77");
        assertThat(listed.get("batch_id").asString()).isEqualTo("BLK-1");
        assertThat(listed.get("counterparty_account_last4").asString()).isEqualTo("3000");
        // indexed lookups
        assertThat(get("/v1/journal-entries?uetr=" + uetr, TENANT).get("data")).hasSize(1);
        assertThat(get("/v1/journal-entries?end_to_end_id=E2E-PAY-77", TENANT).get("data")).hasSize(1);
        assertThat(get("/v1/journal-entries?batch_id=BLK-1", TENANT).get("data")).hasSize(1);
        assertThat(get("/v1/journal-entries?counterparty_account_hash=" + expectedHash, TENANT).get("data")).hasSize(1);
        assertThat(get("/v1/journal-entries?uetr=" + uetr, OTHER_TENANT).get("data")).isEmpty();

        // the posted event carries them (consumers such as GL and reconciliation need no extra read)
        String payload = io.meridian.common.tenancy.TenantContext.callAs(UUID.fromString(TENANT), "test", () -> jdbc.sql(
                "select payload::text from outbox where event_type = 'ledger.journal_entry.posted' and aggregate_id = ?::uuid")
                .param(je.get("id").asString()).query(String.class).single());
        assertThat(json.readTree(payload).get("uetr").asString()).isEqualTo(uetr);
        assertThat(json.readTree(payload).get("entries").get(0).get("bank_reference").asString()).isEqualTo("CLR-0077");

        // captures carry them too (outbound payments settle by capturing their hold)
        String holdId = post("/v1/holds", """
                {"ledger_account_id":"%s","hold_type":"PAYMENT","amount":"10.00","currency":"USD"}""".formatted(alice), 201)
                .get("id").asString();
        post("/v1/holds/" + holdId + "/capture", """
                {"amount":"10.00","counter_account_id":"%s","reference":"E2E-OUT-1",
                 "external_refs":{"end_to_end_id":"E2E-OUT-1","counterparty_account":"GB29NWBK60161331926819"}}"""
                .formatted(nostro), 200);
        String captureJe = get("/v1/holds/" + holdId + "/events", TENANT).get("data").get(0).get("journal_entry_id").asString();
        JsonNode capture = get("/v1/journal-entries/" + captureJe, TENANT);
        assertThat(capture.get("reference").asString()).isEqualTo("E2E-OUT-1");
        assertThat(capture.get("end_to_end_id").asString()).isEqualTo("E2E-OUT-1");
        assertThat(capture.get("counterparty_account_last4").asString()).isEqualTo("6819");
        assertThat(capture.get("entries").get(1).get("end_to_end_id").asString()).isEqualTo("E2E-OUT-1");

        // a reversal keeps the references of the entry it reverses (reconciliation pairs the two)
        JsonNode rev = post("/v1/journal-entries/" + je.get("id").asString() + "/reverse", "{\"reason\":\"recalled\"}", 201);
        assertThat(rev.get("uetr").asString()).isEqualTo(uetr);
        assertThat(rev.get("counterparty_account_hash").asString()).isEqualTo(expectedHash);
        assertThat(rev.get("entries").get(0).get("entry_type").asString()).isEqualTo("REVERSAL");

        // validation: unknown keys, malformed UETR, non-string values
        for (String bad : List.of("{\"iban\":\"DE89\"}", "{\"uetr\":\"not-a-uuid\"}", "{\"batch_id\":7}", "[\"x\"]")) {
            HttpResponse<String> r = send("POST", "/v1/journal-entries", """
                    {"ledger_id":"%s","entries":[%s,%s],"external_refs":%s}""".formatted(ledger,
                    line(nostro, "DEBIT", "1.00"), line(gina, "CREDIT", "1.00"), bad), UUID.randomUUID().toString(), TENANT);
            assertThat(r.statusCode()).as(bad + " -> " + r.body()).isIn(400, 422);
        }
        // immutable: the journal and its lines are append-only
        String sql = "update journal_entry set uetr = null where id = ?::uuid";
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> io.meridian.common.tenancy.TenantContext.callAs(
                UUID.fromString(TENANT), "test", () -> jdbc.sql(sql).param(je.get("id").asString()).update()))
                .hasMessageContaining("append-only");
    }

    @Test
    @Order(99)
    void reusedKeysNeverReplayADifferentRequest() throws Exception {
        String dave = account("2900-D", "Dave deposits", "LIABILITY", "CREDIT", false);
        String erik = account("2900-E", "Erik deposits", "LIABILITY", "CREDIT", false);
        postEntry("sec19-fund", line(cash, "DEBIT", "100.00"), line(dave, "CREDIT", "100.00"));
        // SEC-19 ledger: same key, other lines -> 422 instead of the first entry
        assertThat(postEntry("sec19-fund", 422, line(cash, "DEBIT", "1.00"), line(dave, "CREDIT", "1.00"))
                .get("code").asString()).isEqualTo("idempotency_key_reused");
        assertThat(send("POST", "/v1/journal-entries", entryBody(line(dave, "CREDIT", "100.00"), line(cash, "DEBIT", "100")),
                "sec19-fund", TENANT).statusCode()).as("same lines in another order and scale replay").isEqualTo(200);
        // holds
        String hold = "{\"ledger_account_id\":\"%s\",\"hold_type\":\"CARD_AUTHORIZATION\",\"amount\":\"%s\",\"currency\":\"USD\"}";
        assertThat(send("POST", "/v1/holds", hold.formatted(dave, "10.00"), "sec19-hold", TENANT).statusCode()).isEqualTo(201);
        assertThat(send("POST", "/v1/holds", hold.formatted(dave, "10.00"), "sec19-hold", TENANT).statusCode()).isEqualTo(201);
        HttpResponse<String> other = send("POST", "/v1/holds", hold.formatted(dave, "90.00"), "sec19-hold", TENANT);
        assertThat(other.statusCode()).isEqualTo(422);
        assertThat(other.body()).contains("idempotency_key_reused");
        // generic Idempotency: the same key and body from another caller is refused, never answered with the first result
        String create = "{\"ledger_id\":\"%s\",\"code\":\"2900-F\",\"name\":\"F\",\"account_type\":\"LIABILITY\","
                + "\"normal_balance\":\"CREDIT\",\"currency\":\"USD\"}";
        HttpResponse<String> first = send("POST", "/v1/ledger-accounts", create.formatted(ledger), "sec19-acct", TENANT);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        HttpResponse<String> otherUser = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/v1/ledger-accounts"))
                .header("X-Tenant-Id", TENANT).header("X-User", "someone-else").header("Content-Type", "application/json")
                .header("Idempotency-Key", "sec19-acct").POST(HttpRequest.BodyPublishers.ofString(create.formatted(ledger)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(otherUser.statusCode()).as(otherUser.body()).isEqualTo(422);
        assertThat(otherUser.body()).contains("idempotency_key_reused").doesNotContain("2900-F");
        assertThat(erik).isNotBlank();
    }

    private HttpResponse<String> patch(String account, String body) throws Exception {
        JsonNode cur = get("/v1/ledger-accounts/" + account, TENANT);
        HttpRequest patch = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/ledger-accounts/" + account))
                .header("X-Tenant-Id", TENANT).header("Content-Type", "application/json")
                .header("If-Match", "\"" + cur.get("row_version").asInt() + "\"")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(patch, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode postAs(String service, String path, String body, int expected) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("Content-Type", "application/json")
                .header("X-Meridian-Service", service).header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> res = http.send(r, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(expected);
        return json.readTree(res.body());
    }

    @Test
    @Order(16)
    void tenantWideEndOfDayRunsEveryLedgerOnceAndFiltersByLegalEntity() throws Exception {
        String entity2 = UUID.randomUUID().toString();
        String ledger2 = post("/v1/ledgers", """
                {"code":"SUB","name":"Subsidiary book","legal_entity_id":"%s","base_currency":"USD"}""".formatted(entity2), 201)
                .get("id").asString();
        post("/v1/ledger-accounts", """
                {"ledger_id":"%s","code":"9000-SUB","name":"Cash","account_type":"ASSET","normal_balance":"DEBIT","currency":"USD",
                 "allow_negative":true}""".formatted(ledger2), 201);
        String date = "2026-01-15";

        // per legal entity: only the subsidiary book
        JsonNode sub = post("/v1/ledgers/end-of-day", "{\"business_date\":\"" + date + "\",\"legal_entity_id\":\"" + entity2 + "\"}", 200);
        assertThat(sub.get("ledgers").asInt()).isEqualTo(1);
        assertThat(sub.get("runs")).hasSize(1);
        assertThat(sub.get("runs").get(0).get("ledger_id").asString()).isEqualTo(ledger2);
        assertThat(sub.get("runs").get(0).get("snapshots_written").asInt()).isEqualTo(1);

        // tenant-wide: the main book runs, the subsidiary is already closed for the date
        JsonNode all = post("/v1/ledgers/end-of-day", "{\"business_date\":\"" + date + "\"}", 200);
        assertThat(all.get("ledgers").asInt()).isEqualTo(2);
        assertThat(all.get("runs")).hasSize(1);
        assertThat(all.get("runs").get(0).get("ledger_id").asString()).isEqualTo(ledger);
        assertThat(all.get("skipped").get(0).get("ledger_id").asString()).isEqualTo(ledger2);
        assertThat(all.get("failed")).isEmpty();

        // retry is a no-op; one ledger (backward-compatible LEDGER_EOD with parameters.ledger_id)
        assertThat(post("/v1/ledgers/end-of-day", "{\"business_date\":\"" + date + "\"}", 200).get("runs")).isEmpty();
        JsonNode one = post("/v1/ledgers/end-of-day", "{\"business_date\":\"2026-01-16\",\"ledger_id\":\"" + ledger + "\"}", 200);
        assertThat(one.get("ledgers").asInt()).isEqualTo(1);
        assertThat(one.get("runs").get(0).get("ledger_id").asString()).isEqualTo(ledger);
        post("/v1/ledgers/end-of-day", "{\"business_date\":\"2026-01-16\",\"ledger_id\":\"" + UUID.randomUUID() + "\"}", 404);
        post("/v1/ledgers/end-of-day", "{}", 400);

        // another tenant sees none of these ledgers
        HttpResponse<String> other = send("POST", "/v1/ledgers/end-of-day", "{\"business_date\":\"" + date + "\"}",
                UUID.randomUUID().toString(), OTHER_TENANT);
        assertThat(other.statusCode()).as(other.body()).isEqualTo(200);
        assertThat(json.readTree(other.body()).get("ledgers").asInt()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private String account(String code, String name, String type, String normal, boolean allowNegative) throws Exception {
        return post("/v1/ledger-accounts", """
                {"ledger_id":"%s","code":"%s","name":"%s","account_type":"%s","normal_balance":"%s","currency":"USD",
                 "allow_negative":%s}""".formatted(ledger, code, name, type, normal, allowNegative), 201)
                .get("id").asString();
    }

    private static String line(String account, String direction, String amount) {
        return lineCcy(account, direction, amount, "USD");
    }

    private static String lineCcy(String account, String direction, String amount, String ccy) {
        return """
                {"ledger_account_id":"%s","direction":"%s","amount":"%s","currency":"%s"}"""
                .formatted(account, direction, amount, ccy);
    }

    private String entryBody(String... lines) {
        return """
                {"ledger_id":"%s","description":"test","entries":[%s]}""".formatted(ledger, String.join(",", lines));
    }

    private JsonNode postEntry(String key, String... lines) throws Exception {
        return postEntry(key, 201, lines);
    }

    private JsonNode postEntry(String key, int expected, String... lines) throws Exception {
        HttpResponse<String> r = send("POST", "/v1/journal-entries", entryBody(lines), key, TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private void assertBalance(String account, String posted, String available) throws Exception {
        JsonNode b = get("/v1/ledger-accounts/" + account + "/balance", TENANT);
        assertThat(new BigDecimal(b.get("posted_balance").asString())).as("posted").isEqualByComparingTo(posted);
        assertThat(new BigDecimal(b.get("available_balance").asString())).as("available").isEqualByComparingTo(available);
    }

    private BigDecimal bal(String account) throws Exception {
        return new BigDecimal(get("/v1/ledger-accounts/" + account + "/balance", TENANT).get("posted_balance").asString());
    }

    private String findEntryByKey(String key) throws Exception {
        for (JsonNode e : get("/v1/journal-entries?limit=200", TENANT).get("data")) {
            if (key.equals(e.path("idempotency_key").asString())) {
                return e.get("id").asString();
            }
        }
        throw new AssertionError("no entry with key " + key);
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
