package io.meridian.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.meridian.audit.application.AuditIntegrityService;
import io.meridian.audit.application.AuditRecorder;
import io.meridian.audit.infrastructure.DomainEventAuditListener;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.listener.BatchListenerFailedException;
import tools.jackson.databind.JsonNode;

/**
 * Batch ingestion keeps the per-tenant hash chains valid: interleaved tenants in one poll, events recorded through
 * the API path in between, duplicates inside a poll and across polls, and a poison record (the poll falls back to
 * per-record processing and reports the failing index to the container's error handler).
 */
@SpringBootTest(properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.audit.listener-enabled=false", "meridian.approvals.listener-enabled=false",
        "meridian.audit.integrity.cron=-"})
@Import(MeridianTestContainers.class)
class AuditBatchIngestionIT {
    @Autowired
    DomainEventAuditListener listener;

    @Autowired
    AuditRecorder recorder;

    @Autowired
    AuditIntegrityService integrity;

    @Autowired
    JdbcClient jdbc;

    @Test
    void interleavedTenantsDuplicatesAndApiWritesKeepEveryChainValid() throws Exception {
        List<UUID> tenants = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        apiEvent(tenants.get(0), "before-batch");
        List<ConsumerRecord<String, String>> poll = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            poll.add(record(tenants.get(i % 3), UUID.randomUUID().toString(), "{\"id\":\"x" + i + "\",\"email\":\"a"
                    + i + "@example.com\",\"updated_by\":\"alice\",\"amount\":1.50}"));
        }
        poll.add(poll.get(4)); // redelivered inside the same poll
        listener.onEvents(poll);
        apiEvent(tenants.get(1), "between-batches");
        List<ConsumerRecord<String, String>> second = new ArrayList<>(poll.subList(0, 10)); // redelivered poll
        for (int i = 0; i < 30; i++) {
            second.add(record(tenants.get(i % 3), UUID.randomUUID().toString(), "{\"id\":\"y" + i + "\"}"));
        }
        listener.onEvents(second);

        assertThat(count(tenants.get(0))).isEqualTo(1 + 30 + 10);
        assertThat(count(tenants.get(1))).isEqualTo(30 + 1 + 10);
        assertThat(count(tenants.get(2))).isEqualTo(30 + 10);
        for (UUID t : tenants) {
            assertValid(t, count(t));
        }
        String email = TenantContext.callAs(tenants.get(0), "test", () -> jdbc.sql(
                "select after_state->>'email' from audit_event where after_state->>'email' is not null limit 1")
                .query(String.class).single());
        assertThat(email).startsWith("psn:");
    }

    @Test
    void aPoisonRecordFallsBackToPerRecordProcessingAndIsReportedByIndex() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<ConsumerRecord<String, String>> poll = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            poll.add(record(i % 2 == 0 ? a : b, UUID.randomUUID().toString(), "{\"id\":\"p" + i + "\"}"));
        }
        poll.add(12, record(a, UUID.randomUUID().toString(), "{not json"));
        BatchListenerFailedException failed = catchThrowableOfType(BatchListenerFailedException.class,
                () -> listener.onEvents(poll));
        assertThat(failed).isNotNull();
        assertThat(failed.getIndex()).isEqualTo(12);
        // the error handler commits the records before the poison one and redelivers from it; after it is skipped
        // the rest arrives again
        listener.onEvents(poll.subList(13, poll.size()));
        assertThat(count(a) + count(b)).isEqualTo(20);
        assertValid(a, 10);
        assertValid(b, 10);
    }

    private void assertValid(UUID tenant, long expected) throws Exception {
        JsonNode run = TenantContext.callAs(tenant, "test", () -> integrity.verifyChain("ON_DEMAND", true));
        assertThat(run.get("valid").asBoolean()).as("chain of %s", tenant).isTrue();
        assertThat(run.get("checked").asLong()).isEqualTo(expected);
        List<Long> seq = TenantContext.callAs(tenant, "test", () -> jdbc.sql(
                "select sequence_no from audit_event order by sequence_no").query(Long.class).list());
        for (int i = 0; i < seq.size(); i++) {
            assertThat(seq.get(i)).isEqualTo(i + 1L);
        }
    }

    private long count(UUID tenant) throws Exception {
        return TenantContext.callAs(tenant, "test", () -> jdbc.sql("select count(*) from audit_event")
                .query(Long.class).single());
    }

    private void apiEvent(UUID tenant, String reason) throws Exception {
        TenantContext.callAs(tenant, "svc", () -> recorder.record(new AuditRecorder.NewAuditEvent(null, "SYSTEM",
                "system", null, null, "platform.cell.drained", "cell", "c-1", "SUCCESS", reason, null, null, null,
                null, "10.0.0.1", null, null, null, null, "tenant-service", null)));
    }

    private static ConsumerRecord<String, String> record(UUID tenant, String ceId, String value) {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>("meridian.customers.v1", 0, 0L,
                UUID.randomUUID().toString(), value);
        rec.headers().add("ce_type", "customers.customer.updated".getBytes(StandardCharsets.UTF_8));
        rec.headers().add("ce_id", ceId.getBytes(StandardCharsets.UTF_8));
        rec.headers().add("ce_tenantid", tenant.toString().getBytes(StandardCharsets.UTF_8));
        rec.headers().add("ce_source", "customer-service".getBytes(StandardCharsets.UTF_8));
        rec.headers().add("ce_time", Instant.now().toString().getBytes(StandardCharsets.UTF_8));
        return rec;
    }
}
