package io.meridian.audit.infrastructure;

import io.meridian.audit.application.AuditRecorder;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records every domain event published on the platform's Kafka topics (binary-mode CloudEvents from the outbox
 * relays) as an audit event of the emitting tenant. Delivery is at-least-once, so events are de-duplicated on
 * {@code ce_id} (stored as {@code source_event_id}). The audit service's own events are skipped.
 *
 * <p>Ingestion is batch-oriented (a restart used to leave the service > 30 s behind, one transaction, lock and
 * five round trips per event): a poll of up to {@code meridian.audit.ingest.max-poll-records} records is parsed and
 * PII-masked in parallel ({@code mask-parallelism} threads), grouped by tenant, and each tenant's events are appended
 * to its hash chain in one transaction with one advisory lock and multi-row inserts
 * ({@link AuditRecorder#appendBatch}); tenant groups run concurrently ({@code tenant-parallelism}) since their chains
 * are independent. Within a tenant the chain follows the order of the records in the poll.
 *
 * <p>Failure handling: if anything in the batch path fails (a poison record, a constraint), the whole poll is
 * processed again record by record; tenant groups that already committed are skipped by the source-event-id
 * de-duplication. A record that still fails is reported as {@link BatchListenerFailedException} with its index, so
 * the container's error handler commits the records before it and retries / skips that record exactly as it did
 * for the former record listener.
 */
@Component
public class DomainEventAuditListener {
    private static final Logger log = LoggerFactory.getLogger(DomainEventAuditListener.class);
    /** Listener container id (tests start / stop it through the registry). */
    public static final String LISTENER_ID = "audit-domain-events";

    private final AuditRecorder recorder;
    private final JsonMapper json;
    private final ExecutorService maskers;
    private final ExecutorService tenants;
    private final int maskParallelism;
    private final int tenantParallelism;

    public DomainEventAuditListener(AuditRecorder recorder, JsonMapper json,
                                    @Value("${meridian.audit.ingest.mask-parallelism:0}") int maskParallelism,
                                    @Value("${meridian.audit.ingest.tenant-parallelism:4}") int tenantParallelism) {
        this.recorder = recorder;
        this.json = json;
        this.maskParallelism = maskParallelism > 0 ? maskParallelism
                : Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        this.tenantParallelism = Math.max(1, tenantParallelism);
        this.maskers = Executors.newFixedThreadPool(this.maskParallelism, Thread.ofPlatform().daemon()
                .name("audit-mask-", 0).factory());
        // bounded by the connection pool: each tenant group holds one connection for its transaction
        this.tenants = Executors.newFixedThreadPool(this.tenantParallelism, Thread.ofVirtual()
                .name("audit-chain-", 0).factory());
    }

    @PreDestroy
    void shutdown() {
        maskers.shutdownNow();
        tenants.shutdownNow();
    }

    /** One audit event prepared from a consumed record. */
    private record Mapped(UUID tenant, AuditRecorder.NewAuditEvent event) {}

    /** A masked event and its tenant. */
    private record Item(UUID tenant, AuditRecorder.Prepared prepared) {}

    @KafkaListener(id = LISTENER_ID, topicPattern = "meridian\\..*\\.v1", groupId = "audit-service",
            autoStartup = "${meridian.audit.listener-enabled:true}", batch = "true",
            properties = {"max.poll.records:${meridian.audit.ingest.max-poll-records:1000}",
                    "fetch.min.bytes:${meridian.audit.ingest.fetch-min-bytes:1}"})
    public void onEvents(List<ConsumerRecord<String, String>> records) {
        if (records.isEmpty()) {
            return;
        }
        try {
            ingest(records);
        } catch (RuntimeException e) {
            log.warn("Audit batch of {} records failed ({}); processing them one by one", records.size(), e.toString());
            for (int i = 0; i < records.size(); i++) {
                try {
                    onEvent(records.get(i));
                } catch (Exception failed) {
                    throw new BatchListenerFailedException("audit record failed: " + failed.getMessage(), failed, i);
                }
            }
        }
    }

    /** Records one consumed event (the per-record path; also used when a batch falls back). */
    public void onEvent(ConsumerRecord<String, String> rec) throws Exception {
        Mapped m = map(rec);
        if (m != null) {
            TenantContext.callAs(m.tenant(), "audit-service", () -> recorder.recordOnce(m.event()));
        }
    }

    private void ingest(List<ConsumerRecord<String, String>> records) {
        Map<UUID, List<AuditRecorder.Prepared>> byTenant = new LinkedHashMap<>();
        for (Item item : prepareAll(records)) {
            if (item != null) {
                byTenant.computeIfAbsent(item.tenant(), t -> new ArrayList<>()).add(item.prepared());
            }
        }
        if (byTenant.isEmpty()) {
            return;
        }
        if (byTenant.size() == 1) {
            Map.Entry<UUID, List<AuditRecorder.Prepared>> only = byTenant.entrySet().iterator().next();
            append(only.getKey(), only.getValue());
            return;
        }
        List<Future<?>> running = new ArrayList<>(byTenant.size());
        for (Map.Entry<UUID, List<AuditRecorder.Prepared>> g : byTenant.entrySet()) {
            running.add(tenants.submit(() -> append(g.getKey(), g.getValue())));
        }
        RuntimeException first = null;
        for (Future<?> f : running) {
            try {
                f.get();
            } catch (ExecutionException e) {
                if (first == null) {
                    first = e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while appending audit events", e);
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private void append(UUID tenant, List<AuditRecorder.Prepared> events) {
        try {
            TenantContext.callAs(tenant, "audit-service", () -> recorder.appendBatch(events));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Parses, maps and masks the records in parallel; null for records that are skipped. Input order. */
    private List<Item> prepareAll(List<ConsumerRecord<String, String>> records) {
        int n = records.size();
        Item[] out = new Item[n];
        int chunks = Math.min(maskParallelism, Math.max(1, n / 32));
        int size = (n + chunks - 1) / chunks;
        List<Future<?>> running = new ArrayList<>(chunks);
        for (int c = 0; c < chunks; c++) {
            int from = c * size;
            int to = Math.min(n, from + size);
            Runnable work = () -> {
                for (int i = from; i < to; i++) {
                    Mapped m = map(records.get(i));
                    out[i] = m == null ? null : new Item(m.tenant(), recorder.prepare(m.tenant(), m.event()));
                }
            };
            if (chunks == 1) {
                work.run();
            } else {
                running.add(maskers.submit(work));
            }
        }
        for (Future<?> f : running) {
            try {
                f.get();
            } catch (ExecutionException e) {
                throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while masking audit events", e);
            }
        }
        return java.util.Arrays.asList(out);
    }

    /** The audit event of a consumed CloudEvent, or null when the record is not audited. */
    private Mapped map(ConsumerRecord<String, String> rec) {
        String type = header(rec, "ce_type");
        String tenant = header(rec, "ce_tenantid");
        String id = header(rec, "ce_id");
        String source = header(rec, "ce_source");
        if (type == null || tenant == null || id == null) {
            log.debug("Skipping non-CloudEvents record on {}", rec.topic());
            return null;
        }
        if (type.startsWith("platform.audit_") || "audit-service".equals(source)) {
            return null;
        }
        JsonNode payload = rec.value() == null ? null : json.readTree(rec.value());
        String actor = text(payload, "updated_by");
        if (actor == null) {
            actor = text(payload, "created_by");
        }
        boolean system = actor == null || "system".equals(actor) || "anonymous".equals(actor);
        String[] parts = type.split("\\.");
        String resourceType = parts.length >= 3 ? parts[1] : type;
        Instant at = parseTime(header(rec, "ce_time"));
        UUID legalEntity = uuid(text(payload, "legal_entity_id"));
        return new Mapped(UUID.fromString(tenant), new AuditRecorder.NewAuditEvent(at,
                system ? "SYSTEM" : "STAFF_USER", truncate(system ? "system" : actor, 64), null, null,
                truncate(type, 128), truncate(resourceType, 64), truncate(rec.key(), 64), "SUCCESS", null,
                null, payload != null && payload.isObject() ? payload : null, legalEntity,
                uuid(text(payload, "approval_request_id")), null, null, null, null, null,
                truncate(source == null ? rec.topic() : source, 64), truncate(id, 64)));
    }

    private static String header(ConsumerRecord<String, String> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static String text(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) ? n.get(f).asString() : null;
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant parseTime(String s) {
        try {
            return s == null ? Clocks.now().toInstant() : OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException e) {
            return Clocks.now().toInstant();
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
