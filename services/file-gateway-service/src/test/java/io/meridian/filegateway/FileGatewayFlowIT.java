package io.meridian.filegateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.filegateway.domain.VirusScanner;
import io.meridian.filegateway.infrastructure.ChannelConnector;
import io.meridian.filegateway.infrastructure.SimulatedChannelConnector;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Managed file transfer over HTTP against PostgreSQL 18 (RLS on): channels with secret references, layouts,
 * routing, the inbound pipeline (dedupe, EICAR quarantine with four-eyes release, validation, dispatch,
 * acknowledgement), outbound generation and delivery with retries, polling, and the immutable transfer log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.files.delivery.backoff=PT0.01S"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FileGatewayFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000f1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000f2";
    static final String FP = "SHA256:" + "A".repeat(43);
    static final String CSV_OK = "id,name,amount,value_date\n1,Alice,10.50,2026-01-30\n2,Bob,4.50,2026-01-30\n";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    @MockitoSpyBean
    SimulatedChannelConnector connector;

    final HttpClient http = HttpClient.newHttpClient();
    final AtomicReference<RuntimeException> consumerFailure = new AtomicReference<>();
    String bank;
    String downChannel;
    String csvFormat;
    String processedFile;

    @BeforeEach
    void stubs() {
        when(serviceClient.post(eq("reconciliation-service"), anyString(), any(), any())).thenAnswer(inv -> {
            RuntimeException f = consumerFailure.get();
            if (f != null) {
                throw f;
            }
            return json.readTree("{\"id\":\"imp-" + UUID.randomUUID() + "\",\"status\":\"PROCESSED\"}");
        });
    }

    @Test
    @Order(1)
    void channelsAcceptOnlySecretReferences() throws Exception {
        String base = """
                {"code":"%s","name":"Bank X","counterparty_type":"BANK","protocol":"SFTP","direction":"BOTH",
                 "host":"%s","username":"meridian","host_key_fingerprint":"%s","remote_inbound_path":"/out",
                 "remote_outbound_path":"/in"%s}""";
        assertThat(post("/v1/files/channels", base.formatted("C1", "sftp.bank.example", FP,
                ",\"credential_secret_ref\":\"hunter2\""), 422).get("code").asString()).isEqualTo("invalid_secret_ref");
        // the credential reference is required by the contract
        assertThat(post("/v1/files/channels", base.formatted("C1", "sftp.bank.example", FP, ""), 400).get("code").asString())
                .isEqualTo("validation_failed");
        // list fields are JSON arrays; each entry must be an IP or CIDR block
        assertThat(post("/v1/files/channels", base.formatted("C1", "sftp.bank.example", FP,
                ",\"credential_secret_ref\":\"vault://kv/bank\",\"ip_allowlist\":{\"ips\":[\"10.0.0.1\"]}"), 400)
                .get("code").asString()).isEqualTo("malformed_request");
        assertThat(post("/v1/files/channels", base.formatted("C1", "sftp.bank.example", FP,
                ",\"credential_secret_ref\":\"vault://kv/bank\",\"ip_allowlist\":[\"not-an-ip\"]"), 422)
                .get("code").asString()).isNotBlank();
        assertThat(post("/v1/files/channels", base.formatted("C1", "sftp.bank.example", "abc",
                ",\"credential_secret_ref\":\"vault://kv/bank\""), 422).get("code").asString())
                .isEqualTo("invalid_host_key_fingerprint");

        JsonNode ch = post("/v1/files/channels", base.formatted("BANKX", "sftp.bank.example", FP,
                ",\"credential_secret_ref\":\"vault://kv/files/bankx/sftp\",\"pgp_encrypt_key_ref\":\"vault://kv/files/bankx/pgp-pub\","
                        + "\"ip_allowlist\":[\"203.0.113.10\",\"198.51.100.0/24\"]"), 201);
        bank = ch.get("id").asString();
        assertThat(ch.get("port").asInt()).isEqualTo(22);
        // references (vault paths) are part of the contract; the secrets themselves never are
        assertThat(ch.get("credential_secret_ref").asString()).isEqualTo("vault://kv/files/bankx/sftp");
        assertThat(ch.get("pgp_encrypt_key_ref").asString()).isEqualTo("vault://kv/files/bankx/pgp-pub");
        assertThat(ch.get("ip_allowlist").toString()).isEqualTo("[\"203.0.113.10\",\"198.51.100.0/24\"]");
        HttpResponse<String> patch = send("PATCH", "/v1/files/channels/" + bank,
                "{\"credential_secret_ref\":\"vault://kv/files/bankx/other\"}", null, TENANT, "dev-user", "1");
        assertThat(patch.statusCode()).as(patch.body()).isEqualTo(422);
        assertThat(json.readTree(patch.body()).get("code").asString()).isEqualTo("use_rotate_credentials");

        downChannel = post("/v1/files/channels", """
                {"code":"DOWN","name":"Down partner","counterparty_type":"PARTNER","protocol":"S3","direction":"OUTBOUND",
                 "host":"bucket.down.invalid","credential_secret_ref":"aws-sm://prod/partner"}""", 201).get("id").asString();

        JsonNode up = post("/v1/files/channels/" + bank + "/test-connection", null, 200);
        assertThat(up.get("success").asBoolean()).isTrue();
        assertThat(up.get("health_status").asString()).isEqualTo("UP");
        JsonNode down = post("/v1/files/channels/" + downChannel + "/test-connection", null, 200);
        assertThat(down.get("success").asBoolean()).isFalse();
        assertThat(down.get("health_status").asString()).isEqualTo("DOWN");
        assertThat(get("/v1/files/channels/" + bank).get("health_status").asString()).isEqualTo("UP");
    }

    @Test
    @Order(2)
    void formatsAndRoutesAreValidated() throws Exception {
        assertThat(post("/v1/files/formats", """
                {"code":"FW","name":"Fixed","format_kind":"FIXED_WIDTH","schema_definition":{"fields":[{"name":"a","start":1,"length":5}]}}""",
                422).get("code").asString()).isEqualTo("invalid_layout");
        csvFormat = post("/v1/files/formats", """
                {"code":"STMT_CSV","name":"Statement CSV","format_kind":"CSV","delimiter":",","has_header":true,
                 "schema_definition":{"fields":[{"name":"id","type":"NUMERIC","required":true},{"name":"name"},
                   {"name":"amount","type":"DECIMAL","required":true},{"name":"value_date","type":"DATE"}]}}""", 201)
                .get("id").asString();
        JsonNode sample = post("/v1/files/formats/" + csvFormat + "/validate-sample",
                "{\"document_id\":\"" + UUID.randomUUID() + "\",\"content\":" + json.writeValueAsString(CSV_OK) + "}", 200);
        assertThat(sample.get("valid").asBoolean()).isTrue();
        assertThat(sample.get("record_count").asInt()).isEqualTo(2);
        JsonNode badSample = post("/v1/files/formats/" + csvFormat + "/validate-sample",
                "{\"document_id\":\"" + UUID.randomUUID() + "\",\"content\":\"id,name,amount,value_date\\nx,y,z,2026\\n\"}", 200);
        assertThat(badSample.get("valid").asBoolean()).isFalse();
        assertThat(badSample.get("error_count").asInt()).isEqualTo(3);
        assertThat(post("/v1/files/formats/" + csvFormat + "/validate-sample", "{}", 422).get("code").asString())
                .isEqualTo("sample_required");

        assertThat(post("/v1/files/routes", route("Bad", "STMT_.*\\\\.csv", "no-such-service"), 422).get("code").asString())
                .isEqualTo("unknown_service");
        assertThat(post("/v1/files/routes", route("Bad", "STMT_(", "reconciliation-service"), 422).get("code").asString())
                .isEqualTo("invalid_pattern");
        post("/v1/files/routes", route("Statements", "STMT_.*\\\\.csv", "reconciliation-service"), 201);
        JsonNode sim = post("/v1/files/routes/simulate", "{\"file_name\":\"STMT_001.csv\",\"file_channel_id\":\"" + bank + "\"}", 200);
        assertThat(sim.get("name").asString()).isEqualTo("Statements");
        assertThat(post("/v1/files/routes/simulate", "{\"file_name\":\"invoice.pdf\"}", 404).get("code").asString())
                .isEqualTo("no_matching_route");
    }

    @Test
    @Order(3)
    void inboundFileIsScannedValidatedRoutedAndAcknowledged() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = inbound("STMT_001.csv", CSV_OK);
        HttpResponse<String> r = send("POST", "/v1/files/inbound/upload", body, key, TENANT, "receiver");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode file = json.readTree(r.body());
        processedFile = file.get("id").asString();
        assertThat(file.get("status").asString()).isEqualTo("PROCESSED");
        assertThat(file.get("virus_scan_status").asString()).isEqualTo("CLEAN");
        assertThat(file.get("record_count").asInt()).isEqualTo(2);
        assertThat(file.get("checksum_sha256").asString()).hasSize(64);
        assertThat(file.get("consumer_reference").asString()).startsWith("imp-");
        assertThat(file.get("file_format_definition_id").asString()).isEqualTo(csvFormat);

        HttpResponse<String> replay = send("POST", "/v1/files/inbound/upload", body, key, TENANT, "receiver");
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        JsonNode dup = post("/v1/files/inbound/upload", inbound("STMT_001_copy.csv", CSV_OK), 409);
        assertThat(dup.get("code").asString()).isEqualTo("duplicate_file");

        List<String> actions = new ArrayList<>();
        get("/v1/files/inbound/" + processedFile + "/logs?limit=50").get("data").forEach(l -> actions.add(l.get("action").asString()));
        assertThat(actions).contains("UPLOAD", "SCAN", "VALIDATE", "ROUTE", "ACK");

        JsonNode link = get("/v1/files/inbound/" + processedFile + "/download");
        assertThat(link.get("url").asString()).contains("signature=");
        HttpResponse<String> blob = http.send(HttpRequest.newBuilder(URI.create(link.get("url").asString())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(blob.statusCode()).isEqualTo(200);
        assertThat(blob.body()).isEqualTo(CSV_OK);
        HttpResponse<String> tampered = http.send(HttpRequest.newBuilder(URI.create(
                link.get("url").asString().replace("signature=", "signature=0"))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(tampered.statusCode()).isEqualTo(403);
    }

    @Test
    @Order(4)
    void eicarIsQuarantinedAndReleaseNeedsFourEyes() throws Exception {
        HttpResponse<String> r = send("POST", "/v1/files/inbound/upload", inbound("STMT_002.csv", VirusScanner.EICAR),
                UUID.randomUUID().toString(), TENANT, "receiver");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode file = json.readTree(r.body());
        String id = file.get("id").asString();
        assertThat(file.get("status").asString()).isEqualTo("QUARANTINED");
        assertThat(file.get("virus_scan_status").asString()).isEqualTo("INFECTED");
        assertThat(file.get("virus_scan_detail").asString()).isEqualTo("Eicar-Test-Signature");
        assertThat(send("GET", "/v1/files/inbound/" + id + "/download", null, null, TENANT, "x").statusCode()).isEqualTo(409);
        assertThat(post("/v1/files/inbound/" + id + "/reprocess", null, 409).get("code").asString()).isEqualTo("file_quarantined");

        HttpResponse<String> self = send("POST", "/v1/files/inbound/" + id + "/release-quarantine",
                "{\"reason\":\"false positive\"}", UUID.randomUUID().toString(), TENANT, "receiver");
        assertThat(self.statusCode()).isEqualTo(422);
        assertThat(json.readTree(self.body()).get("code").asString()).isEqualTo("four_eyes_violation");
        HttpResponse<String> released = send("POST", "/v1/files/inbound/" + id + "/release-quarantine",
                "{\"reason\":\"security reviewed\"}", UUID.randomUUID().toString(), TENANT, "security-officer");
        assertThat(released.statusCode()).as(released.body()).isEqualTo(200);
        JsonNode after = json.readTree(released.body());
        assertThat(after.get("virus_scan_status").asString()).isEqualTo("CLEAN");
        assertThat(after.get("status").asString()).isEqualTo("REJECTED"); // the test string is no statement layout
        assertThat(post("/v1/files/inbound/" + id + "/release-quarantine", "{\"reason\":\"again\"}", 409).get("code").asString())
                .isEqualTo("invalid_transition");
    }

    @Test
    @Order(5)
    void layoutErrorsRejectAndConsumerFailuresCanBeReprocessed() throws Exception {
        JsonNode bad = post("/v1/files/inbound/upload", inbound("STMT_003.csv", "id,name,amount,value_date\n1,A,ten,2026-01-30\n"), 200);
        assertThat(bad.get("status").asString()).isEqualTo("REJECTED");
        assertThat(bad.get("error").asString()).contains("invalid_decimal");

        consumerFailure.set(new ApiException(502, "upstream_error", "reconciliation-service unavailable"));
        JsonNode failed = post("/v1/files/inbound/upload", inbound("STMT_004.csv", CSV_OK.replace("Bob", "Carol")), 200);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        consumerFailure.set(null);
        JsonNode again = post("/v1/files/inbound/" + failed.get("id").asString() + "/reprocess", null, 200);
        assertThat(again.get("status").asString()).isEqualTo("PROCESSED");
        assertThat(post("/v1/files/inbound/" + again.get("id").asString() + "/reprocess", null, 409).get("code").asString())
                .isEqualTo("invalid_transition");

        JsonNode unrouted = post("/v1/files/inbound/upload", inbound("misc.txt", "hello"), 200);
        assertThat(unrouted.get("status").asString()).isEqualTo("REJECTED");
    }

    @Test
    @Order(6)
    void outboundFilesAreGeneratedDeliveredAndAcknowledged() throws Exception {
        String records = "[{\"id\":\"1\",\"name\":\"Alice\",\"amount\":\"10.50\",\"value_date\":\"2026-01-30\"},"
                + "{\"id\":\"2\",\"name\":\"Bob\",\"amount\":\"4.25\",\"value_date\":\"2026-01-30\"}]";
        JsonNode file = post("/v1/files/outbound/generate", """
                {"file_channel_id":"%s","file_format_definition_id":"%s","requested_by_service":"payment-service",
                 "source_reference":"batch-1","file_name":"PAY_001.csv","records":%s}""".formatted(bank, csvFormat, records), 200);
        String id = file.get("id").asString();
        assertThat(file.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(file.get("record_count").asInt()).isEqualTo(2);
        assertThat(file.get("control_totals").get("control_sum").asString()).isEqualTo("14.75");
        assertThat(file.get("delivery_attempts").asInt()).isEqualTo(1);
        assertThat(file.get("checksum_sha256").asString()).hasSize(64);

        JsonNode ack = post("/v1/files/outbound/" + id + "/acknowledge", "{\"ack_reference\":\"ACK-778\"}", 200);
        assertThat(ack.get("status").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(post("/v1/files/outbound/" + id + "/acknowledge", "{\"ack_reference\":\"ACK-779\"}", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(post("/v1/files/outbound/" + id + "/cancel", "{\"reason\":\"late\"}", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        HttpResponse<String> blob = http.send(HttpRequest.newBuilder(URI.create(
                get("/v1/files/outbound/" + id + "/download").get("url").asString())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(blob.body()).startsWith("id,name,amount,value_date\n1,Alice,10.50,2026-01-30\n");

        assertThat(post("/v1/files/outbound/generate", """
                {"file_channel_id":"%s","requested_by_service":"payment-service","file_name":"X.txt","content":"x"}"""
                .formatted(downChannel), 422).get("code").asString()).isEqualTo("encryption_key_missing");
        JsonNode failing = post("/v1/files/outbound/generate", """
                {"file_channel_id":"%s","requested_by_service":"payment-service","file_name":"X.txt","content":"x","encrypt":false}"""
                .formatted(downChannel), 200);
        assertThat(failing.get("status").asString()).isEqualTo("FAILED");
        assertThat(failing.get("delivery_attempts").asInt()).isEqualTo(3);
        JsonNode retried = post("/v1/files/outbound/" + failing.get("id").asString() + "/redeliver", "{\"reason\":\"retry\"}", 200);
        assertThat(retried.get("delivery_attempts").asInt()).isEqualTo(6);
        JsonNode cancelled = post("/v1/files/outbound/" + failing.get("id").asString() + "/cancel", "{\"reason\":\"partner down\"}", 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(post("/v1/files/outbound/" + failing.get("id").asString() + "/redeliver", "{}", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(post("/v1/files/outbound/generate", """
                {"file_channel_id":"%s","requested_by_service":"payment-service","file_name":"Y.txt","encrypt":false}"""
                .formatted(downChannel), 422).get("code").asString()).isEqualTo("content_required");
    }

    @Test
    @Order(7)
    void pollNowReceivesNewRemoteFilesOnce() throws Exception {
        ChannelConnector.RemoteFile remote = new ChannelConnector.RemoteFile("STMT_010.csv", "/out/STMT_010.csv", 60);
        doReturn(List.of(remote)).when(connector).list(any());
        doReturn(CSV_OK.replace("Alice", "Zed").getBytes(StandardCharsets.UTF_8)).when(connector).download(any(), any());
        JsonNode poll = post("/v1/files/channels/" + bank + "/poll-now", null, 200);
        assertThat(poll.get("files_received").asInt()).isEqualTo(1);
        String id = poll.get("inbound_file_ids").get(0).asString();
        assertThat(get("/v1/files/inbound/" + id).get("status").asString()).isEqualTo("PROCESSED");
        JsonNode second = post("/v1/files/channels/" + bank + "/poll-now", null, 200);
        assertThat(second.get("files_received").asInt()).isZero();
        assertThat(second.get("duplicates")).hasSize(1);
        assertThat(post("/v1/files/channels/" + downChannel + "/poll-now", null, 409).get("code").asString())
                .isEqualTo("channel_not_inbound");
    }

    @Test
    @Order(8)
    void rotateAndDisableChannel() throws Exception {
        assertThat(post("/v1/files/channels/" + bank + "/rotate-credentials",
                "{\"credential_secret_ref\":\"vault://kv/files/bankx/sftp\"}", 422).get("code").asString())
                .isEqualTo("credential_unchanged");
        assertThat(post("/v1/files/channels/" + bank + "/rotate-credentials",
                "{\"credential_secret_ref\":\"plaintext\"}", 422).get("code").asString()).isEqualTo("invalid_secret_ref");
        post("/v1/files/channels/" + bank + "/rotate-credentials", "{\"credential_secret_ref\":\"vault://kv/files/bankx/sftp-v2\"}", 200);
        String stored = jdbc.sql("select credential_secret_ref from file_channel where id = ?::uuid")
                .param(bank).query(String.class).optional().orElse(null);
        assertThat(stored).isNull(); // RLS: the test connection has no tenant bound, so nothing is visible

        JsonNode disabled = post("/v1/files/channels/" + bank + "/disable", "{\"reason\":\"contract ended\"}", 200);
        assertThat(disabled.get("is_active").asBoolean()).isFalse();
        assertThat(post("/v1/files/channels/" + bank + "/disable", "{\"reason\":\"again\"}", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(post("/v1/files/channels/" + bank + "/test-connection", null, 409).get("code").asString())
                .isEqualTo("channel_disabled");
        assertThat(post("/v1/files/inbound/upload", inbound("STMT_020.csv", CSV_OK + "3,C,1,2026-01-30\n"), 409).get("code").asString())
                .isEqualTo("channel_disabled");
    }

    @Test
    @Order(9)
    void eventsAuditAndTenantIsolation() throws Exception {
        List<String> types = jdbc.sql("select distinct event_type from outbox").query(String.class).list();
        assertThat(types).contains("operations.inbound_file.received", "operations.inbound_file.quarantined",
                "operations.inbound_file.routed", "operations.inbound_file.processed", "operations.outbound_file.generated",
                "operations.outbound_file.delivered", "operations.outbound_file.failed",
                "operations.file_channel.health_changed", "operations.file_channel.credentials_rotated");
        assertThat(get("/v1/files/transfer-logs?limit=200").get("data").size()).isGreaterThan(10);
        assertThat(send("GET", "/v1/files/inbound/" + processedFile, null, null, OTHER_TENANT, "x").statusCode()).isEqualTo(404);
        assertThat(json.readTree(send("GET", "/v1/files/channels", null, null, OTHER_TENANT, "x").body()).get("data")).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private String inbound(String name, String content) throws Exception {
        return "{\"file_channel_id\":\"" + bank + "\",\"original_file_name\":\"" + name + "\",\"remote_path\":\"/out/" + name
                + "\",\"content\":" + json.writeValueAsString(content) + "}";
    }

    private String route(String name, String pattern, String target) {
        return """
                {"name":"%s","priority":10,"file_name_pattern":"%s","target_service":"%s","target_topic":"/v1/recon/imports",
                 "require_virus_scan":true,"file_format_definition_id":"%s"}""".formatted(name, pattern, target, csvFormat);
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), TENANT, "dev-user");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, TENANT, "dev-user");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant, String user)
            throws Exception {
        return send(method, path, body, idemKey, tenant, user, null);
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant, String user,
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
