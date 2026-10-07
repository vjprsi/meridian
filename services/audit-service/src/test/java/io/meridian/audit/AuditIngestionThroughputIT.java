package io.meridian.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.audit.application.AuditIntegrityService;
import io.meridian.audit.infrastructure.DomainEventAuditListener;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import tools.jackson.databind.JsonNode;

/**
 * Ingestion throughput after a restart (live e2e journey B lagged > 30 s): 20,000 CloudEvents of four tenants are
 * put on a topic while the audit listener is stopped (a backlog, as after a restart), then the listener is started
 * and the time until every event is stored is measured. Every tenant's chain must verify afterwards (full run).
 * Target: >= 2,000 events/s on a developer machine.
 */
@SpringBootTest(properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.audit.listener-enabled=false", "meridian.approvals.listener-enabled=false",
        "meridian.audit.integrity.cron=-",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.properties.metadata.max.age.ms=1000"})
@Import(MeridianTestContainers.class)
class AuditIngestionThroughputIT {
    static final int EVENTS = Integer.getInteger("audit.perf.events", 20_000);
    static final String TOPIC = "meridian.perfaudit.v1";
    static final List<UUID> TENANTS = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @Autowired
    KafkaListenerEndpointRegistry registry;

    @Autowired
    AuditIntegrityService integrity;

    @Autowired
    Environment env;

    @Test
    void ingests20kEventsAndEveryChainVerifies() throws Exception {
        String bootstrap = env.getProperty("spring.kafka.bootstrap-servers");
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.LINGER_MS_CONFIG, 20);
        p.put(ProducerConfig.BATCH_SIZE_CONFIG, 256 * 1024);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
            for (int i = 0; i < EVENTS; i++) {
                UUID tenant = TENANTS.get(i % TENANTS.size());
                String aggregate = UUID.randomUUID().toString();
                ProducerRecord<String, String> rec = new ProducerRecord<>(TOPIC, aggregate, payload(aggregate, i));
                header(rec, "ce_type", "customers.customer.updated");
                header(rec, "ce_id", UUID.randomUUID().toString());
                header(rec, "ce_tenantid", tenant.toString());
                header(rec, "ce_source", "customer-service");
                header(rec, "ce_time", Instant.now().toString());
                producer.send(rec);
            }
            producer.flush();
        }

        MessageListenerContainer container = registry.getListenerContainer(DomainEventAuditListener.LISTENER_ID);
        assertThat(container).isNotNull();
        long started = System.nanoTime();
        container.start();
        long stored = 0;
        long deadline = started + TimeUnit.MINUTES.toNanos(10);
        while (stored < EVENTS && System.nanoTime() < deadline) {
            Thread.sleep(100);
            stored = count();
        }
        double seconds = (System.nanoTime() - started) / 1e9;
        container.stop();
        double rate = stored / seconds;
        String line = String.format("AUDIT INGESTION THROUGHPUT: %d events in %.2f s = %.0f events/s", stored, seconds, rate);
        System.out.println(line);
        org.slf4j.LoggerFactory.getLogger(AuditIngestionThroughputIT.class).info(line);
        assertThat(stored).isEqualTo(EVENTS);

        for (UUID tenant : TENANTS) {
            JsonNode run = TenantContext.callAs(tenant, "perf", () -> integrity.verifyChain("ON_DEMAND", true));
            assertThat(run.get("valid").asBoolean()).as("chain of %s", tenant).isTrue();
            assertThat(run.get("checked").asLong()).isEqualTo(EVENTS / TENANTS.size());
        }
        // personal data was masked on the way in
        try (Connection c = DriverManager.getConnection(MeridianTestContainers.adminJdbcUrl(), "meridian", "meridian");
             PreparedStatement s = c.prepareStatement("select after_state->>'email' from audit.audit_event "
                     + "where tenant_id = ? limit 1")) {
            s.setObject(1, TENANTS.getFirst());
            try (ResultSet rs = s.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).startsWith("psn:");
            }
        }
        if (Boolean.getBoolean("audit.perf.enforce")) {
            assertThat(rate).isGreaterThanOrEqualTo(2_000);
        }
    }

    private static long count() throws Exception {
        try (Connection c = DriverManager.getConnection(MeridianTestContainers.adminJdbcUrl(), "meridian", "meridian");
             PreparedStatement s = c.prepareStatement("select count(*) from audit.audit_event where tenant_id = any(?)")) {
            s.setArray(1, c.createArrayOf("uuid", TENANTS.toArray()));
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void header(ProducerRecord<String, String> rec, String name, String value) {
        rec.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String payload(String id, int i) {
        return """
                {"id":"%s","customer_number":"C%08d","first_name":"Jane%d","last_name":"Doe","email":"jane%d@example.com",
                 "phone":"+4479%08d","date_of_birth":"1990-01-01","status":"ACTIVE","segment":"RETAIL","risk_rating":"LOW",
                 "address":{"line1":"%d High Street","city":"London","postal_code":"N1 1AA","country":"GB"},
                 "preferences":{"language":"en","marketing_opt_in":false},"version":%d,
                 "created_by":"system","updated_by":"alice","created_at":"2026-10-07T10:00:00Z","updated_at":"2026-10-07T10:00:00Z"}"""
                .formatted(id, i, i, i, i, i, i % 7 + 1);
    }
}
