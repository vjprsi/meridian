package io.meridian.mover;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs moves on virtual threads, at most one worker per move in this instance (the database lease keeps other
 * instances out), and resumes every due run each {@code meridian.mover.resume-interval}.
 */
public class MoveScheduler implements AutoCloseable {
    private final TenantMover mover;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public MoveScheduler(TenantMover mover) {
        this.mover = mover;
    }

    /** Starts (or continues) a run in the background unless this instance is already working on it. */
    public void submit(UUID moveId) {
        if (inFlight.add(moveId)) {
            executor.submit(() -> {
                try {
                    mover.runIfDue(moveId);
                } finally {
                    inFlight.remove(moveId);
                }
            });
        }
    }

    @Scheduled(fixedDelayString = "${meridian.mover.resume-interval:PT30S}", initialDelayString = "PT5S")
    public void resume() {
        try {
            mover.dueRuns().forEach(this::submit);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(MoveScheduler.class).warn("Cannot list due moves: {}", e.toString());
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
