package io.meridian.mover;

/**
 * Smooth rate limit (units per second) shared by all runs of this mover instance, so a tenant move never saturates
 * the source cell's databases while it is still serving reads (and other tenants). 0 = unlimited.
 */
public class Throttle {
    private final double perSecond;
    private long nextFreeNanos = System.nanoTime();

    public Throttle(int perSecond) {
        this.perSecond = perSecond;
    }

    /** Blocks until {@code units} may proceed. */
    public void acquire(long units) {
        if (perSecond <= 0 || units <= 0) {
            return;
        }
        long wait;
        synchronized (this) {
            long now = System.nanoTime();
            long start = Math.max(now, nextFreeNanos);
            nextFreeNanos = start + (long) (units * 1_000_000_000.0 / perSecond);
            wait = start - now;
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MoverHttp.TransientException("interrupted");
            }
        }
    }
}
