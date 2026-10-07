package io.meridian.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.meridian.audit.application.AuditIntegrityJobs;
import io.meridian.audit.application.AuditRecorder;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GAP-07 end to end against PostgreSQL 18 and an S3-compatible fake: personal data is masked in stored states, the
 * chain is verified (scheduled and on demand), yesterday's events are sealed to WORM storage with Object Lock headers
 * and a manifest, and tampering with storage or with the database is detected and alerted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.audit.listener-enabled=false", "meridian.approvals.listener-enabled=false",
                "meridian.audit.integrity.cron=-"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditIntegrityIT {
    static final UUID TENANT = UUID.randomUUID();
    static final FakeS3 S3 = FakeS3.start();

    @DynamicPropertySource
    static void s3(DynamicPropertyRegistry r) {
        r.add("meridian.audit.worm.endpoint", () -> "http://localhost:" + S3.port());
        r.add("meridian.audit.worm.object-lock-mode", () -> "COMPLIANCE");
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    AuditRecorder recorder;

    @Autowired
    AuditIntegrityJobs jobs;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    MeterRegistry meters;

    @Autowired
    Environment env;

    final HttpClient http = HttpClient.newHttpClient();
    String sealId;

    @AfterAll
    void stop() {
        S3.stop();
    }

    @Test
    @Order(1)
    void personalDataIsMaskedBeforeItIsHashedAndStored() throws Exception {
        for (int i = 1; i <= 3; i++) {
            JsonNode state = json.readTree("""
                    {"id":"%s","first_name":"Jane","last_name":"Doe","status":"ACTIVE","email":"jane%d@example.org",
                     "collected_data":{"phone":"+447700900123"}}""".formatted(UUID.randomUUID(), i));
            TenantContext.callAs(TENANT, "audit-test", () -> recorder.record(new AuditRecorder.NewAuditEvent(null, "SYSTEM",
                    "system", null, null, "party.customer.updated", "customer", null, "SUCCESS", null, null, state, null,
                    null, null, null, null, null, null, "customer-service", null)));
        }
        String stored = TenantContext.callAs(TENANT, "audit-test", () -> jdbc.sql(
                "select string_agg(after_state::text, ' ') from audit_event").query(String.class).single());
        assertThat(stored).doesNotContain("Jane").doesNotContain("Doe").doesNotContain("example.org")
                .doesNotContain("7700900123").contains("psn:").contains("ACTIVE");
    }

    @Test
    @Order(2)
    void scheduledRunVerifiesTheChainAndSealsYesterdayToWormStorage() throws Exception {
        // the events were recorded "yesterday" (created_at is not part of the hash)
        admin("update audit_event set created_at = created_at - interval '1 day' where tenant_id = ?::uuid");
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        jobs.runFor(TENANT, yesterday);

        JsonNode runs = get("/v1/audit-chain-verifications").get("data");
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).get("valid").asBoolean()).isTrue();
        assertThat(runs.get(0).get("checked").asLong()).isEqualTo(3);
        assertThat(runs.get(0).get("trigger").asString()).isEqualTo("SCHEDULED");

        JsonNode seal = get("/v1/audit-seals").get("data").get(0);
        sealId = seal.get("id").asString();
        assertThat(seal.get("seal_date").asString()).isEqualTo(yesterday.toString());
        assertThat(seal.get("event_count").asInt()).isEqualTo(3);
        assertThat(seal.get("from_sequence").asLong()).isEqualTo(1);
        assertThat(seal.get("to_sequence").asLong()).isEqualTo(3);
        assertThat(seal.get("object_lock_mode").asString()).isEqualTo("COMPLIANCE");
        String key = "/meridian-audit/audit/" + TENANT + "/" + yesterday.getYear() + "/"
                + "%02d/%02d/".formatted(yesterday.getMonthValue(), yesterday.getDayOfMonth());
        assertThat(S3.objects).containsKeys(key + "events.jsonl", key + "manifest.json");
        Map<String, String> put = S3.headers.get(key + "manifest.json");
        assertThat(put.get("x-amz-object-lock-mode")).isEqualTo("COMPLIANCE");
        assertThat(put.get("x-amz-object-lock-retain-until-date")).startsWith(yesterday.plusYears(10).toString());
        assertThat(put.get("authorization")).startsWith("AWS4-HMAC-SHA256 Credential=");
        JsonNode manifest = json.readTree(S3.objects.get(key + "manifest.json"));
        assertThat(manifest.get("content_sha256").asString()).isEqualTo(seal.get("content_sha256").asString());
        assertThat(manifest.get("last_hash").asString()).isEqualTo(seal.get("last_hash").asString());
        assertThat(outbox("platform.audit_seal.created")).isEqualTo(1);

        // sealing the same day again returns the seal; the verification of an intact seal passes
        assertThat(post("/v1/audit-seals/run", "{\"seal_date\":\"" + yesterday + "\"}", 200).get("id").asString()).isEqualTo(sealId);
        JsonNode v = post("/v1/audit-seals/" + sealId + "/verify", null, 200);
        assertThat(v.get("valid").asBoolean()).as(v.toString()).isTrue();
        assertThat(v.get("checks").get("database_matches").asBoolean()).isTrue();
    }

    @Test
    @Order(3)
    void onDemandVerificationIsIncremental() throws Exception {
        TenantContext.callAs(TENANT, "audit-test", () -> recorder.record(new AuditRecorder.NewAuditEvent(null, "SYSTEM", "system",
                null, null, "payments.payment.completed", "payment", null, "SUCCESS", null, null,
                json.readTree("{\"amount\":\"10.00\"}"), null, null, null, null, null, null, null, "payment-service", null)));
        JsonNode run = post("/v1/audit-chain-verifications/run", "{}", 200);
        assertThat(run.get("valid").asBoolean()).isTrue();
        assertThat(run.get("from_sequence").asLong()).isEqualTo(4);
        assertThat(run.get("checked").asLong()).isEqualTo(1);
        assertThat(run.get("trigger").asString()).isEqualTo("ON_DEMAND");
    }

    @Test
    @Order(4)
    void tamperingWithTheSealedObjectIsDetected() throws Exception {
        String key = S3.objects.keySet().stream().filter(k -> k.endsWith("events.jsonl")).findFirst().orElseThrow();
        byte[] original = S3.objects.get(key);
        S3.objects.put(key, new String(original, StandardCharsets.UTF_8).replace("ACTIVE", "CLOSED").getBytes(StandardCharsets.UTF_8));
        double before = meters.counter("meridian.audit.seal.tampered").count();
        JsonNode v = post("/v1/audit-seals/" + sealId + "/verify", null, 200);
        assertThat(v.get("valid").asBoolean()).isFalse();
        assertThat(v.get("checks").get("content_hash").asBoolean()).isFalse();
        assertThat(meters.counter("meridian.audit.seal.tampered").count()).isEqualTo(before + 1);
        assertThat(outbox("platform.audit_seal.tampered")).isEqualTo(1);
        S3.objects.put(key, original);
    }

    @Test
    @Order(5)
    void tamperingWithTheDatabaseIsDetectedByTheSealAndTheFullVerification() throws Exception {
        admin("update audit_event set outcome = 'FAILURE' where tenant_id = ?::uuid and sequence_no = 2");
        JsonNode v = post("/v1/audit-seals/" + sealId + "/verify", null, 200);
        assertThat(v.get("valid").asBoolean()).isFalse();
        assertThat(v.get("checks").get("database_matches").asBoolean()).isFalse();
        assertThat(v.get("problems").toString()).contains("stored event 2 differs");

        double before = meters.counter("meridian.audit.chain.breaks").count();
        JsonNode run = post("/v1/audit-chain-verifications/run", "{\"full\":true}", 200);
        assertThat(run.get("valid").asBoolean()).isFalse();
        assertThat(run.get("first_broken_sequence").asLong()).isEqualTo(2);
        assertThat(run.get("break_reason").asString()).isEqualTo("HASH_MISMATCH");
        assertThat(meters.counter("meridian.audit.chain.breaks").count()).isEqualTo(before + 1);
        assertThat(outbox("platform.audit_chain.broken")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private void admin(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(env.getProperty("spring.flyway.url"), "meridian", "meridian")) {
            try (Statement s = c.createStatement()) {
                s.execute("set session_replication_role = replica"); // bypasses the append-only trigger
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, TENANT.toString());
                assertThat(ps.executeUpdate()).isPositive();
            }
        }
    }

    private long outbox(String type) {
        return jdbc.sql("select count(*) from outbox where event_type = ? and tenant_id = ?").params(type, TENANT)
                .query(Long.class).single();
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT.toString()).header("X-User", "auditor").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT.toString()).header("X-User", "auditor").header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    /** Minimal S3 stand-in: stores PUT bodies (checking Content-MD5 and the signature header), serves GETs. */
    static final class FakeS3 {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final Map<String, Map<String, String>> headers = new ConcurrentHashMap<>();
        private HttpServer server;

        static FakeS3 start() {
            FakeS3 f = new FakeS3();
            try {
                f.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            f.server.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                byte[] body = ex.getRequestBody().readAllBytes();
                int status;
                byte[] out = new byte[0];
                if (ex.getRequestHeaders().getFirst("Authorization") == null) {
                    status = 403;
                } else if ("PUT".equals(ex.getRequestMethod())) {
                    String md5;
                    try {
                        md5 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body));
                    } catch (java.security.NoSuchAlgorithmException e) {
                        throw new IllegalStateException(e);
                    }
                    if (!md5.equals(ex.getRequestHeaders().getFirst("Content-MD5"))) {
                        status = 400;
                    } else {
                        Map<String, String> h = new ConcurrentHashMap<>();
                        ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), v.getFirst()));
                        f.headers.put(path, h);
                        f.objects.put(path, body);
                        status = 200;
                    }
                } else {
                    byte[] o = f.objects.get(path);
                    status = o == null ? 404 : 200;
                    out = o == null ? out : o;
                }
                ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
                if (out.length > 0) {
                    ex.getResponseBody().write(out);
                }
                ex.close();
            });
            f.server.start();
            return f;
        }

        int port() {
            return server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }
    }
}
