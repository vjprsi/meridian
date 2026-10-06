package io.meridian.notification.infrastructure;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.notification.application.NotificationService;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background sweep: sends scheduled notifications (quiet hours ended, future send time) and retries queued ones
 * whose backoff elapsed. Row-level security scopes every query to one tenant, so the sweep iterates over tenants
 * with API activity (from the RLS-free idempotency_key table) and runs as each of them.
 */
@Component
@ConditionalOnProperty(name = "meridian.notifications.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationJobs {
    private static final Logger log = LoggerFactory.getLogger(NotificationJobs.class);

    private final NotificationStore store;
    private final NotificationService notifications;

    public NotificationJobs(NotificationStore store, NotificationService notifications) {
        this.store = store;
        this.notifications = notifications;
    }

    @Scheduled(fixedDelayString = "${meridian.notifications.dispatch-interval-ms:5000}")
    public void dispatchDue() {
        for (UUID tenant : store.knownTenants()) {
            try {
                TenantContext.callAs(tenant, "system", () -> notifications.processDue(Instant.now()));
            } catch (Exception e) {
                log.warn("Notification sweep for tenant {} failed: {}", tenant, e.toString());
            }
        }
    }
}
