package io.meridian.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Invalidation through the real event backbone: tenant-service's outbox events (CloudEvents headers, row payload) on
 * {@code meridian.platform.v1} reach every router instance (own consumer group) and change routing within moments,
 * not after the cache TTL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.router.events.enabled=true", "meridian.router.health.enabled=false",
                "meridian.router.cells-refresh=1h", "meridian.router.cache-ttl=1h", "management.server.port=0"})
class CellRouterKafkaIT {
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));
    static final FakeEstate estate;

    static {
        KAFKA.start();
        try {
            estate = new FakeEstate("cell-k1", "cell-k2");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("meridian.router.registry-url", estate::registryUrl);
        r.add("meridian.router.jwt.jwk-set-uri", () -> estate.registryUrl() + "/jwks");
        r.add("meridian.router.jwt.issuer", () -> FakeEstate.ISSUER);
    }

    @AfterAll
    static void stop() {
        estate.close();
        KAFKA.stop();
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void tenantAndCellEventsChangeRoutingWithoutWaitingForTheTtl() throws Exception {
        UUID t = UUID.randomUUID();
        estate.place(t, "cell-k1", null, "NORMAL");
        assertThat(get(t)).contains("cell-k1");
        estate.place(t, "cell-k2", "cell-k1", "NORMAL");
        assertThat(get(t)).contains("cell-k1");    // cached

        try (KafkaProducer<String, String> producer = producer()) {
            // the consumer joins at the latest offset: re-send until the router has seen one (idempotent invalidation)
            long deadline = System.currentTimeMillis() + 60_000;
            boolean moved = false;
            while (!moved && System.currentTimeMillis() < deadline) {
                send(producer, "platform.tenant.migrated", t.toString(),
                        "{\"subject_tenant_id\":\"" + t + "\",\"status\":\"ACTIVE\"}");
                TimeUnit.MILLISECONDS.sleep(500);
                moved = get(t).contains("cell-k2");
            }
            assertThat(moved).as("routing followed the migrated event").isTrue();

            estate.cellStatus.put("cell-k2", "OFFLINE");
            send(producer, "platform.cell.status_changed", UUID.randomUUID().toString(), estate.cellJson("cell-k2"));
            deadline = System.currentTimeMillis() + 30_000;
            boolean failedOver = false;
            while (!failedOver && System.currentTimeMillis() < deadline) {
                TimeUnit.MILLISECONDS.sleep(200);
                failedOver = get(t).contains("cell-k1");
            }
            assertThat(failedOver).as("standby took over after the cell event").isTrue();
        }
    }

    private String get(UUID tenant) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/accounts"))
                .header("Authorization", "Bearer " + estate.token(tenant)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static KafkaProducer<String, String> producer() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return new KafkaProducer<>(p);
    }

    private static void send(KafkaProducer<String, String> producer, String type, String tenant, String payload)
            throws Exception {
        ProducerRecord<String, String> rec = new ProducerRecord<>("meridian.platform.v1", UUID.randomUUID().toString(), payload);
        rec.headers().add("ce_type", type.getBytes(StandardCharsets.UTF_8));
        rec.headers().add("ce_tenantid", tenant.getBytes(StandardCharsets.UTF_8));
        rec.headers().add("ce_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        producer.send(rec).get(10, TimeUnit.SECONDS);
    }
}
