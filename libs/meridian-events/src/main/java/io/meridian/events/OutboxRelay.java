package io.meridian.events;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls the outbox and publishes events to Kafka as binary-mode CloudEvents (ce_* headers), then deletes them.
 * {@code FOR UPDATE SKIP LOCKED} lets several replicas relay concurrently without duplicates; delivery is
 * at-least-once, so consumers de-duplicate on {@code ce_id}. In production Debezium CDC can replace this relay.
 */
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final String topic;
    private final String source;
    private final int batchSize;
    private io.micrometer.core.instrument.Counter relayed;
    private io.micrometer.core.instrument.Counter failures;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       String topic, String source, int batchSize) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.topic = topic;
        this.source = source;
        this.batchSize = batchSize;
    }

    /**
     * GAP-10 saturation metrics: {@code meridian.outbox.relayed} / {@code meridian.outbox.relay.failures} (counters) and
     * the backlog ({@code meridian.outbox.backlog} rows, {@code meridian.outbox.oldest.age} seconds), read from the
     * outbox at scrape time (the table only holds unpublished events, so the query is cheap).
     */
    public void bindMetrics(io.micrometer.core.instrument.MeterRegistry meters) {
        relayed = meters.counter("meridian.outbox.relayed");
        failures = meters.counter("meridian.outbox.relay.failures");
        io.micrometer.core.instrument.Gauge.builder("meridian.outbox.backlog", this, r -> r.backlog()[0])
                .description("Unpublished outbox events").strongReference(true).register(meters);
        io.micrometer.core.instrument.Gauge.builder("meridian.outbox.oldest.age", this, r -> r.backlog()[1])
                .baseUnit("seconds").description("Age of the oldest unpublished outbox event").strongReference(true)
                .register(meters);
    }

    private double[] backlog() {
        try {
            return jdbc.sql("select count(*), coalesce(extract(epoch from now() - min(created_at)), 0) from outbox")
                    .query((rs, i) -> new double[] {rs.getLong(1), rs.getDouble(2)}).single();
        } catch (RuntimeException e) {
            return new double[] {Double.NaN, Double.NaN};
        }
    }

    record Row(UUID id, UUID tenantId, String aggregateId, String type, String payload, OffsetDateTime createdAt) {}

    @Scheduled(fixedDelayString = "${meridian.events.relay-interval-ms:200}")
    public void relay() {
        Integer sent;
        do {
            sent = tx.execute(s -> relayBatch());
        } while (sent != null && sent == batchSize);
    }

    int relayBatch() {
        List<Row> rows = jdbc.sql("""
                select id, tenant_id, aggregate_id, event_type, payload::text, created_at
                from outbox order by created_at, id limit ? for update skip locked""")
                .param(batchSize)
                .query((rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getObject(6, OffsetDateTime.class)))
                .list();
        if (rows.isEmpty()) {
            return 0;
        }
        try {
            for (Row r : rows) {
                ProducerRecord<String, String> rec = new ProducerRecord<>(topic, r.aggregateId(), r.payload());
                header(rec, "ce_specversion", "1.0");
                header(rec, "ce_id", r.id().toString());
                header(rec, "ce_type", r.type());
                header(rec, "ce_source", source);
                header(rec, "ce_time", r.createdAt().toString());
                header(rec, "ce_tenantid", r.tenantId().toString());
                header(rec, "content-type", "application/json");
                kafka.send(rec).get(10, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            if (failures != null) {
                failures.increment();
            }
            log.warn("Outbox relay to {} failed; will retry: {}", topic, e.toString());
            throw new IllegalStateException(e);
        }
        jdbc.sql("delete from outbox where id = any(?)")
                .param(rows.stream().map(Row::id).toArray(UUID[]::new))
                .update();
        if (relayed != null) {
            relayed.increment(rows.size());
        }
        return rows.size();
    }

    private static void header(ProducerRecord<String, String> rec, String key, String value) {
        rec.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
    }
}
