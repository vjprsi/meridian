package io.meridian.businessday.application;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Tunables of EOD orchestration and batch locking ({@code meridian.business-day.*}). */
@Component
public class EodSettings {
    private final String instanceId;
    private final Duration lockLease;
    private final Duration retryBackoff;
    private final Duration retryBackoffCap;
    private final int maxConcurrency;

    public EodSettings(@Value("${meridian.business-day.lock-lease:PT5M}") Duration lockLease,
                       @Value("${meridian.business-day.eod.retry-backoff:PT2S}") Duration retryBackoff,
                       @Value("${meridian.business-day.eod.retry-backoff-cap:PT1M}") Duration retryBackoffCap,
                       @Value("${meridian.business-day.eod.max-concurrency:16}") int maxConcurrency) {
        this.instanceId = ManagementFactory.getRuntimeMXBean().getName() + "/" + UUID.randomUUID().toString().substring(0, 8);
        this.lockLease = lockLease;
        this.retryBackoff = retryBackoff;
        this.retryBackoffCap = retryBackoffCap;
        this.maxConcurrency = Math.max(1, maxConcurrency);
    }

    public String instanceId() {
        return instanceId;
    }

    /** Lock holder of an EOD run: the run id (stable across instances, so any instance can resume it). */
    public String eodHolder(UUID runId) {
        return "eod-run:" + runId;
    }

    public Duration lockLease() {
        return lockLease;
    }

    public Duration retryBackoff() {
        return retryBackoff;
    }

    public Duration retryBackoffCap() {
        return retryBackoffCap;
    }

    public int maxConcurrency() {
        return maxConcurrency;
    }
}
