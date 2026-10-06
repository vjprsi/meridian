package io.meridian.workflow.application;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.workflow.infrastructure.WorkflowStore;
import java.util.UUID;
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

        Job(SweepService sweep, WorkflowStore store) {
            this.sweep = sweep;
            this.store = store;
        }

        @Scheduled(fixedDelayString = "${meridian.workflow.sweep-interval-ms:60000}",
                initialDelayString = "${meridian.workflow.sweep-initial-delay-ms:30000}")
        void run() {
            for (UUID tenant : store.activeTenants()) {
                try {
                    Result r = TenantContext.callAs(tenant, "system", sweep::processDue);
                    if (r.expiredRequests() + r.breachedTasks() > 0) {
                        log.info("Tenant {}: expired {} approval requests, {} task SLA breaches", tenant,
                                r.expiredRequests(), r.breachedTasks());
                    }
                } catch (Exception e) {
                    log.warn("Workflow sweep failed for tenant {}: {}", tenant, e.toString());
                }
            }
        }
    }
}
