package io.meridian.businessday.application;

import io.meridian.businessday.domain.EodPolicy;
import io.meridian.businessday.domain.JobGraph;
import io.meridian.businessday.infrastructure.JobDispatcher;
import io.meridian.businessday.infrastructure.LockStore;
import io.meridian.businessday.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Executes an EOD run: dispatches jobs to their owning services in dependency order, running independent jobs
 * and job partitions in parallel (virtual threads, bounded), retrying failed calls with exponential back-off
 * per the job's failure policy, recording every job execution with timings and errors, and finally setting
 * the run outcome and the business-day state. Jobs already SUCCEEDED (or skipped) are never dispatched again,
 * which makes restart, retry and skip resume from the point of failure. A lease lock per legal entity and
 * business date prevents two executions of the same cycle.
 */
@Component
public class EodOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(EodOrchestrator.class);
    private static final Set<String> RESETTABLE = Set.of("dependency_failed", "cycle_stopped");

    private final Rows rows;
    private final JobDefinitions definitions;
    private final JobDispatcher dispatcher;
    private final LockStore locks;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final EodSettings settings;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    public EodOrchestrator(Rows rows, JobDefinitions definitions, JobDispatcher dispatcher, LockStore locks,
                           OutboxWriter outbox, TransactionTemplate tx, JsonMapper json, EodSettings settings) {
        this.rows = rows;
        this.definitions = definitions;
        this.dispatcher = dispatcher;
        this.locks = locks;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
        this.settings = settings;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Starts execution once the current transaction commits (immediately if there is none). */
    public void launchAfterCommit(UUID runId) {
        UUID tenant = TenantContext.require();
        String principal = TenantContext.principal();
        Runnable launch = () -> executor.submit(() -> TenantContext.callAs(tenant, principal, () -> {
            execute(runId);
            return null;
        }));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    launch.run();
                }
            });
        } else {
            launch.run();
        }
    }

    public boolean isActive(UUID runId) {
        return active.contains(runId);
    }

    /** Lock key of the EOD cycle of a legal entity and business date. */
    public static String lockKey(JsonNode run, String businessDate) {
        return "eod:" + run.get("legal_entity_id").asString() + ":" + businessDate;
    }

    void execute(UUID runId) {
        if (!active.add(runId)) {
            return;
        }
        try {
            run(runId);
        } catch (RuntimeException e) {
            log.error("EOD run {} aborted", runId, e);
            finish(runId, "Orchestration error: " + e.getMessage());
        } finally {
            active.remove(runId);
        }
    }

    private record Partition(UUID id, String code, int partitionNo, String status, boolean skipped,
                             String errorCode, int attempt, JsonNode checkpoint) {
        boolean done() {
            return "SUCCEEDED".equals(status) || skipped;
        }
    }

    private void run(UUID runId) {
        JsonNode run = rows.require("eod_run", runId, false);
        JsonNode day = rows.require("business_day", UUID.fromString(run.get("business_day_id").asString()), false);
        String lockKey = lockKey(run, day.get("business_date").asString());
        String holder = settings.eodHolder(runId);
        if (locks.acquire(lockKey, holder, settings.lockLease()).isEmpty()) {
            log.warn("EOD run {} not executed: lock {} is held by another execution", runId, lockKey);
            return;
        }
        Map<UUID, JobDefinitions.Definition> defsById = definitions.byId();
        List<Partition> initial = partitions(runId, defsById);
        Map<String, JobDefinitions.Definition> defs = new LinkedHashMap<>();
        initial.forEach(p -> defsById.values().stream().filter(d -> d.code().equals(p.code())).findFirst()
                .ifPresent(d -> defs.put(d.code(), d)));
        JobGraph graph = definitions.graphOf(new ArrayList<>(defs.values()));
        Map<String, Integer> partitionCount = new LinkedHashMap<>();
        initial.forEach(p -> partitionCount.merge(p.code(), 1, Integer::sum));

        CompletionService<UUID> cs = new ExecutorCompletionService<>(executor);
        Set<UUID> inflight = new HashSet<>();
        boolean lockLost = false;
        while (true) {
            // re-acquiring as the same holder extends the lease (heartbeat)
            if (!lockLost && locks.acquire(lockKey, holder, settings.lockLease()).isEmpty()) {
                lockLost = true;
                log.warn("EOD run {} lost its lock {}; draining", runId, lockKey);
            }
            String runStatus = rows.require("eod_run", runId, false).get("status").asString();
            boolean cancelled = "CANCELLED".equals(runStatus);
            List<Partition> parts = partitions(runId, defsById);
            Map<String, List<Partition>> byCode = new LinkedHashMap<>();
            parts.forEach(p -> byCode.computeIfAbsent(p.code(), k -> new ArrayList<>()).add(p));
            Set<String> satisfied = new HashSet<>();
            Set<String> failed = new HashSet<>();
            byCode.forEach((code, ps) -> {
                if (ps.stream().allMatch(Partition::done)) {
                    satisfied.add(code);
                }
                if (ps.stream().anyMatch(p -> "FAILED".equals(p.status()))) {
                    failed.add(code);
                }
            });
            boolean stop = cancelled || lockLost
                    || failed.stream().anyMatch(c -> defs.get(c).failurePolicy().stopsCycle());
            Set<String> blocked = new HashSet<>(failed);
            for (String f : failed) {
                for (String d : graph.downstreamOf(f)) {
                    blocked.add(d);
                    cancelQueued(byCode.getOrDefault(d, List.of()), inflight, "dependency_failed",
                            "Dependency " + f + " failed");
                }
            }
            if (stop) {
                cancelQueued(parts, inflight, cancelled ? "cancelled" : "cycle_stopped",
                        cancelled ? "Run cancelled" : "Cycle stopped after a job failure");
            } else {
                for (String code : graph.ready(satisfied, blocked)) {
                    for (Partition p : byCode.getOrDefault(code, List.of())) {
                        if ("QUEUED".equals(p.status()) && !inflight.contains(p.id())
                                && inflight.size() < settings.maxConcurrency()) {
                            inflight.add(p.id());
                            JobDefinitions.Definition def = defs.get(code);
                            int count = partitionCount.get(code);
                            UUID tenant = TenantContext.require();
                            String principal = TenantContext.principal();
                            cs.submit(() -> TenantContext.callAs(tenant, principal, () -> {
                                runPartition(run, day, def, p, count);
                                return p.id();
                            }));
                        }
                    }
                }
            }
            if (inflight.isEmpty()) {
                break;
            }
            try {
                Future<UUID> done = cs.take();
                inflight.remove(done.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                // runPartition records its own failures; an exception here is unexpected
                log.error("EOD run {} partition task failed", runId, e.getCause());
                inflight.removeIf(id -> rows.require("eod_job_run", id, false).path("status").asString().matches("SUCCEEDED|FAILED|CANCELLED"));
            }
        }
        finish(runId, null);
        locks.release(lockKey, holder);
    }

    private void cancelQueued(List<Partition> parts, Set<UUID> inflight, String code, String message) {
        for (Partition p : parts) {
            if ("QUEUED".equals(p.status()) && !inflight.contains(p.id())) {
                rows.update("""
                        update eod_job_run set status = 'CANCELLED', error_code = ?, error = ?, finished_at = now()
                        where id = ? and status = 'QUEUED'""", code, message, p.id());
            }
        }
    }

    /** Executes one job partition with retries; records the outcome on its eod_job_run row. */
    private void runPartition(JsonNode run, JsonNode day, JobDefinitions.Definition def, Partition p, int partitionCount) {
        int first = Math.max(1, p.attempt());
        int maxAttempts = EodPolicy.maxAttempts(def.failurePolicy(), def.maxRetries());
        long start = System.nanoTime();
        String errorCode = null;
        String error = null;
        for (int attempt = first; attempt < first + maxAttempts; attempt++) {
            if ("CANCELLED".equals(rows.require("eod_run", UUID.fromString(run.get("id").asString()), false)
                    .get("status").asString())) {
                rows.update("""
                        update eod_job_run set status = 'CANCELLED', error_code = 'cancelled', error = 'Run cancelled',
                               finished_at = now() where id = ? and status in ('QUEUED', 'RUNNING')""", p.id());
                return;
            }
            int a = attempt;
            tx.executeWithoutResult(s -> {
                String row = rows.jdbc().sql("""
                        update eod_job_run set status = 'RUNNING', attempt = ?, started_at = coalesce(started_at, now())
                        where id = ? returning row_to_json(eod_job_run)::text""")
                        .params(a, p.id()).query(String.class).single();
                outbox.publish("operations.eod_job_run.requested", "eod_job_run", p.id(), json.readTree(row));
            });
            ObjectNode payload = payload(run, day, def, p, partitionCount, attempt);
            boolean retryable;
            try {
                JsonNode res = callWithTimeout(def, payload, "eod-" + p.id());
                rows.update("""
                        update eod_job_run set status = 'SUCCEEDED', finished_at = now(), duration_ms = ?,
                               records_processed = ?, records_failed = ?, checkpoint = coalesce(?::jsonb, checkpoint),
                               error_code = null, error = null
                        where id = ?""",
                        elapsedMs(start), longOrNull(res, "records_processed"), longOrNull(res, "records_failed"),
                        res != null && res.hasNonNull("checkpoint") ? json.writeValueAsString(res.get("checkpoint")) : null,
                        p.id());
                return;
            } catch (ApiException e) {
                errorCode = e.code();
                error = e.getMessage();
                retryable = e.status() >= 500 || e.status() == 408 || e.status() == 429 || e.status() == 409;
            } catch (TimeoutException e) {
                errorCode = "timeout";
                error = "Job did not finish within " + def.timeoutMinutes() + " minutes";
                retryable = true;
            } catch (RuntimeException e) {
                errorCode = "dispatch_error";
                error = String.valueOf(e.getMessage());
                retryable = true;
            }
            rows.update("update eod_job_run set error_code = ?, error = ? where id = ?", errorCode, truncate(error), p.id());
            if (!retryable || attempt + 1 >= first + maxAttempts) {
                break;
            }
            sleep(EodPolicy.backoff(attempt - first + 2, settings.retryBackoff(), settings.retryBackoffCap()));
        }
        String fc = errorCode;
        String fe = truncate(error);
        tx.executeWithoutResult(s -> {
            String row = rows.jdbc().sql("""
                    update eod_job_run set status = 'FAILED', finished_at = now(), duration_ms = ?, error_code = ?, error = ?
                    where id = ? returning row_to_json(eod_job_run)::text""")
                    .params(elapsedMs(start), fc, fe, p.id()).query(String.class).single();
            outbox.publish("operations.eod_job_run.failed", "eod_job_run", p.id(), json.readTree(row));
        });
    }

    private JsonNode callWithTimeout(JobDefinitions.Definition def, ObjectNode payload, String key) throws TimeoutException {
        UUID tenant = TenantContext.require();
        String principal = TenantContext.principal();
        Future<JsonNode> f = executor.submit(() -> TenantContext.callAs(tenant, principal,
                () -> dispatcher.dispatch(def.owningService(), def.endpoint(), payload, key)));
        try {
            return f.get(def.timeoutMinutes(), TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "interrupted", "Job dispatch interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new ApiException(502, "dispatch_error", String.valueOf(e.getCause()));
        } catch (TimeoutException e) {
            f.cancel(true);
            throw e;
        }
    }

    private ObjectNode payload(JsonNode run, JsonNode day, JobDefinitions.Definition def, Partition p, int count, int attempt) {
        ObjectNode n = json.createObjectNode();
        n.put("eod_run_id", run.get("id").asString());
        n.put("eod_job_run_id", p.id().toString());
        n.put("job_code", def.code());
        n.put("job_type", def.jobType());
        n.put("cycle_type", run.get("cycle_type").asString());
        n.put("legal_entity_id", run.get("legal_entity_id").asString());
        n.put("business_date", day.get("business_date").asString());
        n.put("is_month_end", day.path("is_month_end").asBoolean(false));
        n.put("is_quarter_end", day.path("is_quarter_end").asBoolean(false));
        n.put("is_year_end", day.path("is_year_end").asBoolean(false));
        n.put("partition_no", p.partitionNo());
        n.put("partition_count", count);
        if (def.partitionKey() != null) {
            n.put("partition_key", def.partitionKey());
        }
        n.put("attempt", attempt);
        if (def.parameters() != null && !def.parameters().isNull()) {
            n.set("parameters", def.parameters());
        }
        if (p.checkpoint() != null && !p.checkpoint().isNull()) {
            n.set("checkpoint", p.checkpoint());
        }
        return n;
    }

    /** Computes the run outcome, updates the run, the business-day state, publishes the event. */
    void finish(UUID runId, String abortError) {
        tx.executeWithoutResult(s -> {
            JsonNode run = rows.require("eod_run", runId, true);
            Map<UUID, JobDefinitions.Definition> defsById = definitions.byId();
            List<Partition> parts = partitions(runId, defsById);
            Map<String, List<Partition>> byCode = new LinkedHashMap<>();
            parts.forEach(p -> byCode.computeIfAbsent(p.code(), k -> new ArrayList<>()).add(p));
            List<EodPolicy.JobResult> results = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            byCode.forEach((code, ps) -> {
                boolean critical = defsById.values().stream().filter(d -> d.code().equals(code)).findFirst()
                        .map(JobDefinitions.Definition::critical).orElse(true);
                EodPolicy.JobOutcome o;
                if (ps.stream().allMatch(x -> "SUCCEEDED".equals(x.status()))) {
                    o = EodPolicy.JobOutcome.SUCCEEDED;
                } else if (ps.stream().allMatch(Partition::done)) {
                    o = EodPolicy.JobOutcome.SKIPPED;
                } else if (ps.stream().anyMatch(x -> "FAILED".equals(x.status()))) {
                    o = EodPolicy.JobOutcome.FAILED;
                    ps.stream().filter(x -> "FAILED".equals(x.status())).findFirst()
                            .ifPresent(x -> errors.add(code + ": " + x.errorCode()));
                } else {
                    o = EodPolicy.JobOutcome.NOT_RUN;
                }
                results.add(new EodPolicy.JobResult(code, critical, o));
            });
            boolean cancelled = "CANCELLED".equals(run.get("status").asString());
            String status = abortError != null && !cancelled ? "FAILED" : EodPolicy.runStatus(results, cancelled);
            long ok = results.stream().filter(r -> r.outcome() == EodPolicy.JobOutcome.SUCCEEDED
                    || r.outcome() == EodPolicy.JobOutcome.SKIPPED).count();
            long bad = results.stream().filter(r -> r.outcome() == EodPolicy.JobOutcome.FAILED).count();
            String error = abortError != null ? abortError
                    : errors.isEmpty() ? (status.equals("FAILED") ? "Critical jobs did not run" : null)
                    : "Failed jobs: " + String.join(", ", errors);
            if (cancelled) {
                error = run.path("error").asString(null);
            }
            String row = rows.jdbc().sql("""
                    update eod_run set status = ?, finished_at = coalesce(finished_at, now()), jobs_succeeded = ?,
                           jobs_failed = ?, error = ?
                    where id = ? returning row_to_json(eod_run)::text""")
                    .params(status, (int) ok, (int) bad, truncate(error), runId).query(String.class).single();
            if (!cancelled && !status.equals("SUCCEEDED") && !status.equals("PARTIALLY_SUCCEEDED")) {
                rows.update("update business_day set state = 'EOD_FAILED' where id = ? and state = 'EOD_RUNNING'",
                        UUID.fromString(run.get("business_day_id").asString()));
            }
            if (!cancelled) {
                outbox.publish(status.equals("SUCCEEDED") || status.equals("PARTIALLY_SUCCEEDED")
                        ? "operations.eod_run.completed" : "operations.eod_run.failed", "eod_run", runId, json.readTree(row));
            }
        });
    }

    private List<Partition> partitions(UUID runId, Map<UUID, JobDefinitions.Definition> defsById) {
        return rows.query("select row_to_json(j)::text from eod_job_run j where eod_run_id = ? order by partition_no, id", runId)
                .stream().map(j -> {
                    JobDefinitions.Definition d = defsById.get(UUID.fromString(j.get("eod_job_definition_id").asString()));
                    return new Partition(UUID.fromString(j.get("id").asString()), d == null ? "?" : d.code(),
                            j.path("partition_no").asInt(), j.get("status").asString(), j.hasNonNull("skipped_by"),
                            j.path("error_code").asString(null), j.path("attempt").asInt(1), j.get("checkpoint"));
                }).toList();
    }

    /** Re-queues job executions cancelled only because of an upstream failure or a stopped cycle. */
    public int resetBlocked(UUID runId) {
        return rows.update("""
                update eod_job_run set status = 'QUEUED', error_code = null, error = null, finished_at = null,
                       started_at = null, duration_ms = null
                where eod_run_id = ? and status = 'CANCELLED' and skipped_by is null and error_code = any(?)""",
                runId, RESETTABLE.toArray(String[]::new));
    }

    private static Long longOrNull(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) && n.get(f).isNumber() ?n.get(f).asLong() : null;
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 4000 ? s : s.substring(0, 4000);
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
