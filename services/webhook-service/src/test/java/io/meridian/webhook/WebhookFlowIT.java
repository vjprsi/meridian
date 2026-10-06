package io.meridian.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.webhook.application.DeliveryService;
import io.meridian.webhook.application.ReplayService;
import io.meridian.webhook.domain.WebhookSignature;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end over HTTP against PostgreSQL 18 (RLS enforced) with a real HTTP receiver (JDK HttpServer) that
 * verifies the HMAC signatures. Dispatch and replay processing are driven directly (scheduler/consumer disabled).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.webhooks.consumer-enabled=false", "meridian.webhooks.scheduler-enabled=false",
                "meridian.webhooks.allow-insecure-localhost=true", "meridian.webhooks.disable-after-failures=3"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebhookFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000e1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000e2";

    record Received(String path, Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DeliveryService deliveries;

    @Autowired
    ReplayService replays;

    final HttpClient http = HttpClient.newHttpClient();
    final List<Received> received = new CopyOnWriteArrayList<>();
    final Map<String, AtomicInteger> statusByPath = new ConcurrentHashMap<>();
    HttpServer receiver;

    String okEndpoint;
    String okSecret;
    String failEndpoint;
    String failSecret;

    @BeforeAll
    void startReceiver() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getPath();
            received.add(new Received(path, Map.copyOf(ex.getRequestHeaders()), body));
            int status = statusByPath.computeIfAbsent(path, p -> new AtomicInteger(200)).get();
            byte[] out = ("{\"received\":" + (status < 300) + "}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        receiver.start();
        statusByPath.put("/fail", new AtomicInteger(500));
    }

    @AfterAll
    void stopReceiver() {
        receiver.stop(0);
    }

    String hookUrl(String path) {
        return "http://127.0.0.1:" + receiver.getAddress().getPort() + path;
    }

    @Test
    @Order(1)
    void createReturnsSecretOnceAndStoresItEncrypted() throws Exception {
        String body = """
                {"url":"%s","description":"payments","custom_headers":{"X-Partner":"acme"}}""".formatted(hookUrl("/ok"));
        HttpResponse<String> r = send("POST", "/v1/webhook-endpoints", body, "ep-ok", TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        JsonNode ep = json.readTree(r.body());
        okEndpoint = ep.get("id").asString();
        okSecret = ep.get("signing_secret").asString();
        assertThat(okSecret).startsWith("whsec_");
        assertThat(ep.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(ep.get("signing_secret_version").asInt()).isEqualTo(1);

        JsonNode fetched = get("/v1/webhook-endpoints/" + okEndpoint, TENANT);
        assertThat(fetched.hasNonNull("signing_secret")).isFalse();
        assertThat(fetched.has("signing_secret_enc")).isFalse();
        assertThat(fetched.hasNonNull("previous_secret_expires_at")).isFalse();
        String stored = asTenant(() -> jdbc.sql("select signing_secret_enc from webhook_endpoint where id = ?::uuid")
                .param(okEndpoint).query(String.class).single());
        assertThat(stored).startsWith("v1:").doesNotContain(okSecret.substring(6));

        HttpResponse<String> replay = send("POST", "/v1/webhook-endpoints", body, "ep-ok", TENANT);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        JsonNode replayed = json.readTree(replay.body());
        assertThat(replayed.get("id").asString()).isEqualTo(okEndpoint);
        assertThat(replayed.hasNonNull("signing_secret")).isFalse();

        JsonNode fail = post("/v1/webhook-endpoints", """
                {"url":"%s"}""".formatted(hookUrl("/fail")), 201);
        failEndpoint = fail.get("id").asString();
        failSecret = fail.get("signing_secret").asString();
    }

    @Test
    @Order(2)
    void endpointValidation() throws Exception {
        assertCode(send("POST", "/v1/webhook-endpoints", "{\"url\":\"http://example.com/hook\"}", key(), TENANT),
                422, "https_required");
        assertCode(send("POST", "/v1/webhook-endpoints", "{\"description\":\"no url\"}", key(), TENANT),
                400, "validation_failed");
        assertCode(send("POST", "/v1/webhook-endpoints",
                "{\"url\":\"https://hooks.example.com\",\"custom_headers\":{\"Meridian-Signature\":\"x\"}}", key(), TENANT),
                422, "invalid_custom_headers");
        // status is read-only (changed through /enable and /disable): a client value is ignored
        HttpResponse<String> patch = send("PATCH", "/v1/webhook-endpoints/" + okEndpoint,
                "{\"status\":\"DISABLED\",\"description\":\"payments (prod)\"}", null, TENANT, "1");
        assertThat(patch.statusCode()).as(patch.body()).isEqualTo(200);
        JsonNode patched = json.readTree(patch.body());
        assertThat(patched.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(patched.get("description").asString()).isEqualTo("payments (prod)");
    }

    @Test
    @Order(3)
    void subscriptionValidation() throws Exception {
        assertCode(send("POST", "/v1/webhook-subscriptions", """
                {"webhook_endpoint_id":"%s","event_type":"Payments.**"}""".formatted(okEndpoint), key(), TENANT),
                422, "invalid_event_type");
        assertCode(send("POST", "/v1/webhook-subscriptions", """
                {"webhook_endpoint_id":"%s","event_type":"payments.*"}""".formatted(UUID.randomUUID()), key(), TENANT),
                422, "endpoint_not_found");
        assertCode(send("POST", "/v1/webhook-subscriptions", """
                {"webhook_endpoint_id":"%s","event_type":"payments.*","filter_expression":"amount > 5"}"""
                .formatted(okEndpoint), key(), TENANT), 422, "invalid_filter_expression");
        post("/v1/webhook-subscriptions", """
                {"webhook_endpoint_id":"%s","event_type":"payments.*"}""".formatted(okEndpoint), 201);
        assertCode(send("POST", "/v1/webhook-subscriptions", """
                {"webhook_endpoint_id":"%s","event_type":"payments.*"}""".formatted(okEndpoint), key(), TENANT),
                409, "duplicate");
        post("/v1/webhook-subscriptions", """
                {"webhook_endpoint_id":"%s","event_type":"payments.payment.settled","filter_expression":"data.currency == \\"USD\\""}"""
                .formatted(failEndpoint), 201);
    }

    @Test
    @Order(4)
    void ingestFansOutDedupesAndDeliversSignedPayload() throws Exception {
        List<UUID> created = asTenant(() -> deliveries.ingest("evt-1", "payments.payment.settled", "pay-1",
                json.readTree("{\"id\":\"pay-1\",\"amount\":\"10.00\",\"currency\":\"EUR\"}")));
        assertThat(created).hasSize(1); // the failing endpoint's filter (currency == USD) does not match
        assertThat(asTenant(() -> deliveries.ingest("evt-1", "payments.payment.settled", "pay-1",
                json.readTree("{\"currency\":\"EUR\"}")))).isEmpty();
        assertThat(asTenant(() -> deliveries.ingest("evt-2", "cards.card.issued", "card-1",
                json.readTree("{}")))).isEmpty();

        int before = received.size();
        assertThat(asTenant(() -> deliveries.processDue())).isEqualTo(1);
        Received req = received.get(before);
        assertThat(req.path()).isEqualTo("/ok");
        assertThat(req.header("Meridian-Event-Id")).isEqualTo("evt-1");
        assertThat(req.header("Meridian-Event-Type")).isEqualTo("payments.payment.settled");
        assertThat(req.header("X-Partner")).isEqualTo("acme");
        assertThat(WebhookSignature.verify(okSecret, req.header("Meridian-Signature"), req.body(),
                Instant.now().getEpochSecond(), 300)).isTrue();
        assertThat(WebhookSignature.verify("whsec_wrong", req.header("Meridian-Signature"), req.body(),
                Instant.now().getEpochSecond(), 300)).isFalse();
        JsonNode envelope = json.readTree(req.body());
        assertThat(envelope.get("type").asString()).isEqualTo("payments.payment.settled");
        assertThat(envelope.get("data").get("amount").asString()).isEqualTo("10.00");

        String deliveryId = created.getFirst().toString();
        JsonNode d = get("/v1/webhook-deliveries/" + deliveryId, TENANT);
        assertThat(d.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(d.get("attempt_count").asInt()).isEqualTo(1);
        assertThat(d.get("last_status_code").asInt()).isEqualTo(200);
        JsonNode attempts = get("/v1/webhook-deliveries/" + deliveryId + "/attempts", TENANT).get("data");
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).get("request_headers").get("Meridian-Signature").asString()).contains("v1=<redacted>");
        assertThat(asTenant(() -> deliveries.processDue())).isZero();
        assertCode(send("POST", "/v1/webhook-deliveries/" + deliveryId + "/retry", null, key(), TENANT),
                409, "invalid_transition");
    }

    @Test
    @Order(5)
    void failuresRetryWithBackoffThenAutoDisableAndEnable() throws Exception {
        List<UUID> created = asTenant(() -> deliveries.ingest("evt-3", "payments.payment.settled", "pay-3",
                json.readTree("{\"currency\":\"USD\"}")));
        assertThat(created).hasSize(2);
        assertThat(asTenant(() -> deliveries.processDue())).isEqualTo(2);
        String failDelivery = deliveryFor(failEndpoint, "evt-3");
        JsonNode d = get("/v1/webhook-deliveries/" + failDelivery, TENANT);
        assertThat(d.get("status").asString()).isEqualTo("RETRYING");
        assertThat(d.get("last_status_code").asInt()).isEqualTo(500);
        OffsetDateTime next = OffsetDateTime.parse(d.get("next_attempt_at").asString());
        assertThat(next).isAfter(OffsetDateTime.now().plusSeconds(30)).isBefore(OffsetDateTime.now().plusMinutes(2));
        assertThat(get("/v1/webhook-endpoints/" + failEndpoint, TENANT).get("status").asString()).isEqualTo("FAILING");
        assertThat(asTenant(() -> deliveries.processDue())).as("not due yet").isZero();

        JsonNode r2 = post("/v1/webhook-deliveries/" + failDelivery + "/retry", null, 200);
        assertThat(r2.get("attempt_count").asInt()).isEqualTo(2);
        assertThat(r2.get("status").asString()).isEqualTo("RETRYING");
        post("/v1/webhook-deliveries/" + failDelivery + "/retry", null, 200);
        JsonNode ep = get("/v1/webhook-endpoints/" + failEndpoint, TENANT);
        assertThat(ep.get("status").asString()).isEqualTo("DISABLED");
        assertThat(ep.get("consecutive_failures").asInt()).isEqualTo(3);
        assertThat(ep.get("disabled_reason").asString()).contains("3 consecutive");
        assertThat(outboxEvents()).contains("platform.webhook_endpoint.disabled");

        assertCode(send("POST", "/v1/webhook-deliveries/" + failDelivery + "/retry", null, key(), TENANT),
                409, "endpoint_disabled");
        assertCode(send("POST", "/v1/webhook-endpoints/" + failEndpoint + "/test", "{\"event_type\":\"platform.test.ping\"}",
                key(), TENANT), 409, "endpoint_disabled");

        JsonNode enabled = post("/v1/webhook-endpoints/" + failEndpoint + "/enable", null, 200);
        assertThat(enabled.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(enabled.get("consecutive_failures").asInt()).isZero();
        assertThat(outboxEvents()).contains("platform.webhook_endpoint.enabled");
        assertCode(send("POST", "/v1/webhook-endpoints/" + failEndpoint + "/enable", null, key(), TENANT),
                409, "invalid_transition");
    }

    @Test
    @Order(6)
    void exhaustedScheduleAbandonsDelivery() throws Exception {
        String failDelivery = deliveryFor(failEndpoint, "evt-3");
        asTenant(() -> jdbc.sql("update webhook_delivery set attempt_count = 11 where id = ?::uuid")
                .param(failDelivery).update());
        JsonNode d = post("/v1/webhook-deliveries/" + failDelivery + "/retry", null, 200);
        assertThat(d.get("status").asString()).isEqualTo("ABANDONED");
        assertThat(d.get("attempt_count").asInt()).isEqualTo(12);
        assertThat(d.has("next_attempt_at")).isFalse();
        assertThat(outboxEvents()).contains("platform.webhook_delivery.abandoned");

        statusByPath.get("/fail").set(204);
        JsonNode manual = post("/v1/webhook-deliveries/" + failDelivery + "/retry", null, 200);
        assertThat(manual.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(get("/v1/webhook-endpoints/" + failEndpoint, TENANT).get("consecutive_failures").asInt()).isZero();
        assertThat(get("/v1/webhook-deliveries/" + failDelivery + "/attempts?limit=50", TENANT).get("data")).hasSize(5);
    }

    @Test
    @Order(7)
    void testSendAndDisableAction() throws Exception {
        JsonNode test = post("/v1/webhook-endpoints/" + okEndpoint + "/test", "{\"event_type\":\"platform.test.ping\"}", 200);
        assertThat(test.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(test.get("event_id").asString()).startsWith("evt_test_");
        assertCode(send("POST", "/v1/webhook-endpoints/" + okEndpoint + "/test", "{\"event_type\":\"payments.*\"}",
                key(), TENANT), 422, "invalid_event_type");

        statusByPath.get("/fail").set(503);
        JsonNode failedTest = post("/v1/webhook-endpoints/" + failEndpoint + "/test", "{\"event_type\":\"platform.test.ping\"}", 200);
        assertThat(failedTest.get("status").asString()).isEqualTo("FAILED");

        JsonNode disabled = post("/v1/webhook-endpoints/" + failEndpoint + "/disable", "{\"reason\":\"maintenance\"}", 200);
        assertThat(disabled.get("status").asString()).isEqualTo("DISABLED");
        assertThat(disabled.get("disabled_reason").asString()).isEqualTo("maintenance");
        assertCode(send("POST", "/v1/webhook-endpoints/" + failEndpoint + "/disable", "{}", key(), TENANT),
                409, "invalid_transition");
        // events are not fanned out to disabled endpoints
        assertThat(asTenant(() -> deliveries.ingest("evt-4", "payments.payment.settled", "pay-4",
                json.readTree("{\"currency\":\"USD\"}")))).hasSize(1);
        assertThat(asTenant(() -> deliveries.processDue())).isEqualTo(1);
    }

    @Test
    @Order(8)
    void rotateSecretSignsWithBothSecretsDuringGrace() throws Exception {
        HttpResponse<String> r = send("POST", "/v1/webhook-endpoints/" + okEndpoint + "/rotate-secret", null, "rot-1", TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode rotated = json.readTree(r.body());
        String newSecret = rotated.get("signing_secret").asString();
        assertThat(newSecret).startsWith("whsec_").isNotEqualTo(okSecret);
        assertThat(rotated.get("signing_secret_version").asInt()).isEqualTo(2);
        assertThat(OffsetDateTime.parse(rotated.get("previous_secret_expires_at").asString()))
                .isBetween(OffsetDateTime.now().plusHours(23), OffsetDateTime.now().plusHours(25));
        HttpResponse<String> again = send("POST", "/v1/webhook-endpoints/" + okEndpoint + "/rotate-secret", null, "rot-1", TENANT);
        assertThat(again.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(again.body()).hasNonNull("signing_secret")).isFalse();
        JsonNode fetched = get("/v1/webhook-endpoints/" + okEndpoint, TENANT);
        assertThat(fetched.get("signing_secret_version").asInt()).isEqualTo(2);
        assertThat(fetched.hasNonNull("previous_secret_expires_at")).isTrue();
        String previousStored = asTenant(() -> jdbc.sql(
                "select previous_signing_secret_enc from webhook_endpoint where id = ?::uuid")
                .param(okEndpoint).query(String.class).single());
        assertThat(previousStored).startsWith("v1:").doesNotContain(okSecret.substring(6));
        assertThat(outboxEvents()).contains("platform.webhook_endpoint.secret_rotated");

        int before = received.size();
        post("/v1/webhook-endpoints/" + okEndpoint + "/test", "{\"event_type\":\"platform.test.ping\"}", 200);
        Received req = received.get(before);
        String sig = req.header("Meridian-Signature");
        assertThat(sig.split(",v1=")).hasSize(3);
        long now = Instant.now().getEpochSecond();
        assertThat(WebhookSignature.verify(newSecret, sig, req.body(), now, 300)).isTrue();
        assertThat(WebhookSignature.verify(okSecret, sig, req.body(), now, 300)).isTrue();
        okSecret = newSecret;
    }

    @Test
    @Order(9)
    void replayJobRedeliversPastEvents() throws Exception {
        String from = OffsetDateTime.now().minusHours(1).toString();
        String to = OffsetDateTime.now().plusMinutes(1).toString();
        assertCode(send("POST", "/v1/event-replays", """
                {"webhook_endpoint_id":"%s","from_time":"%s","to_time":"%s"}""".formatted(okEndpoint, to, from),
                key(), TENANT), 422, "invalid_time_range");
        assertCode(send("POST", "/v1/event-replays", """
                {"webhook_endpoint_id":"%s","from_time":"%s","to_time":"%s","event_types":["not a pattern!"]}"""
                .formatted(okEndpoint, from, to), key(), TENANT), 422, "invalid_event_types");

        JsonNode job = post("/v1/event-replays", """
                {"webhook_endpoint_id":"%s","from_time":"%s","to_time":"%s","event_types":["payments.*"]}"""
                .formatted(okEndpoint, from, to), 201);
        assertThat(job.get("status").asString()).isEqualTo("QUEUED");
        String jobId = job.get("id").asString();
        assertThat(asTenant(() -> replays.processQueued())).isEqualTo(1);
        JsonNode done = get("/v1/event-replays/" + jobId, TENANT);
        assertThat(done.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.get("events_total").asInt()).isEqualTo(3); // evt-1, evt-3, evt-4 (test sends excluded)
        assertThat(done.get("events_replayed").asInt()).isEqualTo(3);
        assertThat(outboxEvents()).contains("platform.webhook_replay_job.completed");
        assertThat(get("/v1/webhook-deliveries?event_id=evt-1&webhook_endpoint_id=" + okEndpoint, TENANT).get("data"))
                .hasSize(2);
        assertThat(get("/v1/webhook-deliveries?replay_job_id=" + jobId, TENANT).get("data")).hasSize(3);
        assertThat(done.get("event_types").get(0).asString()).isEqualTo("payments.*");
        int before = received.size();
        assertThat(asTenant(() -> deliveries.processDue())).isEqualTo(3);
        assertThat(received.size() - before).isEqualTo(3);
        assertCode(send("POST", "/v1/event-replays/" + jobId + "/cancel", null, key(), TENANT), 409, "invalid_transition");

        JsonNode second = post("/v1/event-replays", """
                {"webhook_endpoint_id":"%s","from_time":"%s","to_time":"%s"}""".formatted(okEndpoint, from, to), 201);
        JsonNode cancelled = post("/v1/event-replays/" + second.get("id").asString() + "/cancel", null, 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(outboxEvents()).contains("platform.webhook_replay_job.cancelled");
        assertThat(asTenant(() -> replays.processQueued())).isZero();
    }

    @Test
    @Order(10)
    void actionReplayIsIdempotentAndKeyBoundToOperation() throws Exception {
        HttpResponse<String> first = send("POST", "/v1/webhook-endpoints/" + failEndpoint + "/enable", null, "en-1", TENANT);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        HttpResponse<String> replay = send("POST", "/v1/webhook-endpoints/" + failEndpoint + "/enable", null, "en-1", TENANT);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertCode(send("POST", "/v1/webhook-endpoints/" + okEndpoint + "/enable", null, "en-1", TENANT),
                422, "idempotency_key_reused");
    }

    @Test
    @Order(11)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/webhook-endpoints/" + okEndpoint, null, null, OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(get("/v1/webhook-deliveries", OTHER_TENANT).get("data")).isEmpty();
        assertThat(send("POST", "/v1/webhook-endpoints/" + okEndpoint + "/disable", "{}", key(), OTHER_TENANT)
                .statusCode()).isEqualTo(404);
        List<UUID> none = TenantContext.callAs(UUID.fromString(OTHER_TENANT), "test",
                () -> deliveries.ingest("evt-x", "payments.payment.settled", null, json.readTree("{}")));
        assertThat(none).isEmpty();
    }

    @Test
    @Order(12)
    void deleteRequiresNoDeliveryHistory() throws Exception {
        assertCode(send("DELETE", "/v1/webhook-endpoints/" + okEndpoint, null, null, TENANT), 409, "endpoint_has_deliveries");
        JsonNode fresh = post("/v1/webhook-endpoints", "{\"url\":\"https://hooks.example.com/x\"}", 201);
        String id = fresh.get("id").asString();
        post("/v1/webhook-subscriptions", """
                {"webhook_endpoint_id":"%s","event_type":"*"}""".formatted(id), 201);
        assertThat(send("DELETE", "/v1/webhook-endpoints/" + id, null, null, TENANT).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/v1/webhook-endpoints/" + id, null, null, TENANT).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    private String deliveryFor(String endpoint, String eventId) throws Exception {
        JsonNode data = get("/v1/webhook-deliveries?webhook_endpoint_id=" + endpoint + "&event_id=" + eventId, TENANT)
                .get("data");
        assertThat(data).isNotEmpty();
        return data.get(data.size() - 1).get("id").asString();
    }

    private List<String> outboxEvents() throws Exception {
        return jdbc.sql("select event_type from outbox").query(String.class).list();
    }

    private <T> T asTenant(java.util.concurrent.Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", body);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private void assertCode(HttpResponse<String> r, int status, String code) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(json.readTree(r.body()).get("code").asString()).as(r.body()).isEqualTo(code);
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, key(), TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant)
            throws Exception {
        return send(method, path, body, idemKey, tenant, null);
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant,
                                      String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", "ops-user").header("Content-Type", "application/json");
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
