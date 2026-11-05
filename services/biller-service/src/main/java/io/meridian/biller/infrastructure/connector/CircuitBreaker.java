package io.meridian.biller.infrastructure.connector;

/**
 * Hand-rolled, in-memory circuit breaker for one provider. States: CLOSED normally; after {@code threshold}
 * consecutive failures it trips OPEN and short-circuits calls for {@code cooldownMs}; the first call after the
 * cooldown is let through as a HALF_OPEN trial — its success closes the breaker, its failure re-opens it.
 *
 * <p>Short-circuiting surfaces as {@link io.meridian.biller.application.BillerGateway.Outcome#TIMEOUT} to the caller
 * so a provider outage leaves the payer's funds reserved (to be retried) rather than wrongly rejecting the payment.
 */
public final class CircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    private final int threshold;
    private final long cooldownMs;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openedAt;

    public CircuitBreaker(int threshold, long cooldownMs) {
        this.threshold = Math.max(1, threshold);
        this.cooldownMs = Math.max(0, cooldownMs);
    }

    /** True when the call must be short-circuited; moves OPEN -&gt; HALF_OPEN once the cooldown elapses (trial allowed). */
    public synchronized boolean shouldShortCircuit() {
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAt >= cooldownMs) {
                state = State.HALF_OPEN;
                return false;
            }
            return true;
        }
        return false;
    }

    public synchronized void recordSuccess() {
        state = State.CLOSED;
        consecutiveFailures = 0;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= threshold) {
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
        }
    }

    synchronized State state() {
        return state;
    }
}
