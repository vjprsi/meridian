package io.meridian.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Data lifecycle over HTTP against PostgreSQL 18 (RLS on): classification catalogue and scan, retention policies
 * with preview, WORM archive runs with verification and restore, legal holds, and four-eyes purges blocked by
 * holds with a certificate of destruction. Owning services are stubbed through {@link ServiceClient}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev", "meridian.archive.batch-size=2"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ArchiveFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000a1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000a2";
    static final String C1 = "0199a000-0000-7000-8000-00000000c001";
    static final String C2 = "0199a000-0000-7000-8000-00000000c002";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final AtomicInteger extracts = new AtomicInteger();
    final AtomicInteger confirms = new AtomicInteger();
    String txnClass;
    String customerClass;
    String txnPolicy;
    String customerPolicy;
    String objectId;
    String heldPurge;
    String holdId;

    @BeforeEach
    void stubs() {
        when(serviceClient.post(anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            String path = inv.getArgument(1);
            return switch (path) {
                case "/v1/internal/data-lifecycle/count" -> json.readTree("{\"count\":42}");
                case "/v1/internal/data-lifecycle/extract" -> switch (extracts.incrementAndGet()) {
                    case 1 -> json.readTree("""
                            {"records":[{"id":"0199a000-0000-7000-8000-0000000000d1","created_at":"2024-01-02T10:00:00Z","amount":"10.00"},
                                        {"id":"0199a000-0000-7000-8000-0000000000d2","created_at":"2024-03-04T10:00:00Z","amount":"5.00"}],
                             "skipped_hold":1,"has_more":true}""");
                    case 2 -> json.readTree("""
                            {"records":[{"id":"0199a000-0000-7000-8000-0000000000d3","created_at":"2024-05-06T10:00:00Z","amount":"1.00"}],"has_more":false}""");
                    default -> json.readTree("{\"records\":[]}");
                };
                case "/v1/internal/data-lifecycle/confirm" -> {
                    confirms.incrementAndGet();
                    yield json.createObjectNode();
                }
                case "/v1/internal/data-lifecycle/purge" -> json.readTree("{\"records_purged\":17}");
                default -> throw new AssertionError("unexpected call " + path);
            };
        });
    }

    @Test
    @Order(1)
    void classificationCatalogue() throws Exception {
        assertThat(post("/v1/archive/classifications", classification("TRANSACTION", "no-such-service", "ledger_db", "journal_entry",
                "CONFIDENTIAL", ""), 422).get("code").asString()).isEqualTo("unknown_service");
        assertThat(post("/v1/archive/classifications", classification("CUSTOMER", "customer-service", "customer_db", "customer",
                "INTERNAL", ",\"pii_columns\":{\"columns\":[\"email\"]}"), 422).get("code").asString()).isEqualTo("sensitivity_too_low");
        txnClass = post("/v1/archive/classifications", classification("TRANSACTION", "ledger-service", "ledger_db", "journal_entry",
                "CONFIDENTIAL", ""), 201).get("id").asString();
        customerClass = post("/v1/archive/classifications", classification("CUSTOMER", "customer-service", "customer_db", "customer",
                "PII", ",\"pii_columns\":{\"columns\":[\"email\",\"full_name\"]}"), 201).get("id").asString();
        post("/v1/archive/classifications", classification("TRANSACTION", "ledger-service", "ledger_db", "journal_entry",
                "CONFIDENTIAL", ""), 409);

        JsonNode scan = post("/v1/archive/classifications/scan", null, 200);
        assertThat(scan.get("tables_scanned").asInt()).isGreaterThan(100);
        assertThat(scan.get("created").asInt()).isZero();
        boolean found = false;
        for (JsonNode s : scan.get("suggestions")) {
            if (s.get("database_name").asString().equals("ledger_db") && s.get("table_name").asString().equals("journal_entry")) {
                found = true;
                assertThat(s.get("already_classified").asBoolean()).isTrue();
                assertThat(s.get("suggested_data_class").asString()).isEqualTo("TRANSACTION");
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    @Order(2)
    void retentionPoliciesAndPreview() throws Exception {
        assertThat(post("/v1/archive/retention-policies", policy("TRANSACTION", 0, 365), 422).get("code").asString())
                .isEqualTo("invalid_retention_years");
        txnPolicy = post("/v1/archive/retention-policies", policy("TRANSACTION", 7, 365), 201).get("id").asString();
        assertThat(post("/v1/archive/retention-policies", policy("TRANSACTION", 10, 365), 409).get("code").asString())
                .isEqualTo("policy_exists");
        customerPolicy = post("/v1/archive/retention-policies", policy("CUSTOMER", 5, 90), 201).get("id").asString();
        JsonNode preview = post("/v1/archive/retention-policies/" + txnPolicy + "/preview", null, 200);
        assertThat(preview.get("archive_cutoff_date").asString()).isEqualTo(LocalDate.now().minusDays(365).toString());
        assertThat(preview.get("purge_cutoff_date").asString()).isEqualTo(LocalDate.now().minusYears(7).toString());
        assertThat(preview.get("total_archivable").asLong()).isEqualTo(42);
        assertThat(preview.get("tables")).hasSize(1);
    }

    @Test
    @Order(3)
    void archiveRunWritesWormObjectsThatVerifyAndRestore() throws Exception {
        assertThat(post("/v1/archive/jobs", job(LocalDate.now().minusDays(10)), 422).get("code").asString())
                .isEqualTo("cutoff_within_online_period");
        JsonNode job = post("/v1/archive/jobs", job(LocalDate.now().minusDays(400)), 201);
        String jobId = job.get("id").asString();
        JsonNode done = awaitJob(jobId);
        assertThat(done.get("status").asString()).as(done.toString()).isEqualTo("SUCCEEDED");
        assertThat(done.get("records_archived").asLong()).isEqualTo(3);
        assertThat(done.get("records_skipped_hold").asLong()).isEqualTo(1);
        assertThat(done.get("bytes_archived").asLong()).isPositive();
        assertThat(confirms.get()).isEqualTo(2);

        JsonNode objects = get("/v1/archive/jobs/" + jobId + "/objects", TENANT).get("data");
        assertThat(objects).hasSize(2);
        JsonNode first = objects.get(objects.size() - 1);
        objectId = first.get("id").asString();
        assertThat(first.get("checksum_sha256").asString()).hasSize(64);
        assertThat(first.has("encryption_key_ref")).isFalse(); // secret column never exposed
        assertThat(first.get("worm_retain_until").asString()).isEqualTo("2031-03-04");
        assertThat(first.get("record_count").asLong()).isEqualTo(2);

        JsonNode verify = post("/v1/archive/objects/" + objectId + "/verify", null, 200);
        assertThat(verify.get("valid").asBoolean()).as(verify.toString()).isTrue();
        assertThat(verify.get("object_lock").get("compliant").asBoolean()).isTrue();
        JsonNode restore = post("/v1/archive/objects/" + objectId + "/restore", "{\"reason\":\"regulator request 42\"}", 200);
        assertThat(restore.get("restored_to").asString()).startsWith("query-area://");
        assertThat(restore.get("record_count").asLong()).isEqualTo(2);
        assertThat(post("/v1/archive/jobs/" + jobId + "/cancel", null, 409).get("code").asString()).isEqualTo("invalid_transition");
        // the manifest table is append-only (WORM)
        Integer blocked = io.meridian.common.tenancy.TenantContext.callAs(UUID.fromString(TENANT), "t", () -> {
            try {
                return jdbc.sql("update archived_object set record_count = 0 where id = ?::uuid").param(objectId).update();
            } catch (org.springframework.dao.DataAccessException e) {
                return -1;
            }
        });
        assertThat(blocked).isEqualTo(-1);
    }

    @Test
    @Order(4)
    void legalHoldsAreChecked() throws Exception {
        JsonNode hold = send("POST", "/v1/archive/legal-holds", """
                {"name":"Matter 2026-17","scope_type":"CUSTOMER","scope_ids":{"ids":["%s"]},"reason":"Litigation",
                 "requested_by":"Legal","case_reference":"HC-2026-17","starts_on":"%s"}""".formatted(C1, LocalDate.now().minusDays(1)),
                "legal", 201);
        holdId = hold.get("id").asString();
        assertThat(hold.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(hold.get("customer_id").asString()).isEqualTo(C1);
        assertThat(get("/v1/archive/legal-holds/check?scope_type=CUSTOMER&id=" + C1, TENANT).get("on_hold").asBoolean()).isTrue();
        assertThat(get("/v1/archive/legal-holds/check?scope_type=CUSTOMER&id=" + C2, TENANT).get("on_hold").asBoolean()).isFalse();
        HttpResponse<String> shrink = raw("PATCH", "/v1/archive/legal-holds/" + holdId, "{\"scope_ids\":{\"ids\":[]}}", null, TENANT,
                "legal", hold.get("row_version").asString());
        assertThat(shrink.statusCode()).isEqualTo(422);
        assertThat(json.readTree(shrink.body()).get("code").asString()).isEqualTo("hold_scope_reduction");
    }

    @Test
    @Order(5)
    void purgeNeedsFourEyesAndIsBlockedByHolds() throws Exception {
        assertThat(post("/v1/archive/purge-requests", purge("{\"customer_ids\":[\"" + C2 + "\"]}", "ARCHIVE"), 422)
                .get("code").asString()).isEqualTo("invalid_purge_action");
        assertThat(post("/v1/archive/purge-requests", purge("{\"created_before\":\"" + LocalDate.now().minusYears(1) + "\"}", "PURGE"), 422)
                .get("code").asString()).isEqualTo("retention_not_expired");

        heldPurge = send("POST", "/v1/archive/purge-requests", purge("{\"customer_ids\":[\"" + C1 + "\"]}", "PURGE"), "ops", 201)
                .get("id").asString();
        assertThat(send("POST", "/v1/archive/purge-requests/" + heldPurge + "/submit", null, "ops", 200).get("status").asString())
                .isEqualTo("BLOCKED_BY_HOLD");
        assertThat(send("POST", "/v1/archive/purge-requests/" + heldPurge + "/execute", null, "ops", 409).get("code").asString())
                .isEqualTo("blocked_by_legal_hold");

        JsonNode p = send("POST", "/v1/archive/purge-requests",
                purge("{\"created_before\":\"2015-01-01\",\"customer_ids\":[\"" + C2 + "\"]}", "ANONYMISE"), "ops", 201);
        String id = p.get("id").asString();
        assertThat(p.get("status").asString()).isEqualTo("DRAFT");
        assertThat(send("POST", "/v1/archive/purge-requests/" + id + "/execute", null, "ops", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        JsonNode submitted = send("POST", "/v1/archive/purge-requests/" + id + "/submit", null, "ops", 200);
        assertThat(submitted.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(submitted.get("estimated_records").asLong()).isEqualTo(42);
        assertThat(send("POST", "/v1/archive/purge-requests/" + id + "/approve", null, "ops", 422).get("code").asString())
                .isEqualTo("four_eyes_violation");
        assertThat(send("POST", "/v1/archive/purge-requests/" + id + "/approve", null, "dpo", 200).get("status").asString())
                .isEqualTo("APPROVED");

        JsonNode executed = send("POST", "/v1/archive/purge-requests/" + id + "/execute", null, "ops", 200);
        assertThat(executed.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(executed.get("records_purged").asLong()).isEqualTo(17);
        assertThat(executed.get("certificate_hash").asString()).hasSize(64);
        JsonNode cert = get("/v1/archive/purge-requests/" + id + "/certificate", TENANT);
        assertThat(cert.get("hash_verified").asBoolean()).isTrue();
        assertThat(cert.get("certificate").get("approved_by").asString()).isEqualTo("dpo");
        assertThat(cert.get("certificate").get("records_purged").asLong()).isEqualTo(17);
        assertThat(cert.get("certificate").get("legal_hold_exclusions").get("customer_ids").get(0).asString()).isEqualTo(C1);
        assertThat(send("POST", "/v1/archive/purge-requests/" + id + "/execute", null, "ops", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(send("POST", "/v1/archive/purge-requests/" + id + "/cancel", "{\"reason\":\"late\"}", "ops", 409)
                .get("code").asString()).isEqualTo("invalid_transition");
        assertThat(send("GET", "/v1/archive/purge-requests/" + heldPurge + "/certificate", null, "ops", 409).get("code").asString())
                .isEqualTo("certificate_not_available");
    }

    @Test
    @Order(6)
    void releasingTheHoldUnblocksThePurgeForReapproval() throws Exception {
        assertThat(send("POST", "/v1/archive/legal-holds/" + holdId + "/release", "{\"reason\":\"case closed\"}", "legal", 422)
                .get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode released = send("POST", "/v1/archive/legal-holds/" + holdId + "/release", "{\"reason\":\"case closed\"}", "dpo", 200);
        assertThat(released.get("status").asString()).isEqualTo("RELEASED");
        assertThat(released.get("released_by").asString()).isEqualTo("dpo");
        assertThat(send("POST", "/v1/archive/legal-holds/" + holdId + "/release", "{\"reason\":\"x\"}", "dpo", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(get("/v1/archive/purge-requests/" + heldPurge, TENANT).get("status").asString()).isEqualTo("PENDING_APPROVAL");
        send("POST", "/v1/archive/purge-requests/" + heldPurge + "/approve", null, "dpo", 200);
        assertThat(send("POST", "/v1/archive/purge-requests/" + heldPurge + "/execute", null, "ops", 200).get("status").asString())
                .isEqualTo("COMPLETED");

        String third = send("POST", "/v1/archive/purge-requests", purge("{\"customer_ids\":[\"" + C2 + "\"]}", "PURGE"), "ops", 201)
                .get("id").asString();
        send("POST", "/v1/archive/purge-requests/" + third + "/submit", null, "ops", 200);
        assertThat(send("POST", "/v1/archive/purge-requests/" + third + "/reject", "{\"reason\":\"not needed\"}", "dpo", 200)
                .get("status").asString()).isEqualTo("REJECTED");
        assertThat(send("POST", "/v1/archive/purge-requests/" + third + "/cancel", "{\"reason\":\"x\"}", "ops", 409)
                .get("code").asString()).isEqualTo("invalid_transition");
    }

    @Test
    @Order(7)
    void eventsAndTenantIsolation() throws Exception {
        List<String> types = jdbc.sql("select distinct event_type from outbox").query(String.class).list();
        assertThat(types).contains("operations.archive_job.completed", "operations.legal_hold.placed",
                "operations.legal_hold.released", "operations.purge_request.approved", "operations.purge_request.completed");
        assertThat(raw("GET", "/v1/archive/objects/" + objectId, null, null, OTHER_TENANT, "x", null).statusCode()).isEqualTo(404);
        assertThat(raw("POST", "/v1/archive/purge-requests/" + heldPurge + "/execute", null, UUID.randomUUID().toString(),
                OTHER_TENANT, "x", null).statusCode()).isEqualTo(404);
        assertThat(json.readTree(raw("GET", "/v1/archive/legal-holds/check?scope_type=TENANT&id=x", null, null, OTHER_TENANT, "x",
                null).body()).get("on_hold").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private static String classification(String cls, String service, String db, String table, String sensitivity, String extra) {
        return """
                {"data_class":"%s","service_name":"%s","database_name":"%s","table_name":"%s","sensitivity":"%s"%s}"""
                .formatted(cls, service, db, table, sensitivity, extra);
    }

    private static String policy(String cls, int years, int onlineDays) {
        return """
                {"data_class":"%s","retention_years":%d,"online_days":%d,"valid_from":"2020-01-01",
                 "regulation_reference":"AML record keeping"}""".formatted(cls, years, onlineDays);
    }

    private String job(LocalDate cutoff) {
        return """
                {"retention_policy_id":"%s","data_classification_id":"%s","cutoff_date":"%s"}"""
                .formatted(txnPolicy, txnClass, cutoff);
    }

    private String purge(String selector, String action) {
        return """
                {"retention_policy_id":"%s","data_classification_id":"%s","scope_description":"Customer records",
                 "record_selector":%s,"action":"%s"}""".formatted(customerPolicy, customerClass, selector, action);
    }

    private JsonNode awaitJob(String id) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode j = get("/v1/archive/jobs/" + id, TENANT);
            String s = j.get("status").asString();
            if (!s.equals("QUEUED") && !s.equals("RUNNING")) {
                return j;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("job " + id + " did not finish");
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        return send("POST", path, body, "dev-user", expected);
    }

    private JsonNode send(String method, String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = raw(method, path, body, "POST".equals(method) ? UUID.randomUUID().toString() : null, TENANT, user, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = raw("GET", path, null, null, tenant, "dev-user", null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> raw(String method, String path, String body, String idemKey, String tenant, String user,
                                     String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
