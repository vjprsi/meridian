package io.meridian.workflow.application;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.workflow.infrastructure.WorkflowStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Time-driven transitions: expires approval requests past their deadline and flags tasks that breached their SLA.
 * {@link #processDue()} works on the current tenant; the scheduled job runs it for every tenant with recent activity.
 */
@Service
public class SweepService {
    private static final Logger log = LoggerFactory.getLogger(SweepService.class);

    private final WorkflowStore store;
    private final ApprovalService approvals;
    private final TaskService tasks;

    public SweepService(WorkflowStore store, ApprovalService approvals, TaskService tasks) {
        this.store = store;
        this.approvals = approvals;
        this.tasks = tasks;
    }

    public record Result(int expiredRequests, int breachedTasks) {}

    /** Processes due work of the tenant bound to {@link TenantContext}. */
    public Result processDue() {
        TenantContext.require();
        int expired = 0;
        for (UUID id : store.expiredRequests()) {
            expired += approvals.expire(id).isPresent() ? 1 : 0;
        }
        int breached = 0;
        for (UUID id : store.overdueTasks()) {
            breached += tasks.breachSla(id).isPresent() ? 1 : 0;
        }
        return new Result(expired, breached);
    }

    @Service
    @ConditionalOnProperty(name = "meridian.workflow.sweeper-enabled", havingValue = "true", matchIfMissing = true)
    static class Job {
        private final SweepService sweep;
        private final WorkflowStore store;
        /** GAP-10: approval queue across tenants, refreshed every sweep (SLO_SLA.md "approval queue age"). */
        private final AtomicLong pending = new AtomicLong();
        private final AtomicLong oldestAgeSeconds = new AtomicLong();

        Job(SweepService sweep, WorkflowStore store, MeterRegistry meters) {
            this.sweep = sweep;
            this.store = store;
            Gauge.builder("meridian.workflow.approvals.pending", pending, AtomicLong::get)
                    .description("PENDING approval requests, all tenants").register(meters);
            Gauge.builder("meridian.workflow.approvals.oldest.age", oldestAgeSeconds, AtomicLong::get).baseUnit("seconds")
                    .description("Age of the oldest PENDING approval request, all tenants").register(meters);
        }

        @Scheduled(fixedDelayString = "${meridian.workflow.sweep-interval-ms:60000}",
                initialDelayString = "${meridian.workflow.sweep-initial-delay-ms:30000}")
        void run() {
            long count = 0;
            long oldest = 0;
            for (UUID tenant : store.activeTenants()) {
                try {
                    long[] q = TenantContext.callAs(tenant, "system", store::pendingApprovalStats);
                    count += q[0];
                    oldest = Math.max(oldest, q[1]);
                    Result r = TenantContext.callAs(tenant, "system", sweep::processDue);
                    if (r.expiredRequests() + r.breachedTasks() > 0) {
                        log.info("Tenant {}: expired {} approval requests, {} task SLA breaches", tenant,
                                r.expiredRequests(), r.breachedTasks());
                    }
                } catch (Exception e) {
                    log.warn("Workflow sweep failed for tenant {}: {}", tenant, e.toString());
                }
            }
            pending.set(count);
            oldestAgeSeconds.set(oldest);
        }
    }
}
