package io.meridian.events;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * Polls the outbox and publishes events to Kafka as binary-mode CloudEvents (ce_* headers). {@code FOR UPDATE SKIP
 * LOCKED} lets several replicas relay concurrently without duplicates; delivery is at-least-once, so consumers
 * de-duplicate on {@code ce_id}. In production Debezium CDC can replace this relay.
 *
 * <p>MR-GAME-DAY replay retention: with {@code meridian.events.retention.window} &gt; 0 (default 72h) a relayed row is
 * kept and stamped {@code published_at} instead of being deleted, so that an operator (or the standby promotion) can
 * re-publish a time range with the original ids ({@link OutboxReplay}); {@link OutboxRetention} purges rows past the
 * window. A zero window restores the old delete-on-relay behaviour.
 *
 * <p>The relay is <em>paused</em> while the cell is a warm standby (its database is a read-only replica and its topics
 * are replicated): {@link #pause()} / {@link #resume()} switch it at runtime, without a restart.
 */
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final String topic;
    private final String source;
    private final int batchSize;
    private final Duration retention;
    private volatile boolean paused;
    private io.micrometer.core.instrument.Counter relayed;
    private io.micrometer.core.instrument.Counter failures;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       String topic, String source, int batchSize) {
        this(jdbc, kafka, tx, topic, source, batchSize, Duration.ZERO, false);
    }

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       String topic, String source, int batchSize, Duration retention, boolean paused) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.topic = topic;
        this.source = source;
        this.batchSize = batchSize;
        this.retention = retention == null || retention.isNegative() ? Duration.ZERO : retention;
        this.paused = paused;
    }

    /**
     * GAP-10 saturation metrics: {@code meridian.outbox.relayed} / {@code meridian.outbox.relay.failures} (counters) and
     * the backlog ({@code meridian.outbox.backlog} rows, {@code meridian.outbox.oldest.age} seconds), read from the
     * outbox at scrape time (partial index on unpublished rows, so the query stays cheap with retention).
     * MR-GAME-DAY: {@code meridian.outbox.relay.paused} is 1 while the relay is paused (warm standby).
     */
    public void bindMetrics(io.micrometer.core.instrument.MeterRegistry meters) {
        relayed = meters.counter("meridian.outbox.relayed");
        failures = meters.counter("meridian.outbox.relay.failures");
        io.micrometer.core.instrument.Gauge.builder("meridian.outbox.backlog", this, r -> r.backlog()[0])
                .description("Unpublished outbox events").strongReference(true).register(meters);
        io.micrometer.core.instrument.Gauge.builder("meridian.outbox.oldest.age", this, r -> r.backlog()[1])
                .baseUnit("seconds").description("Age of the oldest unpublished outbox event").strongReference(true)
                .register(meters);
        io.micrometer.core.instrument.Gauge.builder("meridian.outbox.relay.paused", this, r -> r.paused ? 1 : 0)
                .description("1 while the outbox relay is paused (warm standby cell)").strongReference(true)
                .register(meters);
    }

    private double[] backlog() {
        try {
            return jdbc.sql("""
                    select count(*), coalesce(extract(epoch from now() - min(created_at)), 0)
                    from outbox where published_at is null""")
                    .query((rs, i) -> new double[] {rs.getLong(1), rs.getDouble(2)}).single();
        } catch (RuntimeException e) {
            return new double[] {Double.NaN, Double.NaN};
        }
    }

    public boolean paused() {
        return paused;
    }

    /** Stops relaying (standby cell); in-flight batches finish. */
    public void pause() {
        if (!paused) {
            paused = true;
            log.info("Outbox relay to {} paused", topic);
        }
    }

    /** Starts relaying again (standby promoted to primary). */
    public void resume() {
        if (paused) {
            paused = false;
            log.info("Outbox relay to {} resumed", topic);
        }
    }

    public Duration retention() {
        return retention;
    }

    public String topic() {
        return topic;
    }

    public String source() {
        return source;
    }

    record Row(UUID id, UUID tenantId, String aggregateId, String type, String payload, OffsetDateTime createdAt) {}

    @Scheduled(fixedDelayString = "${meridian.events.relay-interval-ms:200}")
    public void relay() {
        if (paused) {
            return;
        }
        Integer sent;
        do {
            sent = tx.execute(s -> relayBatch());
        } while (!paused && sent != null && sent == batchSize);
    }

    int relayBatch() {
        List<Row> rows = jdbc.sql("""
                select id, tenant_id, aggregate_id, event_type, payload::text, created_at
                from outbox where published_at is null order by created_at, id limit ? for update skip locked""")
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
                kafka.send(record(topic, source, r, null)).get(10, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            if (failures != null) {
                failures.increment();
            }
            log.warn("Outbox relay to {} failed; will retry: {}", topic, e.toString());
            throw new IllegalStateException(e);
        }
        UUID[] ids = rows.stream().map(Row::id).toArray(UUID[]::new);
        if (retention.isZero()) {
            jdbc.sql("delete from outbox where id = any(?)").param(ids).update();
        } else {
            jdbc.sql("update outbox set published_at = now() where id = any(?)").param(ids).update();
        }
        if (relayed != null) {
            relayed.increment(rows.size());
        }
        return rows.size();
    }

    /**
     * The Kafka record of an outbox row: binary-mode CloudEvent with the row id as {@code ce_id} (a replay sends the
     * same id, so consumers de-duplicate it) and, for a replay, the extension {@code ce_meridianreplay}.
     */
    static ProducerRecord<String, String> record(String topic, String source, Row r, String replayId) {
        ProducerRecord<String, String> rec = new ProducerRecord<>(topic, r.aggregateId(), r.payload());
        header(rec, "ce_specversion", "1.0");
        header(rec, "ce_id", r.id().toString());
        header(rec, "ce_type", r.type());
        header(rec, "ce_source", source);
        header(rec, "ce_time", r.createdAt().toString());
        header(rec, "ce_tenantid", r.tenantId().toString());
        header(rec, "content-type", "application/json");
        if (replayId != null) {
            header(rec, "ce_meridianreplay", replayId);
        }
        return rec;
    }

    private static void header(ProducerRecord<String, String> rec, String key, String value) {
        rec.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
    }
}
