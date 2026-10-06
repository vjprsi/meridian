package io.meridian.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.meridian.audit.application.AuditRecorder;
import io.meridian.audit.application.DataExportService;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Audit log end to end over HTTP against PostgreSQL 18 with row-level security: hash-chained recording,
 * chain verification (including tamper detection), search, retention policies and maker-checker data exports.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.audit.listener-enabled=false", "meridian.approvals.listener-enabled=false"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000c1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000c2";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    AuditRecorder recorder;

    @Autowired
    DataExportService exports;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Environment env;

    @MockitoBean
    ServiceClient services;

    final HttpClient http = HttpClient.newHttpClient();
    final Instant start = Instant.now().minus(1, ChronoUnit.HOURS);

    @BeforeAll
    void seed() throws Exception {
        for (int i = 1; i <= 5; i++) {
            int n = i;
            ObjectNode before = json.createObjectNode();
            before.put("limit", 100 * n);
            before.putObject("nested").put("b", new java.math.BigDecimal("1.50")).put("a", "x");
            ObjectNode after = before.deepCopy().put("limit", 200 * n);
            as(TENANT, "svc", () -> recorder.record(new AuditRecorder.NewAuditEvent(
                    start.plusSeconds(n * 60L), "STAFF_USER", "alice", "Alice Admin", null,
                    n % 2 == 0 ? "platform.tenant.updated" : "payments.payment.approve",
                    n % 2 == 0 ? "tenant" : "payment", "res-" + n, "SUCCESS", "change " + n, before, after,
                    null, null, "10.1.2." + n, "JUnit", "sess-1", "req-" + n, null, "test-service")));
        }
        for (int i = 1; i <= 2; i++) {
            int n = i;
            as(OTHER_TENANT, "svc", () -> recorder.record(new AuditRecorder.NewAuditEvent(
                    null, "SYSTEM", "system", null, null, "platform.cell.drained", "cell", "c-" + n, "SUCCESS",
                    null, null, null, null, null, null, null, null, null, null, "tenant-service")));
        }
    }

    @BeforeEach
    void stubWorkflow() {
        when(services.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString()))
                .thenAnswer(inv -> json.createObjectNode().put("id", UUID.randomUUID().toString())
                        .put("status", "PENDING").put("maker", "maker"));
    }

    @Test
    @Order(1)
    void eventsAreSequencedAndHashChainedPerTenant() throws Exception {
        JsonNode data = get("/v1/audit-events?limit=50", TENANT).get("data");
        assertThat(data).hasSize(5);
        JsonNode first = byseq(data, 1);
        JsonNode second = byseq(data, 2);
        assertThat(first.get("prev_hash").asString()).isEqualTo("0".repeat(64));
        assertThat(second.get("prev_hash").asString()).isEqualTo(first.get("hash").asString());
        assertThat(first.get("hash").asString()).hasSize(64);

        JsonNode v = post("/v1/audit-events/verify-chain", "{\"from_sequence\":1,\"to_sequence\":5}", 200, TENANT);
        assertThat(v.get("valid").asBoolean()).isTrue();
        assertThat(v.get("checked").asInt()).isEqualTo(5);

        // Other tenant: independent chain starting at 1.
        JsonNode other = get("/v1/audit-events", OTHER_TENANT).get("data");
        assertThat(other).hasSize(2);
        assertThat(byseq(other, 1).get("prev_hash").asString()).isEqualTo("0".repeat(64));
        JsonNode ov = post("/v1/audit-events/verify-chain", "{\"from_sequence\":1,\"to_sequence\":10}", 200, OTHER_TENANT);
        assertThat(ov.get("valid").asBoolean()).isTrue();
        assertThat(ov.get("checked").asInt()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from outbox where event_type = 'platform.audit_event.recorded'")
                .query(Long.class).single()).isGreaterThanOrEqualTo(7L);
    }

    @Test
    @Order(2)
    void verifyChainValidatesRangeAndReplays() throws Exception {
        assertThat(code(send("POST", "/v1/audit-events/verify-chain", "{\"from_sequence\":0,\"to_sequence\":5}",
                key(), TENANT, null), 422)).isEqualTo("invalid_range");
        assertThat(code(send("POST", "/v1/audit-events/verify-chain", "{\"from_sequence\":5,\"to_sequence\":2}",
                key(), TENANT, null), 422)).isEqualTo("invalid_range");
        assertThat(code(send("POST", "/v1/audit-events/verify-chain", "{\"from_sequence\":1,\"to_sequence\":200001}",
                key(), TENANT, null), 422)).isEqualTo("range_too_large");
        assertThat(send("POST", "/v1/audit-events/verify-chain", "{\"from_sequence\":1}", key(), TENANT, null)
                .statusCode()).isEqualTo(400);

        String k = key();
        HttpResponse<String> first = send("POST", "/v1/audit-events/verify-chain",
                "{\"from_sequence\":2,\"to_sequence\":4}", k, TENANT, null);
        assertThat(first.statusCode()).isEqualTo(200);
        HttpResponse<String> replay = send("POST", "/v1/audit-events/verify-chain",
                "{\"from_sequence\":2,\"to_sequence\":4}", k, TENANT, null);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("checked").asInt()).isEqualTo(3);
        assertThat(code(send("POST", "/v1/audit-events/verify-chain", "{\"from_sequence\":1,\"to_sequence\":4}",
                k, TENANT, null), 422)).isEqualTo("idempotency_key_reused");
    }

    @Test
    @Order(3)
    void searchFiltersAndPages() throws Exception {
        JsonNode payments = post("/v1/audit-events/search", "{\"query\":{\"action\":\"payments.*\"}}", 200, TENANT);
        assertThat(payments.get("data")).hasSize(3);
        JsonNode text = post("/v1/audit-events/search", "{\"query\":{\"text\":\"change 4\"}}", 200, TENANT);
        assertThat(text.get("data")).hasSize(1);
        assertThat(text.get("data").get(0).get("resource_id").asString()).isEqualTo("res-4");
        JsonNode window = post("/v1/audit-events/search", """
                {"query":{"actor_id":"alice","outcome":"SUCCESS","from":"%s","to":"%s"}}"""
                .formatted(start.plusSeconds(90), start.plusSeconds(200)), 200, TENANT);
        assertThat(window.get("data")).hasSize(2);

        JsonNode page1 = post("/v1/audit-events/search?limit=2", "{\"query\":{\"actor_id\":\"alice\"}}", 200, TENANT);
        assertThat(page1.get("has_more").asBoolean()).isTrue();
        JsonNode page3 = post("/v1/audit-events/search?limit=2&cursor=" + page1.get("next_cursor").asString(),
                "{\"query\":{\"actor_id\":\"alice\"}}", 200, TENANT);
        assertThat(page3.get("data")).hasSize(2);
        assertThat(page3.get("data").get(0).get("id").asString())
                .isNotEqualTo(page1.get("data").get(0).get("id").asString());

        assertThat(code(send("POST", "/v1/audit-events/search", "{\"query\":{\"password\":\"x\"}}", key(), TENANT, null),
                400)).isEqualTo("unknown_filter");
        assertThat(code(send("POST", "/v1/audit-events/search", "{\"query\":{\"outcome\":\"MAYBE\"}}", key(), TENANT, null),
                400)).isEqualTo("invalid_value");
        // Tenant isolation: the other tenant's search sees only its own events.
        assertThat(post("/v1/audit-events/search", "{\"query\":{\"actor_id\":\"alice\"}}", 200, OTHER_TENANT)
                .get("data")).isEmpty();
    }

    @Test
    @Order(4)
    void otherTenantCannotReadEvents() throws Exception {
        String id = byseq(get("/v1/audit-events", TENANT).get("data"), 1).get("id").asString();
        assertThat(send("GET", "/v1/audit-events/" + id, null, null, OTHER_TENANT, null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/v1/audit-events/" + id, null, null, TENANT, null).statusCode()).isEqualTo(200);
    }

    @Test
    @Order(5)
    void retentionPoliciesAreValidated() throws Exception {
        String k = key();
        HttpResponse<String> created = send("POST", "/v1/audit-retention-policies",
                "{\"resource_type\":\"payment\",\"retention_years\":10,\"archive_after_days\":365,\"legal_basis\":\"AML Act s.7\"}",
                k, TENANT, null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String id = json.readTree(created.body()).get("id").asString();
        HttpResponse<String> replay = send("POST", "/v1/audit-retention-policies",
                "{\"resource_type\":\"payment\",\"retention_years\":10,\"archive_after_days\":365,\"legal_basis\":\"AML Act s.7\"}",
                k, TENANT, null);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(id);

        assertThat(code(send("POST", "/v1/audit-retention-policies",
                "{\"resource_type\":\"payment\",\"retention_years\":5}", key(), TENANT, null), 409)).isEqualTo("duplicate");
        assertThat(code(send("POST", "/v1/audit-retention-policies",
                "{\"resource_type\":\"loan\",\"retention_years\":0}", key(), TENANT, null), 422))
                .isEqualTo("invalid_retention_years");
        assertThat(code(send("POST", "/v1/audit-retention-policies",
                "{\"resource_type\":\"loan\",\"retention_years\":1,\"archive_after_days\":400}", key(), TENANT, null), 422))
                .isEqualTo("invalid_archive_after_days");

        HttpResponse<String> bad = sendWith("PATCH", "/v1/audit-retention-policies/" + id,
                "{\"retention_years\":1}", "If-Match", "1", TENANT);
        assertThat(bad.statusCode()).as(bad.body()).isEqualTo(422);
        HttpResponse<String> ok = sendWith("PATCH", "/v1/audit-retention-policies/" + id,
                "{\"retention_years\":7}", "If-Match", "1", TENANT);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(json.readTree(ok.body()).get("retention_years").asInt()).isEqualTo(7);
        // The same resource type is free for another tenant.
        assertThat(send("POST", "/v1/audit-retention-policies", "{\"resource_type\":\"payment\"}", key(),
                OTHER_TENANT, null).statusCode()).isEqualTo(201);
    }

    @Test
    @Order(6)
    void dataExportNeedsSecondPersonApproval() throws Exception {
        String body = """
                {"export_type":"AUDIT_LOG","requested_for":"Central Bank inspection","from_time":"%s","to_time":"%s"}"""
                .formatted(start, Instant.now());
        String k = key();
        HttpResponse<String> r = send("POST", "/v1/data-exports", body, k, TENANT, "maker");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        JsonNode export = json.readTree(r.body());
        String id = export.get("id").asString();
        assertThat(export.get("status").asString()).isEqualTo("QUEUED");
        String approvalId = export.get("approval_request_id").asString();
        verify(services).post(eq("workflow-service"), eq("/v1/approval-requests"),
                argThat(b -> ((JsonNode) b).get("resource_type").asString().equals("data_export_request")
                        && ((JsonNode) b).get("resource_id").asString().equals(id)),
                eq("audit-service:" + k));

        HttpResponse<String> replay = send("POST", "/v1/data-exports", body, k, TENANT, "maker");
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(id);

        assertThat(code(send("GET", "/v1/data-exports/" + id + "/download-url", null, null, TENANT, null), 409))
                .isEqualTo("export_not_ready");

        ObjectNode approval = json.createObjectNode().put("id", approvalId).put("resource_type", "data_export_request")
                .put("resource_id", id).put("status", "PENDING").put("maker", "maker");
        assertThat(apiError(() -> as(TENANT, "checker", () -> exports.applyApproved(approval))).code())
                .isEqualTo("approval_not_approved");
        approval.put("status", "APPROVED");
        assertThat(apiError(() -> as(TENANT, "maker", () -> exports.applyApproved(approval))).code())
                .isEqualTo("four_eyes_violation");
        ObjectNode foreign = approval.deepCopy().put("id", UUID.randomUUID().toString());
        assertThat(apiError(() -> as(TENANT, "checker", () -> exports.applyApproved(foreign))).code())
                .isEqualTo("approval_mismatch");

        JsonNode done = json.readTree(as(TENANT, "checker", () -> exports.applyApproved(approval)));
        assertThat(done.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.get("row_count").asInt()).isEqualTo(5);
        assertThat(done.hasNonNull("document_id")).isTrue();
        // Re-delivery of the approval event is harmless.
        assertThat(json.readTree(as(TENANT, "checker", () -> exports.applyApproved(approval)))
                .get("document_id").asString()).isEqualTo(done.get("document_id").asString());

        JsonNode url = get("/v1/data-exports/" + id + "/download-url", TENANT);
        assertThat(url.get("url").asString()).contains(done.get("document_id").asString()).contains("signature=");
        assertThat(url.hasNonNull("expires_at")).isTrue();
        assertThat(code(send("POST", "/v1/data-exports/" + id + "/cancel", "{\"reason\":\"too late\"}", key(), TENANT,
                null), 409)).isEqualTo("invalid_transition");
        assertThat(jdbc.sql("select count(*) from outbox where event_type = 'platform.data_export_request.completed'")
                .query(Long.class).single()).isEqualTo(1L);
        // Not visible to another tenant.
        assertThat(send("GET", "/v1/data-exports/" + id, null, null, OTHER_TENANT, null).statusCode()).isEqualTo(404);
    }

    @Test
    @Order(7)
    void dataExportValidationAndCancel() throws Exception {
        assertThat(code(send("POST", "/v1/data-exports",
                "{\"export_type\":\"DATA_SUBJECT_ACCESS\",\"requested_for\":\"Customer DSAR\"}", key(), TENANT, null),
                422)).isEqualTo("subject_required");
        assertThat(code(send("POST", "/v1/data-exports", """
                {"export_type":"AUDIT_LOG","requested_for":"x","from_time":"2026-01-02T00:00:00Z","to_time":"2026-01-01T00:00:00Z"}""",
                key(), TENANT, null), 422)).isEqualTo("invalid_time_range");
        assertThat(send("POST", "/v1/data-exports", "{\"requested_for\":\"x\"}", key(), TENANT, null).statusCode())
                .isEqualTo(400);

        JsonNode dsar = post("/v1/data-exports", """
                {"export_type":"DATA_SUBJECT_ACCESS","requested_for":"Customer DSAR","subject_type":"CUSTOMER",
                 "subject_id":"%s"}""".formatted(UUID.randomUUID()), 201, TENANT);
        assertThat(dsar.get("due_date").asString()).isNotBlank();
        String id = dsar.get("id").asString();
        JsonNode cancelled = post("/v1/data-exports/" + id + "/cancel", "{\"reason\":\"withdrawn by customer\"}", 200, TENANT);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(code(send("POST", "/v1/data-exports/" + id + "/cancel", "{\"reason\":\"again\"}", key(), TENANT, null),
                409)).isEqualTo("invalid_transition");
        ObjectNode approval = json.createObjectNode().put("id", dsar.get("approval_request_id").asString())
                .put("resource_type", "data_export_request").put("resource_id", id).put("status", "APPROVED");
        assertThat(apiError(() -> as(TENANT, "checker", () -> exports.applyApproved(approval))).code())
                .isEqualTo("invalid_transition");
    }

    @Test
    @Order(8)
    void tamperingIsDetectedAtTheFirstBrokenEvent() throws Exception {
        try (Connection c = DriverManager.getConnection(env.getProperty("spring.flyway.url"), "meridian", "meridian")) {
            try (Statement s = c.createStatement()) {
                s.execute("set session_replication_role = replica"); // bypasses the append-only trigger
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "update audit_event set reason = 'nothing to see' where tenant_id = ?::uuid and sequence_no = 3")) {
                ps.setString(1, TENANT);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
        }
        JsonNode v = post("/v1/audit-events/verify-chain", "{\"from_sequence\":1,\"to_sequence\":5}", 200, TENANT);
        assertThat(v.get("valid").asBoolean()).isFalse();
        assertThat(v.get("first_broken_sequence").asLong()).isEqualTo(3L);
        assertThat(v.get("reason").asString()).isEqualTo("HASH_MISMATCH");
        assertThat(jdbc.sql("select count(*) from outbox where event_type = 'platform.audit_chain.broken'")
                .query(Long.class).single()).isEqualTo(1L);
        // Later links still verify against the stored hash; other tenants are unaffected.
        assertThat(post("/v1/audit-events/verify-chain", "{\"from_sequence\":4,\"to_sequence\":5}", 200, TENANT)
                .get("valid").asBoolean()).isTrue();
        assertThat(post("/v1/audit-events/verify-chain", "{\"from_sequence\":1,\"to_sequence\":2}", 200, OTHER_TENANT)
                .get("valid").asBoolean()).isTrue();
        // The application role cannot rewrite history either (append-only trigger).
        Throwable t = catchThrowable(() -> as(TENANT, "svc", () -> jdbc
                .sql("update audit_event set reason = 'x' where sequence_no = 1").update()));
        assertThat(t).hasMessageContaining("append-only");
    }

    // ------------------------------------------------------------------ helpers

    private <T> T as(String tenant, String user, Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(tenant), user, body);
    }

    private static ApiException apiError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        Throwable t = catchThrowable(call);
        assertThat(t).isInstanceOf(ApiException.class);
        return (ApiException) t;
    }

    private static JsonNode byseq(JsonNode data, long seq) {
        for (JsonNode e : data) {
            if (e.get("sequence_no").asLong() == seq) {
                return e;
            }
        }
        throw new AssertionError("no event with sequence " + seq);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private String code(HttpResponse<String> r, int expected) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body()).get("code").asString();
    }

    private JsonNode post(String path, String body, int expected, String tenant) throws Exception {
        HttpResponse<String> r = send("POST", path, body, key(), tenant, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> sendWith(String method, String path, String body, String header, String value,
                                          String tenant) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("Content-Type", "application/json").header(header, value);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant,
                                      String user) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        if (user != null) {
            b.header("X-User", user);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
