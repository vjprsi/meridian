package io.meridian.events;

import io.meridian.common.id.Ids;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * MR-GAME-DAY: re-publishes relayed outbox events kept by the retention window, with their <b>original</b> CloudEvent
 * ids ({@code ce_id} = outbox id, same {@code ce_time}, {@code ce_type}, key and payload) so idempotent consumers
 * de-duplicate whatever they already applied. Used
 * <ul>
 *   <li>by operators ({@code POST /v1/internal/ops/outbox/replay}): time range, event types (exact or {@code prefix.*}),
 *       tenant, dry run;</li>
 *   <li>on standby promotion ({@code meridian.events.retention.replay-on-promote}): the last window of published rows
 *       relative to the newest {@code published_at} in the replicated database. These are the events the old region
 *       relayed that MSK Replicator may not have copied before the failure (the event-side gap of an unplanned
 *       failover).</li>
 * </ul>
 * Events go out in {@code (time, id)} order, keyed by aggregate as on the first delivery. Replayed rows are stamped
 * ({@code headers.replayed_at}, {@code headers.replay_id}); the promotion replay skips rows already replayed, so
 * replicas promoted one after another do not repeat it. Throttled ({@code max-per-second}) and capped per call.
 */
public class OutboxReplay {
    private static final Logger log = LoggerFactory.getLogger(OutboxReplay.class);
    static final int PAGE = 500;
    public static final int MAX_EVENTS = 1_000_000;

    /** Which timestamp the time range applies to. */
    public enum TimeBasis { CREATED, PUBLISHED }

    /**
     * A replay selection. {@code from} inclusive, {@code to} exclusive; {@code eventTypes} empty = all, an entry ending
     * in {@code *} is a prefix; {@code tenantId} null = all tenants.
     */
    public record Request(OffsetDateTime from, OffsetDateTime to, TimeBasis basis, List<String> eventTypes,
                          UUID tenantId, boolean dryRun, int maxEvents, boolean skipReplayed, String reason) {
        public Request {
            if (from == null || to == null || !from.isBefore(to)) {
                throw new IllegalArgumentException("from must be before to");
            }
            basis = basis == null ? TimeBasis.CREATED : basis;
            eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
            for (String t : eventTypes) {
                if (t == null || !t.matches("[a-z0-9_.]{1,127}\\*?")) {
                    throw new IllegalArgumentException("invalid event type " + t);
                }
            }
            maxEvents = maxEvents <= 0 ? 100_000 : Math.min(maxEvents, MAX_EVENTS);
        }
    }

    public record Result(String replayId, long matched, long published, OffsetDateTime first, OffsetDateTime last,
                         boolean dryRun, boolean truncated, Map<String, Long> byType) {}

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final String source;
    private final int maxPerSecond;
    private io.micrometer.core.instrument.Counter replayed;

    public OutboxReplay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, String topic, String source,
                        int maxPerSecond) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.topic = topic;
        this.source = source;
        this.maxPerSecond = maxPerSecond;
    }

    public void bindMetrics(io.micrometer.core.instrument.MeterRegistry meters) {
        replayed = meters.counter("meridian.outbox.replayed");
    }

    /** Re-publishes (or, for a dry run, counts) the selected relayed events. */
    public Result replay(Request req) {
        String replayId = "rpl-" + Ids.newId();
        String ts = req.basis() == TimeBasis.PUBLISHED ? "published_at" : "created_at";
        long matched = 0;
        long published = 0;
        OffsetDateTime first = null;
        OffsetDateTime last = null;
        Map<String, Long> byType = new TreeMap<>();
        OffsetDateTime afterTs = null;
        UUID afterId = null;
        long started = System.nanoTime();
        boolean truncated = false;
        while (true) {
            int limit = (int) Math.min(PAGE, req.maxEvents() - matched);
            if (limit <= 0) {
                truncated = hasMore(req, ts, afterTs, afterId);
                break;
            }
            List<Selected> page = page(req, ts, afterTs, afterId, limit);
            if (page.isEmpty()) {
                break;
            }
            List<CompletableFuture<?>> sends = new ArrayList<>();
            for (Selected s : page) {
                matched++;
                byType.merge(s.row().type(), 1L, Long::sum);
                first = first == null ? s.row().createdAt() : first;
                last = s.row().createdAt();
                if (!req.dryRun()) {
                    sends.add(kafka.send(OutboxRelay.record(topic, source, s.row(), replayId)));
                }
            }
            if (!req.dryRun()) {
                try {
                    CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("Replay " + replayId + " failed after " + published
                            + " events: " + e.getMessage(), e);
                }
                published += page.size();
                jdbc.sql("""
                        update outbox set headers = headers || jsonb_build_object('replayed_at', now()::text,
                            'replay_id', ?::text) where id = any(?)""")
                        .params(replayId, page.stream().map(s -> s.row().id()).toArray(UUID[]::new)).update();
                if (replayed != null) {
                    replayed.increment(page.size());
                }
                throttle(published, started);
            }
            Selected tail = page.getLast();
            afterTs = tail.ts();
            afterId = tail.row().id();
            if (page.size() < limit) {
                break;
            }
        }
        log.info("Outbox replay {} ({}): {} events matched, {} re-published to {} [{} .. {}) by {} types={} tenant={}{}",
                replayId, req.reason(), matched, published, topic, req.from(), req.to(), req.basis(),
                req.eventTypes(), req.tenantId(), req.dryRun() ? " (dry run)" : "");
        return new Result(replayId, matched, published, first, last, req.dryRun(), truncated, byType);
    }

    /**
     * The promotion replay: relayed rows published in the last {@code window} before the newest {@code published_at}
     * of the (replicated) outbox, not yet replayed. Returns null when nothing was ever relayed.
     */
    public Result replayLastWindow(Duration window, String reason) {
        OffsetDateTime newest = jdbc.sql("select max(published_at) from outbox")
                .query(OffsetDateTime.class).optional().orElse(null);
        if (newest == null) {
            return null;
        }
        return replay(new Request(newest.minus(window), newest.plusNanos(1_000), TimeBasis.PUBLISHED, List.of(), null,
                false, MAX_EVENTS, true, reason));
    }

    private record Selected(OutboxRelay.Row row, OffsetDateTime ts) {}

    private List<Selected> page(Request req, String ts, OffsetDateTime afterTs, UUID afterId, int limit) {
        List<Object> args = new ArrayList<>();
        String where = where(req, ts, afterTs, afterId, args);
        args.add(limit);
        return jdbc.sql("select id, tenant_id, aggregate_id, event_type, payload::text, created_at, " + ts
                        + " from outbox where " + where + " order by " + ts + ", id limit ?")
                .params(args)
                .query((rs, i) -> new Selected(new OutboxRelay.Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class)),
                        rs.getObject(7, OffsetDateTime.class)))
                .list();
    }

    private boolean hasMore(Request req, String ts, OffsetDateTime afterTs, UUID afterId) {
        List<Object> args = new ArrayList<>();
        String where = where(req, ts, afterTs, afterId, args);
        return jdbc.sql("select exists(select 1 from outbox where " + where + ")").params(args)
                .query(Boolean.class).single();
    }

    private static String where(Request req, String ts, OffsetDateTime afterTs, UUID afterId, List<Object> args) {
        StringBuilder w = new StringBuilder("published_at is not null and " + ts + " >= ? and " + ts + " < ?");
        args.add(req.from());
        args.add(req.to());
        if (!req.eventTypes().isEmpty()) {
            List<String> exact = new ArrayList<>();
            List<String> prefixes = new ArrayList<>();
            for (String t : req.eventTypes()) {
                if (t.endsWith("*")) {
                    prefixes.add(t.substring(0, t.length() - 1).replace("_", "\\_") + "%");
                } else {
                    exact.add(t);
                }
            }
            w.append(" and (event_type = any(?) or event_type like any(?))");
            args.add(exact.toArray(String[]::new));
            args.add(prefixes.toArray(String[]::new));
        }
        if (req.tenantId() != null) {
            w.append(" and tenant_id = ?");
            args.add(req.tenantId());
        }
        if (req.skipReplayed()) {
            w.append(" and headers->>'replayed_at' is null");
        }
        if (afterTs != null) {
            w.append(" and (").append(ts).append(", id) > (?, ?)");
            args.add(afterTs);
            args.add(afterId);
        }
        return w.toString();
    }

    private void throttle(long sent, long startedNanos) {
        if (maxPerSecond <= 0) {
            return;
        }
        long dueNanos = (long) (sent * 1_000_000_000.0 / maxPerSecond);
        long ahead = dueNanos - (System.nanoTime() - startedNanos);
        if (ahead > 0) {
            try {
                Thread.sleep(Duration.ofNanos(ahead));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("replay interrupted", e);
            }
        }
    }
}
