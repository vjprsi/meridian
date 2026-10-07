package io.meridian.web.idempotency;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;

/**
 * SEC-34: periodically deletes completed idempotency keys past their retention. Deliberately not a
 * {@code ScheduledExecutorService} bean (Spring's {@code @Scheduled} support would pick it up as its scheduler).
 */
public final class IdempotencyJanitor implements AutoCloseable {
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "idempotency-janitor");
        t.setDaemon(true);
        return t;
    });

    public IdempotencyJanitor(Idempotency idempotency, Duration every) {
        if (every != null && !every.isZero() && !every.isNegative()) {
            exec.scheduleWithFixedDelay(() -> {
                try {
                    idempotency.purgeExpired();
                } catch (RuntimeException e) {
                    LoggerFactory.getLogger(IdempotencyJanitor.class).warn("idempotency purge failed: {}", e.toString());
                }
            }, every.toSeconds(), every.toSeconds(), TimeUnit.SECONDS);
        }
    }

    @Override
    public void close() {
        exec.shutdownNow();
    }
}
