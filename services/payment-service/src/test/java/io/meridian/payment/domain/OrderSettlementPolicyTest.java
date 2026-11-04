package io.meridian.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.payment.domain.OrderLifecycle.FulfilmentStatus;
import io.meridian.payment.domain.OrderLifecycle.OrderStatus;
import io.meridian.payment.domain.OrderSettlementPolicy.Action;
import io.meridian.payment.domain.OrderSettlementPolicy.OrderAlertLevel;
import io.meridian.payment.domain.OrderSettlementPolicy.PgHeartbeatStatus;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * WE-ETM-1 (G-08) pure rules: ambiguous-case classification from the PG heartbeat and deadlines, heartbeat staleness,
 * green/yellow/red alert derivation, backoff, and the order / fulfilment state-machine guards.
 */
class OrderSettlementPolicyTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);

    // ------------------------------------------------------------------ ambiguous-case decision

    @Test
    void liveGatewayWithRetriesLeftKeepsProbing() {
        // gateway alive, nothing timed out, retries remain -> RETRY (a definitive answer is still expected)
        assertThat(OrderSettlementPolicy.decide(PgHeartbeatStatus.UP, false, false, false)).isEqualTo(Action.RETRY);
        assertThat(OrderSettlementPolicy.decide(PgHeartbeatStatus.DEGRADED, false, false, true)).isEqualTo(Action.RETRY);
    }

    @Test
    void deadGatewayBeforeDeadlineWaits() {
        // gateway down/unknown, no timeout, retries left -> WAIT rather than hammer a dead gateway
        assertThat(OrderSettlementPolicy.decide(PgHeartbeatStatus.DOWN, false, false, false)).isEqualTo(Action.WAIT);
        assertThat(OrderSettlementPolicy.decide(PgHeartbeatStatus.UNKNOWN, false, false, true)).isEqualTo(Action.WAIT);
    }

    @Test
    void fulfilmentTimeoutAutoResolvesRatherThanLeavingItStuck() {
        // the acceptance behaviour: a fulfilment timeout forces resolution either way
        // goods already committed -> roll forward (treat as succeeded, complete fulfilment)
        assertThat(OrderSettlementPolicy.decide(PgHeartbeatStatus.DOWN, true, false, true)).isEqualTo(Action.ROLL_FORWARD);
        // nothing delivered -> roll back (reverse the payment, cancel the order)
        assertThat(OrderSettlementPolicy.decide(PgHeartbeatStatus.DOWN, true, false, false)).isEqualTo(Action.ROLLBACK);
        // even a live gateway resolves once the fulfilment deadline has passed
        assertThat(OrderSettlementPolicy.decide(PgHeartbeatStatus.UP, true, false, false)).isEqualTo(Action.ROLLBACK);
    }

    @Test
    void exhaustedRetriesResolveDeterministically() {
        // out of automatic road: roll forward when started, otherwise roll back - regardless of liveness
        assertThat(OrderSettlementPolicy.decide(PgHeartbeatStatus.UP, false, true, true)).isEqualTo(Action.ROLL_FORWARD);
        assertThat(OrderSettlementPolicy.decide(PgHeartbeatStatus.UP, false, true, false)).isEqualTo(Action.ROLLBACK);
        assertThat(OrderSettlementPolicy.decide(PgHeartbeatStatus.DOWN, false, true, false)).isEqualTo(Action.ROLLBACK);
    }

    // ------------------------------------------------------------------ heartbeat staleness

    @Test
    void staleHeartbeatIsTreatedAsUnknown() {
        Duration staleAfter = Duration.ofMinutes(5);
        assertThat(OrderSettlementPolicy.heartbeatStale(NOW.minusMinutes(6), NOW, staleAfter)).isTrue();
        assertThat(OrderSettlementPolicy.heartbeatStale(NOW.minusMinutes(4), NOW, staleAfter)).isFalse();
        assertThat(OrderSettlementPolicy.heartbeatStale(null, NOW, staleAfter)).isTrue();

        // a fresh UP stays UP; a stale UP collapses to UNKNOWN; a null status is UNKNOWN
        assertThat(OrderSettlementPolicy.effectiveHeartbeat(PgHeartbeatStatus.UP, NOW.minusMinutes(1), NOW, staleAfter))
                .isEqualTo(PgHeartbeatStatus.UP);
        assertThat(OrderSettlementPolicy.effectiveHeartbeat(PgHeartbeatStatus.UP, NOW.minusMinutes(10), NOW, staleAfter))
                .isEqualTo(PgHeartbeatStatus.UNKNOWN);
        assertThat(OrderSettlementPolicy.effectiveHeartbeat(null, NOW, NOW, staleAfter))
                .isEqualTo(PgHeartbeatStatus.UNKNOWN);
    }

    @Test
    void staleUpHeartbeatNoLongerCountsAsLiveSoAResolverWaits() {
        Duration staleAfter = Duration.ofMinutes(5);
        PgHeartbeatStatus effective = OrderSettlementPolicy.effectiveHeartbeat(PgHeartbeatStatus.UP,
                NOW.minusMinutes(30), NOW, staleAfter);
        assertThat(OrderSettlementPolicy.gatewayLive(effective)).isFalse();
        assertThat(OrderSettlementPolicy.decide(effective, false, false, false)).isEqualTo(Action.WAIT);
    }

    // ------------------------------------------------------------------ alert derivation

    @Test
    void alertIsGreenWhenSettledCleanlyOrNormalInFlight() {
        assertThat(OrderSettlementPolicy.alert(OrderStatus.COMPLETED, FulfilmentStatus.FULFILLED, false, false))
                .isEqualTo(OrderAlertLevel.GREEN);
        // paid-but-not-fulfilled is a normal segregated state, not an alert
        assertThat(OrderSettlementPolicy.alert(OrderStatus.PAID, FulfilmentStatus.PENDING, false, false))
                .isEqualTo(OrderAlertLevel.GREEN);
        assertThat(OrderSettlementPolicy.alert(OrderStatus.CANCELLED, FulfilmentStatus.CANCELLED, false, false))
                .isEqualTo(OrderAlertLevel.GREEN);
    }

    @Test
    void alertIsYellowWhileAmbiguousAndRedWhenExhaustedOrFailed() {
        assertThat(OrderSettlementPolicy.alert(OrderStatus.PAID, FulfilmentStatus.PENDING, true, false))
                .isEqualTo(OrderAlertLevel.YELLOW);
        assertThat(OrderSettlementPolicy.alert(OrderStatus.PAID, FulfilmentStatus.PENDING, true, true))
                .isEqualTo(OrderAlertLevel.RED);
        assertThat(OrderSettlementPolicy.alert(OrderStatus.FAILED, FulfilmentStatus.PENDING, false, false))
                .isEqualTo(OrderAlertLevel.RED);
        assertThat(OrderSettlementPolicy.alert(OrderStatus.PAID, FulfilmentStatus.FAILED, false, false))
                .isEqualTo(OrderAlertLevel.RED);
        // string overload agrees
        assertThat(OrderSettlementPolicy.alert("PAID", "PENDING", true, false)).isEqualTo(OrderAlertLevel.YELLOW);
    }

    @Test
    void manualInterventionWhenRetriesSpentWithNothingDelivered() {
        assertThat(OrderSettlementPolicy.needsManualIntervention(5, 5, false)).isTrue();
        assertThat(OrderSettlementPolicy.needsManualIntervention(5, 5, true)).isFalse(); // roll-forward still possible
        assertThat(OrderSettlementPolicy.needsManualIntervention(3, 5, false)).isFalse();
    }

    // ------------------------------------------------------------------ backoff

    @Test
    void backoffDoublesAndIsCapped() {
        Duration base = Duration.ofMinutes(2);
        Duration max = Duration.ofHours(1);
        assertThat(OrderSettlementPolicy.backoff(0, base, max)).isEqualTo(Duration.ofMinutes(2));
        assertThat(OrderSettlementPolicy.backoff(1, base, max)).isEqualTo(Duration.ofMinutes(4));
        assertThat(OrderSettlementPolicy.backoff(3, base, max)).isEqualTo(Duration.ofMinutes(16));
        assertThat(OrderSettlementPolicy.backoff(20, base, max)).isEqualTo(max); // capped
    }

    // ------------------------------------------------------------------ lifecycle guards

    @Test
    void orderLifecycleAllowsTheHappyPathAndGuardsTerminals() {
        assertThat(OrderLifecycle.orderAllowed("CREATED", "AWAITING_PAYMENT")).isTrue();
        assertThat(OrderLifecycle.orderAllowed("AWAITING_PAYMENT", "PAID")).isTrue();
        assertThat(OrderLifecycle.orderAllowed("PAID", "COMPLETED")).isTrue();
        assertThat(OrderLifecycle.orderAllowed("PAID", "CANCELLED")).isTrue();
        // cannot skip back or leave a terminal state
        assertThat(OrderLifecycle.orderAllowed("COMPLETED", "PAID")).isFalse();
        assertThat(OrderLifecycle.orderAllowed("CANCELLED", "PAID")).isFalse();
        assertThat(OrderLifecycle.orderFinal("COMPLETED")).isTrue();
        assertThat(OrderLifecycle.orderFinal("PAID")).isFalse();
    }

    @Test
    void fulfilmentLifecycleAndStartedFlag() {
        assertThat(OrderLifecycle.fulfilmentAllowed("PENDING", "FULFILLED")).isTrue();
        assertThat(OrderLifecycle.fulfilmentAllowed("IN_PROGRESS", "FAILED")).isTrue();
        assertThat(OrderLifecycle.fulfilmentAllowed("FULFILLED", "IN_PROGRESS")).isFalse();
        assertThat(OrderLifecycle.fulfilmentStarted("PENDING")).isFalse();
        assertThat(OrderLifecycle.fulfilmentStarted("IN_PROGRESS")).isTrue();
        assertThat(OrderLifecycle.fulfilmentStarted("FULFILLED")).isTrue();
    }

    @Test
    void orderEventsFireOnMeaningfulStatusesOnly() {
        assertThat(OrderLifecycle.orderEvent("CREATED")).contains("payments.payment_order.created");
        assertThat(OrderLifecycle.orderEvent("PAID")).contains("payments.payment_order.paid");
        assertThat(OrderLifecycle.orderEvent("COMPLETED")).contains("payments.payment_order.completed");
        assertThat(OrderLifecycle.orderEvent("CANCELLED")).contains("payments.payment_order.cancelled");
        assertThat(OrderLifecycle.orderEvent("AWAITING_PAYMENT")).isEmpty();
        assertThat(OrderLifecycle.orderEvent("FULFILLING")).isEmpty();
    }
}
