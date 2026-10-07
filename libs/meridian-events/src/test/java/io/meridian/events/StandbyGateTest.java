package io.meridian.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.meridian.common.cell.CellRole;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * MR-GAME-DAY standby promotion without restart: a standby cell holds back every listener container that would have
 * started (also those with their own autoStartup expression) and pauses the relay; promotion waits for a writable
 * database, then resumes the relay and starts exactly the held containers; demotion holds them again.
 */
class StandbyGateTest {

    /** Minimal container double: tracks autoStartup and running. */
    static final class FakeContainer {
        final MessageListenerContainer mock = mock(MessageListenerContainer.class);
        boolean autoStartup;
        boolean running;

        FakeContainer(String id, boolean autoStartup) {
            this.autoStartup = autoStartup;
            when(mock.getListenerId()).thenReturn(id);
            when(mock.isAutoStartup()).thenAnswer(i -> this.autoStartup);
            when(mock.isRunning()).thenAnswer(i -> running);
            org.mockito.Mockito.doAnswer(i -> {
                this.autoStartup = i.getArgument(0);
                return null;
            }).when(mock).setAutoStartup(org.mockito.ArgumentMatchers.anyBoolean());
            org.mockito.Mockito.doAnswer(i -> {
                running = true;
                return null;
            }).when(mock).start();
            org.mockito.Mockito.doAnswer(i -> {
                running = false;
                return null;
            }).when(mock).stop();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void standbyHoldsListenersAndRelayUntilPromotedAndTheDatabaseIsWritable() {
        FakeContainer audit = new FakeContainer("audit-domain-events", true);      // own autoStartup expression
        FakeContainer approvals = new FakeContainer("approvals", true);
        FakeContainer disabled = new FakeContainer("feature-off", false);          // switched off by its own flag
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        when(registry.getListenerContainers()).thenReturn(List.of(audit.mock, approvals.mock, disabled.mock));
        ObjectProvider<KafkaListenerEndpointRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);

        CellRole role = new CellRole("euw2c01", CellRole.parse("standby"));
        OutboxRelay relay = new OutboxRelay(null, null, null, "t", "s", 10, Duration.ofHours(72), !role.active());
        AtomicBoolean writable = new AtomicBoolean(false);
        StandbyGate gate = new StandbyGate(role, provider, relay, null, Duration.ZERO, writable::get,
                Duration.ofSeconds(30), Duration.ofMillis(20));

        gate.start();                                     // lifecycle phase just before the registry
        assertThat(audit.autoStartup).isFalse();
        assertThat(approvals.autoStartup).isFalse();
        assertThat(gate.heldListeners()).containsExactly("audit-domain-events", "approvals");
        assertThat(relay.paused()).isTrue();
        assertThat(gate.state()).isEqualTo(StandbyGate.State.STANDBY);
        relay.relay();                                    // paused: no database or Kafka access at all

        assertThat(role.promote("game day", "platform-ops")).isTrue();
        waitFor(() -> gate.state() == StandbyGate.State.WAITING_FOR_DATABASE);
        assertThat(audit.running).isFalse();
        assertThat(relay.paused()).isTrue();

        writable.set(true);                               // Aurora secondary promoted
        waitFor(() -> gate.state() == StandbyGate.State.ACTIVE);
        assertThat(relay.paused()).isFalse();
        assertThat(audit.running).isTrue();
        assertThat(approvals.running).isTrue();
        assertThat(disabled.running).isFalse();           // never started: it was off before the standby hold
        assertThat(gate.heldListeners()).isEmpty();
        assertThat(role.promote("again", "platform-ops")).isFalse();

        assertThat(role.demote("failback", "platform-ops")).isTrue();
        assertThat(relay.paused()).isTrue();
        assertThat(audit.running).isFalse();
        assertThat(gate.heldListeners()).containsExactly("audit-domain-events", "approvals");
    }

    @SuppressWarnings("unchecked")
    @Test
    void promotionFailsWhenTheDatabaseStaysReadOnlyAndCanBeRetried() {
        ObjectProvider<KafkaListenerEndpointRegistry> provider = mock(ObjectProvider.class);
        CellRole role = new CellRole("c", CellRole.Role.STANDBY);
        OutboxRelay relay = new OutboxRelay(null, null, null, "t", "s", 10, Duration.ZERO, true);
        AtomicBoolean writable = new AtomicBoolean(false);
        StandbyGate gate = new StandbyGate(role, provider, relay, null, Duration.ZERO, writable::get,
                Duration.ofMillis(100), Duration.ofMillis(10));
        gate.start();
        role.promote("test", "ops");
        waitFor(() -> gate.state() == StandbyGate.State.FAILED);
        assertThat(gate.detail()).contains("read-only");
        assertThat(relay.paused()).isTrue();

        writable.set(true);
        assertThat(gate.retry()).isTrue();
        waitFor(() -> gate.state() == StandbyGate.State.ACTIVE);
        assertThat(relay.paused()).isFalse();
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 5 s");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void primaryCellStartsNormally() {
        CellRole role = new CellRole("c", CellRole.parse(null));
        assertThat(role.active()).isTrue();
        assertThat(CellRole.parse("STANDBY")).isEqualTo(CellRole.Role.STANDBY);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CellRole.parse("warm"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
