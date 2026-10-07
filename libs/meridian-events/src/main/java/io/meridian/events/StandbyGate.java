package io.meridian.events;

import io.meridian.common.cell.CellRole;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * MR-GAME-DAY: standby promotion without a restart.
 *
 * <p>In a warm standby cell ({@link CellRole.Role#STANDBY}) this lifecycle bean runs just before the Kafka listener
 * registry starts and switches {@code autoStartup} off on every listener container that would have started (including
 * listeners with their own {@code autoStartup} expression, which ignore {@code spring.kafka.listener.auto-startup}),
 * remembering them. The outbox relay is created paused.
 *
 * <p>On promotion ({@link CellRole#promote}, operator endpoint or registry watcher) it, on a background thread:
 * <ol>
 *   <li>waits until the database accepts writes (Aurora Global failover / replica promotion done:
 *       {@code pg_is_in_recovery() = false} and {@code transaction_read_only = off}), up to
 *       {@code meridian.cell.promotion.db-wait} (default 10 min);</li>
 *   <li>optionally re-publishes the last {@code meridian.events.retention.replay-on-promote} of relayed outbox rows
 *       (the event-side gap of an unplanned failover; consumers de-duplicate by {@code ce_id});</li>
 *   <li>resumes the outbox relay;</li>
 *   <li>starts the held listener containers.</li>
 * </ol>
 * Demotion pauses the relay and stops the listener containers again (planned failback). Status: {@link #state()}.
 */
public class StandbyGate implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(StandbyGate.class);

    /** Promotion progress, for the operator endpoint and logs. */
    public enum State { STANDBY, WAITING_FOR_DATABASE, REPLAYING, ACTIVE, FAILED }

    private final CellRole role;
    private final ObjectProvider<KafkaListenerEndpointRegistry> registry;
    private final OutboxRelay relay;
    private final OutboxReplay replay;
    private final Duration replayOnPromote;
    private final BooleanSupplier databaseWritable;
    private final Duration dbWait;
    private final Duration dbPoll;
    private final List<MessageListenerContainer> held = new ArrayList<>();
    private final AtomicReference<State> state = new AtomicReference<>();
    private volatile String detail = "";
    private volatile boolean running;
    private volatile Thread worker;

    public StandbyGate(CellRole role, ObjectProvider<KafkaListenerEndpointRegistry> registry, OutboxRelay relay,
                       OutboxReplay replay, Duration replayOnPromote, BooleanSupplier databaseWritable,
                       Duration dbWait, Duration dbPoll) {
        this.role = role;
        this.registry = registry;
        this.relay = relay;
        this.replay = replay;
        this.replayOnPromote = replayOnPromote == null ? Duration.ZERO : replayOnPromote;
        this.databaseWritable = databaseWritable;
        this.dbWait = dbWait;
        this.dbPoll = dbPoll;
        this.state.set(role.active() ? State.ACTIVE : State.STANDBY);
        role.onChange(this::onChange);
    }

    /** {@code select not pg_is_in_recovery() and current_setting('transaction_read_only') = 'off'}. */
    public static BooleanSupplier postgresWritable(JdbcClient jdbc) {
        return () -> {
            try {
                return Boolean.TRUE.equals(jdbc.sql(
                        "select not pg_is_in_recovery() and current_setting('transaction_read_only') = 'off'")
                        .query(Boolean.class).single());
            } catch (RuntimeException e) {
                return false;
            }
        };
    }

    public State state() {
        return state.get();
    }

    public String detail() {
        return detail;
    }

    /** Listener containers held back while standby (ids). */
    public synchronized List<String> heldListeners() {
        return held.stream().map(MessageListenerContainer::getListenerId).toList();
    }

    // ------------------------------------------------------------------ lifecycle: before the listener registry

    @Override
    public void start() {
        running = true;
        if (!role.active()) {
            hold();
        }
    }

    @Override
    public void stop() {
        running = false;
        Thread w = worker;
        if (w != null) {
            w.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Just before {@link KafkaListenerEndpointRegistry} (phase {@code Integer.MAX_VALUE - 100}). */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 101;
    }

    private synchronized void hold() {
        KafkaListenerEndpointRegistry r = registry.getIfAvailable();
        if (r != null) {
            for (MessageListenerContainer c : r.getListenerContainers()) {
                if (c.isAutoStartup() || c.isRunning()) {
                    c.setAutoStartup(false);
                    if (c.isRunning()) {
                        c.stop();
                    }
                    if (!held.contains(c)) {
                        held.add(c);
                    }
                }
            }
        }
        if (relay != null) {
            relay.pause();
        }
        log.info("Cell {} is a warm standby: outbox relay paused, {} Kafka listener(s) held {}", role.cellId(),
                held.size(), heldListeners());
    }

    // ------------------------------------------------------------------ promotion / demotion

    private void onChange(CellRole.Change c) {
        if (c.to() == CellRole.Role.PRIMARY) {
            Thread t = Thread.ofVirtual().name("standby-promotion").unstarted(this::promote);
            worker = t;
            t.start();
        } else {
            Thread w = worker;
            if (w != null) {
                w.interrupt();
            }
            hold();
            state.set(State.STANDBY);
            detail = "demoted: " + c.reason();
        }
    }

    /** Re-runs a promotion that FAILED (e.g. the database stayed read-only past the wait); false otherwise. */
    public boolean retry() {
        if (!role.active() || state.get() != State.FAILED) {
            return false;
        }
        Thread t = Thread.ofVirtual().name("standby-promotion").unstarted(this::promote);
        worker = t;
        t.start();
        return true;
    }

    /** Runs the promotion steps on the calling thread (the role is already PRIMARY). */
    void promote() {
        long t0 = System.nanoTime();
        try {
            state.set(State.WAITING_FOR_DATABASE);
            detail = "waiting for a writable database";
            long deadline = System.nanoTime() + dbWait.toNanos();
            while (!databaseWritable.getAsBoolean()) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("database still read-only after " + dbWait);
                }
                Thread.sleep(dbPoll);
            }
            if (!role.active()) {
                return;
            }
            if (replay != null && !replayOnPromote.isZero()) {
                state.set(State.REPLAYING);
                detail = "re-publishing the last " + replayOnPromote + " of relayed events";
                OutboxReplay.Result r = replay.replayLastWindow(replayOnPromote, "standby promotion of " + role.cellId());
                detail = r == null ? "nothing to replay" : "replayed " + r.published() + " events (" + r.replayId() + ")";
            }
            if (relay != null) {
                relay.resume();
            }
            List<MessageListenerContainer> start;
            synchronized (this) {
                start = new ArrayList<>(held);
                held.clear();
            }
            for (MessageListenerContainer c : start) {
                c.setAutoStartup(true);
                if (!c.isRunning()) {
                    c.start();
                }
            }
            state.set(State.ACTIVE);
            detail = "promoted in " + Duration.ofNanos(System.nanoTime() - t0).toMillis() + " ms; " + start.size()
                    + " listener(s) started";
            log.info("Cell {} promoted to primary: {}", role.cellId(), detail);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.set(State.FAILED);
            detail = "promotion interrupted";
        } catch (RuntimeException e) {
            state.set(State.FAILED);
            detail = "promotion failed: " + e.getMessage();
            log.error("Standby promotion of cell {} failed: {}", role.cellId(), e.toString());
        }
    }
}
