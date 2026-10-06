package io.meridian.statement.application;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.statement.config.StatementConfig.StatementProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily statement cycle (for the previous calendar day) per configured tenant; off unless enabled. */
@Component
public class StatementJobs {
    private static final Logger log = LoggerFactory.getLogger(StatementJobs.class);

    private final StatementProperties props;
    private final ScheduleService schedules;
    private final Clock clock;

    public StatementJobs(StatementProperties props, ScheduleService schedules, Clock clock) {
        this.props = props;
        this.schedules = schedules;
        this.clock = clock;
    }

    @Scheduled(cron = "${meridian.statement.batch-cron:0 0 2 * * *}", zone = "UTC")
    public void daily() {
        if (!props.batchEnabled()) {
            return;
        }
        LocalDate cycleEnd = LocalDate.now(clock).minusDays(1);
        for (UUID tenant : props.batchTenants()) {
            try {
                int n = TenantContext.callAs(tenant, "system:statements", () -> schedules.runCycle(cycleEnd).size());
                log.info("statement cycle {} for tenant {} produced {} statements", cycleEnd, tenant, n);
            } catch (Exception e) {
                log.error("statement cycle {} failed for tenant {}", cycleEnd, tenant, e);
            }
        }
    }
}
