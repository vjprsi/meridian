package io.meridian.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * MR-GAME-DAY outbox replay retention against real PostgreSQL 18 with the generated outbox DDL: relayed rows are kept
 * (published_at) instead of deleted, replays re-publish them with their original CloudEvent ids (filters by time,
 * type prefix, tenant; dry run), the promotion replay covers the last window once, and the purge job removes only
 * relayed rows past the window.
 */
class OutboxRetentionReplayIT {
    static final PostgreSQLContainer PG = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))
            .withDatabaseName("events_test").withUsername("meridian").withPassword("meridian");

    static JdbcClient jdbc;
    static TransactionTemplate tx;
    final List<ProducerRecord<String, String>> sent = new ArrayList<>();
    KafkaTemplate<String, String> kafka;
    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();

    @BeforeAll
    static void schema() throws Exception {
        PG.start();
        DriverManagerDataSource ds = new DriverManagerDataSource(PG.getJdbcUrl() + "&currentSchema=document", "meridian", "meridian");
        jdbc = JdbcClient.create(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        String ddl = Files.readString(Path.of("../../generated/db/document_db/V001__init.sql"), StandardCharsets.UTF_8);
        JdbcClient.create(new DriverManagerDataSource(PG.getJdbcUrl(), "meridian", "meridian")).sql(ddl).update();
    }

    @AfterAll
    static void stop() {
        PG.stop();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void reset() {
        jdbc.sql("delete from outbox").update();
        kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });
    }

    private UUID event(UUID tenant, String type, OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into outbox (id, tenant_id, aggregate_type, aggregate_id, event_type, payload, created_at)
                values (?, ?, 'x', ?, ?, '{"k":1}'::jsonb, ?)""")
                .params(id, tenant, UUID.randomUUID(), type, createdAt).update();
        return id;
    }

    private static String header(ProducerRecord<String, String> r, String k) {
        var h = r.headers().lastHeader(k);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    @Test
    void relayKeepsRowsForTheWindowAndReplayRepublishesWithOriginalIds() {
        OffsetDateTime t0 = OffsetDateTime.parse("2026-10-01T10:00:00Z");
        UUID a1 = event(tenantA, "payments.payment.completed", t0);
        UUID a2 = event(tenantA, "payments.payment_return.created", t0.plusMinutes(1));
        UUID b1 = event(tenantB, "payments.payment.completed", t0.plusMinutes(2));
        UUID a3 = event(tenantA, "cards.card.issued", t0.plusMinutes(3));

        OutboxRelay relay = new OutboxRelay(jdbc, kafka, tx, "meridian.payments.v1", "payment-service", 2,
                Duration.ofHours(72), false);
        relay.relay();
        assertThat(sent).hasSize(4);
        assertThat(sent.stream().map(r -> header(r, "ce_id")).toList())
                .containsExactly(a1.toString(), a2.toString(), b1.toString(), a3.toString());
        assertThat(jdbc.sql("select count(*) from outbox where published_at is not null").query(Long.class).single()).isEqualTo(4);
        sent.clear();
        relay.relay();
        assertThat(sent).isEmpty();

        OutboxReplay replay = new OutboxReplay(jdbc, kafka, "meridian.payments.v1", "payment-service", 0);
        OutboxReplay.Result dry = replay.replay(new OutboxReplay.Request(t0, t0.plusHours(1), OutboxReplay.TimeBasis.CREATED,
                List.of("payments.*"), tenantA, true, 0, false, "test"));
        assertThat(dry.matched()).isEqualTo(2);
        assertThat(dry.published()).isZero();
        assertThat(sent).isEmpty();

        OutboxReplay.Result r = replay.replay(new OutboxReplay.Request(t0, t0.plusHours(1), OutboxReplay.TimeBasis.CREATED,
                List.of("payments.*"), tenantA, false, 0, false, "test"));
        assertThat(r.published()).isEqualTo(2);
        assertThat(sent.stream().map(x -> header(x, "ce_id")).toList()).containsExactly(a1.toString(), a2.toString());
        assertThat(sent).allSatisfy(x -> {
            assertThat(header(x, "ce_meridianreplay")).isEqualTo(r.replayId());
            assertThat(header(x, "ce_tenantid")).isEqualTo(tenantA.toString());
        });
        assertThat(header(sent.getFirst(), "ce_time")).isEqualTo(t0.toString());

        sent.clear();
        OutboxReplay.Result exact = replay.replay(new OutboxReplay.Request(t0, t0.plusHours(1), OutboxReplay.TimeBasis.CREATED,
                List.of("cards.card.issued", "payments.payment.completed"), null, false, 2, false, "test"));
        assertThat(exact.published()).isEqualTo(2);
        assertThat(exact.truncated()).isTrue();
        assertThat(sent.stream().map(x -> header(x, "ce_id")).toList()).containsExactly(a1.toString(), b1.toString());
    }

    @Test
    void promotionReplayCoversTheLastWindowOnceAndPausedRelaySendsNothing() {
        OffsetDateTime t0 = OffsetDateTime.parse("2026-10-01T10:00:00Z");
        UUID old = event(tenantA, "ledger.journal_entry.posted", t0);
        UUID recent1 = event(tenantA, "ledger.journal_entry.posted", t0.plusMinutes(1));
        UUID recent2 = event(tenantB, "ledger.journal_entry.posted", t0.plusMinutes(2));
        UUID pending = event(tenantB, "ledger.journal_entry.posted", t0.plusMinutes(3));
        jdbc.sql("update outbox set published_at = created_at + interval '1 second' where id <> ?").param(pending).update();

        OutboxRelay paused = new OutboxRelay(jdbc, kafka, tx, "meridian.accounts.v1", "ledger-service", 10,
                Duration.ofHours(72), true);
        paused.relay();
        assertThat(sent).isEmpty();

        OutboxReplay replay = new OutboxReplay(jdbc, kafka, "meridian.accounts.v1", "ledger-service", 0);
        OutboxReplay.Result r = replay.replayLastWindow(Duration.ofSeconds(90), "promotion");
        assertThat(r.published()).isEqualTo(2);
        assertThat(sent.stream().map(x -> header(x, "ce_id")).toList()).containsExactly(recent1.toString(), recent2.toString());
        assertThat(sent.stream().map(x -> header(x, "ce_id"))).doesNotContain(old.toString(), pending.toString());

        sent.clear();
        OutboxReplay.Result again = replay.replayLastWindow(Duration.ofSeconds(90), "second replica");
        assertThat(again.published()).isZero();

        paused.resume();
        paused.relay();
        assertThat(sent.stream().map(x -> header(x, "ce_id")).toList()).containsExactly(pending.toString());
    }

    @Test
    void purgeRemovesOnlyRelayedRowsPastTheWindowAndZeroWindowDeletesOnRelay() {
        OffsetDateTime now = io.meridian.common.time.Clocks.now();
        UUID expired = event(tenantA, "x.y.z", now.minusDays(5));
        UUID kept = event(tenantA, "x.y.z", now.minusHours(1));
        UUID pendingOld = event(tenantA, "x.y.z", now.minusDays(9));
        jdbc.sql("update outbox set published_at = created_at where id in (?, ?)").params(expired, kept).update();

        OutboxRetention retention = new OutboxRetention(jdbc, Duration.ofHours(72), 1, null);
        assertThat(retention.purge()).isEqualTo(1);
        assertThat(jdbc.sql("select id from outbox order by created_at").query(UUID.class).list())
                .containsExactly(pendingOld, kept);

        new OutboxRelay(jdbc, kafka, tx, "t", "s", 10, Duration.ZERO, false).relay();
        assertThat(jdbc.sql("select id from outbox").query(UUID.class).list()).containsExactly(kept);
    }
}
