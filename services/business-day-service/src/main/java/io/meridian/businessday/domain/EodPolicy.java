package io.meridian.businessday.domain;

import java.time.Duration;
import java.util.Collection;

/** Retry, back-off and outcome rules of EOD processing. */
public final class EodPolicy {
    private EodPolicy() {}

    public enum FailurePolicy {
        STOP_CYCLE, CONTINUE, RETRY_THEN_STOP, RETRY_THEN_CONTINUE;

        public boolean retries() {
            return this == RETRY_THEN_STOP || this == RETRY_THEN_CONTINUE;
        }

        public boolean stopsCycle() {
            return this == STOP_CYCLE || this == RETRY_THEN_STOP;
        }

        public static FailurePolicy of(String s) {
            return s == null ? RETRY_THEN_STOP : valueOf(s);
        }
    }

    /** Total attempts for one job partition: one plus the automatic retries the policy allows. */
    public static int maxAttempts(FailurePolicy policy, int maxRetries) {
        return 1 + (policy.retries() ? Math.max(0, maxRetries) : 0);
    }

    /** Exponential back-off before attempt {@code nextAttempt} (2..n), capped. */
    public static Duration backoff(int nextAttempt, Duration base, Duration cap) {
        int exp = Math.max(0, Math.min(nextAttempt - 2, 20));
        long ms = base.toMillis() * (1L << exp);
        return Duration.ofMillis(Math.min(ms, cap.toMillis()));
    }

    /** Final state of one job (all partitions) as seen by the run. */
    public enum JobOutcome { SUCCEEDED, SKIPPED, FAILED, NOT_RUN }

    public record JobResult(String code, boolean critical, JobOutcome outcome) {}

    /**
     * Run status from its job outcomes: SUCCEEDED when every job succeeded or was skipped by an operator;
     * PARTIALLY_SUCCEEDED when only non-critical jobs failed (the roll then needs to be forced, four-eyes);
     * FAILED when a critical job failed or did not run.
     */
    public static String runStatus(Collection<JobResult> results, boolean cancelled) {
        if (cancelled) {
            return "CANCELLED";
        }
        boolean criticalProblem = results.stream().anyMatch(r -> r.critical()
                && (r.outcome() == JobOutcome.FAILED || r.outcome() == JobOutcome.NOT_RUN));
        if (criticalProblem) {
            return "FAILED";
        }
        boolean anyProblem = results.stream()
                .anyMatch(r -> r.outcome() == JobOutcome.FAILED || r.outcome() == JobOutcome.NOT_RUN);
        return anyProblem ? "PARTIALLY_SUCCEEDED" : "SUCCEEDED";
    }
}
