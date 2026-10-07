package io.meridian.load;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Posting throughput test (gate G1: 1,000 TPS sustained). Creates a ledger, one funding account and N customer
 * accounts, funds them, then runs closed-loop workers posting random balanced transfers between customer
 * accounts for the given duration. Prints TPS, latency percentiles and errors, then asks the ledger to verify
 * itself (balances = sum of entries, gapless versions, balanced entries, content hashes).
 *
 * <p>{@code --with-refs true} posts every transfer the way payment-service does (RECON-REAL-REFS): with a reference
 * and {@code external_refs} (UETR, end-to-end id, instruction id, clearing and rail references, counterparty name and
 * account), i.e. typed reference columns on the journal and both lines, the partial reference indexes and the
 * counterparty account pseudonym on every posting.
 */
public final class LedgerLoadTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build();

    private final String base;
    private final boolean withRefs;
    private final String tenant = UUID.randomUUID().toString();

    private LedgerLoadTest(String base, boolean withRefs) {
        this.base = base;
        this.withRefs = withRefs;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new ConcurrentHashMap<>(Map.of("--base", "http://localhost:9110", "--seconds", "60",
                "--concurrency", "64", "--accounts", "200", "--target-tps", "1000", "--with-refs", "false"));
        for (int i = 0; i + 1 < args.length; i += 2) {
            a.put(args[i], args[i + 1]);
        }
        new LedgerLoadTest(a.get("--base"), Boolean.parseBoolean(a.get("--with-refs"))).run(Integer.parseInt(a.get("--seconds")),
                Integer.parseInt(a.get("--concurrency")), Integer.parseInt(a.get("--accounts")),
                Integer.parseInt(a.get("--target-tps")));
    }

    private void run(int seconds, int concurrency, int nAccounts, int targetTps) throws Exception {
        System.out.printf("tenant %s, %d accounts, %d workers, %ds, payment refs %s%n", tenant, nAccounts, concurrency,
                seconds, withRefs ? "on every posting" : "off");
        String ledger = post("/v1/ledgers", """
                {"code":"LOAD","name":"Load test","legal_entity_id":"%s","base_currency":"USD"}""".formatted(UUID.randomUUID()))
                .get("id").asString();
        String funding = account(ledger, "FUND", "ASSET", "DEBIT", true);
        List<String> accounts = new ArrayList<>();
        for (int i = 0; i < nAccounts; i++) {
            accounts.add(account(ledger, "C" + i, "LIABILITY", "CREDIT", false));
        }
        for (String acc : accounts) {
            post("/v1/journal-entries", entry(ledger, funding, "DEBIT", acc, "CREDIT", "100000.00"));
        }
        System.out.println("setup done; warming up 5s");
        drive(ledger, accounts, concurrency, 5, false);
        System.out.println("measuring...");
        Result r = drive(ledger, accounts, concurrency, seconds, true);

        long[] lat = r.latenciesMicros();
        Arrays.sort(lat);
        double tps = r.ok / (double) seconds;
        System.out.printf("%n=== Ledger posting load test ===%n");
        System.out.printf("postings ok     : %,d%n", r.ok);
        System.out.printf("errors          : %,d (insufficient funds counted as ok business rejections: %,d)%n",
                r.errors, r.rejected);
        System.out.printf("throughput      : %,.0f postings/s (target %,d)%n", tps, targetTps);
        System.out.printf("latency p50/p95/p99/max : %.1f / %.1f / %.1f / %.1f ms%n",
                pct(lat, 50) / 1000.0, pct(lat, 95) / 1000.0, pct(lat, 99) / 1000.0, lat[lat.length - 1] / 1000.0);
        JsonNode v = post("/v1/ledgers/" + ledger + "/verify", "");
        System.out.printf("verification    : passed=%s accounts=%s entries=%s mismatches(balance=%s, versions=%s, unbalanced=%s, hash=%s)%n",
                v.get("passed"), v.get("accounts_checked"), v.get("entries_checked"), v.get("balance_mismatches"),
                v.get("version_gaps"), v.get("unbalanced_entries"), v.get("hash_mismatches"));
        boolean gate = tps >= targetTps && r.errors == 0 && v.get("passed").asBoolean();
        System.out.println(gate ? "GATE PASSED" : "GATE FAILED");
        System.exit(gate ? 0 : 1);
    }

    record Result(long ok, long errors, long rejected, long[] latenciesMicros) {}

    private Result drive(String ledger, List<String> accounts, int concurrency, int seconds, boolean record)
            throws InterruptedException {
        LongAdder ok = new LongAdder();
        LongAdder errors = new LongAdder();
        LongAdder rejected = new LongAdder();
        long[] latencies = new long[4_000_000];
        AtomicLong idx = new AtomicLong();
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < concurrency; w++) {
                pool.submit(() -> {
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    while (System.nanoTime() < end) {
                        String from = accounts.get(rnd.nextInt(accounts.size()));
                        String to = accounts.get(rnd.nextInt(accounts.size()));
                        if (from.equals(to)) {
                            continue;
                        }
                        long t0 = System.nanoTime();
                        try {
                            String body = entry(ledger, from, "DEBIT", to, "CREDIT", "1.00");
                            if (withRefs) {
                                body = withRefs(body, rnd);
                            }
                            HttpResponse<String> res = send("/v1/journal-entries", body, UUID.randomUUID().toString());
                            long micros = (System.nanoTime() - t0) / 1000;
                            if (res.statusCode() == 201) {
                                ok.increment();
                            } else if (res.statusCode() == 422) {
                                rejected.increment();
                            } else {
                                errors.increment();
                            }
                            if (record) {
                                long i = idx.getAndIncrement();
                                if (i < latencies.length) {
                                    latencies[(int) i] = micros;
                                }
                            }
                        } catch (Exception e) {
                            errors.increment();
                        }
                    }
                    return null;
                });
            }
        }
        int n = (int) Math.min(idx.get(), latencies.length);
        return new Result(ok.sum(), errors.sum(), rejected.sum(), Arrays.copyOf(latencies, Math.max(n, 1)));
    }

    private static long pct(long[] sorted, int p) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p / 100.0 * sorted.length) - 1)];
    }

    private String account(String ledger, String code, String type, String normal, boolean allowNegative) throws Exception {
        return post("/v1/ledger-accounts", """
                {"ledger_id":"%s","code":"%s","name":"%s","account_type":"%s","normal_balance":"%s","currency":"USD",
                 "allow_negative":%s}""".formatted(ledger, code, code, type, normal, allowNegative)).get("id").asString();
    }

    private static String entry(String ledger, String a, String da, String b, String db, String amount) {
        return """
                {"ledger_id":"%s","entries":[{"ledger_account_id":"%s","direction":"%s","amount":"%s","currency":"USD"},
                 {"ledger_account_id":"%s","direction":"%s","amount":"%s","currency":"USD"}]}"""
                .formatted(ledger, a, da, amount, b, db, amount);
    }

    /** The posting as payment-service sends it: reference = end-to-end id plus external_refs. */
    private static String withRefs(String entry, ThreadLocalRandom rnd) {
        String e2e = "E2E" + Long.toString(rnd.nextLong() & Long.MAX_VALUE, 36).toUpperCase();
        String refs = """
                ,"reference":"%s","external_refs":{"uetr":"%s","end_to_end_id":"%s","instruction_id":"INS%d",
                 "bank_reference":"SCT%d","scheme_reference":"TX%d","counterparty_name":"Acme Supplies Ltd",
                 "counterparty_account":"DE89370400440532%06d","purpose_code":"SUPP"}}""".formatted(e2e, UUID.randomUUID(),
                e2e, rnd.nextInt(1_000_000_000), rnd.nextLong(1_000_000_000_000L), rnd.nextLong(1_000_000_000_000L),
                rnd.nextInt(1_000_000));
        return entry.substring(0, entry.lastIndexOf('}')) + refs;
    }

    private JsonNode post(String path, String body) throws Exception {
        HttpResponse<String> r = send(path, body, UUID.randomUUID().toString());
        if (r.statusCode() >= 300) {
            throw new IllegalStateException(path + " -> " + r.statusCode() + " " + r.body());
        }
        return JSON.readTree(r.body());
    }

    private HttpResponse<String> send(String path, String body, String key) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json").header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
