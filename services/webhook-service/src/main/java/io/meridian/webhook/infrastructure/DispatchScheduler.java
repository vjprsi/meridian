package io.meridian.webhook.infrastructure;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.webhook.application.DeliveryService;
import io.meridian.webhook.application.ReplayService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives replay jobs and due deliveries for every tenant. Business tables are protected by row-level security, so
 * tenants are discovered from the (RLS-free) idempotency_key table: any tenant that configured endpoints did so
 * with idempotent POSTs.
 */
@Component
@ConditionalOnProperty(name = "meridian.webhooks.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class DispatchScheduler {
    private static final Logger log = LoggerFactory.getLogger(DispatchScheduler.class);

    private final WebhookStore store;
    private final DeliveryService deliveries;
    private final ReplayService replays;

    public DispatchScheduler(WebhookStore store, DeliveryService deliveries, ReplayService replays) {
        this.store = store;
        this.deliveries = deliveries;
        this.replays = replays;
    }

    @Scheduled(fixedDelayString = "${meridian.webhooks.dispatch-interval-ms:1000}")
    public void tick() {
        for (UUID tenant : store.tenantsWithActivity()) {
            try {
                TenantContext.callAs(tenant, "webhook-dispatcher", () -> {
                    replays.processQueued();
                    return deliveries.processDue();
                });
            } catch (Exception e) {
                log.warn("Webhook dispatch for tenant {} failed: {}", tenant, e.toString());
            }
        }
    }
}
