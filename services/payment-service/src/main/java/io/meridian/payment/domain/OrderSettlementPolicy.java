package io.meridian.payment.domain;

import io.meridian.payment.domain.OrderLifecycle.FulfilmentStatus;
import io.meridian.payment.domain.OrderLifecycle.OrderStatus;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * WE-ETM-1 (G-08): the pure decision logic of Order &amp; Pay (ETM) ambiguous-case settlement and the green / yellow /
 * red health alert, with NO DB / ledger dependency so it unit-tests cleanly (mirrors
 * {@link io.meridian.wallet.domain.VoucherPolicy}). It answers three questions from recorded facts:
 *
 * <ol>
 *   <li>given a stale-adjusted payment-gateway heartbeat, has the fulfilment timed out and are the automatic retries
 *       exhausted, should the resolver <b>retry</b> the probe, <b>wait</b>, <b>roll forward</b> (treat the payment as
 *       succeeded and complete fulfilment) or <b>roll back</b> (reverse the payment and cancel the order);</li>
 *   <li>is a recorded heartbeat still fresh, or so old it must be treated as {@code UNKNOWN};</li>
 *   <li>what GREEN / YELLOW / RED health alert the order's segregated states imply.</li>
 * </ol>
 *
 * The decision is deterministic: the same facts always yield the same action, so overlapping resolver passes that read
 * the same row decide identically and the idempotent money move posts at most once.
 */
public final class OrderSettlementPolicy {

    /** What the ambiguous-case resolver should do this pass. */
    public enum Action { RETRY, ROLLBACK, ROLL_FORWARD, WAIT }

    public enum PgHeartbeatStatus { UP, DEGRADED, DOWN, UNKNOWN }

    private OrderSettlementPolicy() {}

    /**
     * The gateway is considered <em>live</em> (a definitive answer is still expected soon, so keep probing) only while
     * its last heartbeat is fresh and UP or DEGRADED; a stale or DOWN / UNKNOWN heartbeat is not live.
     */
    public static boolean gatewayLive(PgHeartbeatStatus heartbeat) {
        return heartbeat == PgHeartbeatStatus.UP || heartbeat == PgHeartbeatStatus.DEGRADED;
    }

    /** A heartbeat older than {@code staleAfter} (or absent) carries no information and counts as {@code UNKNOWN}. */
    public static boolean heartbeatStale(OffsetDateTime heartbeatAt, OffsetDateTime now, Duration staleAfter) {
        return heartbeatAt == null || now.isAfter(heartbeatAt.plus(staleAfter));
    }

    /** The recorded heartbeat reduced to {@code UNKNOWN} once it is stale, so old liveness never drives a decision. */
    public static PgHeartbeatStatus effectiveHeartbeat(PgHeartbeatStatus recorded, OffsetDateTime heartbeatAt,
                                                       OffsetDateTime now, Duration staleAfter) {
        if (recorded == null || heartbeatStale(heartbeatAt, now, staleAfter)) {
            return PgHeartbeatStatus.UNKNOWN;
        }
        return recorded;
    }

    /**
     * Deterministic resolution of an ambiguous payment outcome. A live gateway with retries left keeps probing
     * ({@code RETRY}); a dead gateway before any deadline waits for recovery ({@code WAIT}); once the fulfilment
     * deadline has passed or the automatic retries are exhausted the case MUST resolve — roll forward when the goods
     * are already committed (fulfilment started or done), otherwise roll back. This is why forcing a fulfilment timeout
     * auto-resolves a stuck order rather than leaving it pending.
     *
     * @param heartbeat         stale-adjusted gateway heartbeat (see {@link #effectiveHeartbeat})
     * @param fulfilmentTimedOut now is at or past the fulfilment deadline
     * @param attemptsExhausted  the configured automatic retry budget is spent
     * @param fulfilmentStarted  fulfilment is IN_PROGRESS or FULFILLED (goods committed)
     */
    public static Action decide(PgHeartbeatStatus heartbeat, boolean fulfilmentTimedOut, boolean attemptsExhausted,
                                boolean fulfilmentStarted) {
        boolean mustResolve = fulfilmentTimedOut || attemptsExhausted;
        if (!mustResolve) {
            // still within the window: probe a live gateway, otherwise hold off (don't hammer a dead one)
            return gatewayLive(heartbeat) ? Action.RETRY : Action.WAIT;
        }
        return fulfilmentStarted ? Action.ROLL_FORWARD : Action.ROLLBACK;
    }

    /** True once an ambiguous case has run out of automatic road and needs a person (surfaces as a RED alert). */
    public static boolean needsManualIntervention(int settlementAttempts, int maxAttempts, boolean fulfilmentStarted) {
        // exhausted retries with nothing delivered is the hard case the resolver can only roll back; flag it red so an
        // operator can confirm before/after the reversal via the existing repair / recall flow
        return settlementAttempts >= maxAttempts && !fulfilmentStarted;
    }

    /**
     * The order's derived green / yellow / red health. RED = a failed order / fulfilment or an ambiguous case that has
     * exhausted automatic resolution; YELLOW = ambiguous and still in automatic resolution; GREEN = settled cleanly or
     * a normal in-flight / terminal-clean order.
     */
    public static OrderAlertLevel alert(OrderStatus order, FulfilmentStatus fulfilment, boolean ambiguous,
                                        boolean exhausted) {
        if (order == OrderStatus.FAILED || fulfilment == FulfilmentStatus.FAILED) {
            return OrderAlertLevel.RED;
        }
        if (ambiguous) {
            return exhausted ? OrderAlertLevel.RED : OrderAlertLevel.YELLOW;
        }
        return OrderAlertLevel.GREEN;
    }

    /** String overload for callers holding raw status names (row columns). */
    public static OrderAlertLevel alert(String order, String fulfilment, boolean ambiguous, boolean exhausted) {
        return alert(OrderStatus.valueOf(order), FulfilmentStatus.valueOf(fulfilment), ambiguous, exhausted);
    }

    /** Delay before the next resolver pass of an ambiguous order: {@code base} doubled per attempt, capped at {@code max}. */
    public static Duration backoff(int attempt, Duration base, Duration max) {
        Duration d = base.multipliedBy(1L << Math.min(Math.max(attempt, 0), 20));
        return d.compareTo(max) > 0 ? max : d;
    }

    public enum OrderAlertLevel { GREEN, YELLOW, RED }
}
