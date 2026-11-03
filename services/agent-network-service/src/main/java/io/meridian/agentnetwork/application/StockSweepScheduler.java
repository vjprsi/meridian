package io.meridian.agentnetwork.application;

import io.meridian.agentnetwork.infrastructure.Rows;
import io.meridian.common.tenancy.TenantContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/**
 * WE-FLOAT-1 (MQ-STK-6): builds auto-deallocation sweep <em>plans</em> on a schedule, per tenant and currency. It only
 * creates PENDING_APPROVAL runs; a checker still approves before any money moves, so money never moves unattended.
 * Opt-in per deployment ({@code meridian.agent-network.sweep.schedule-enabled=true}); tenants are discovered from the
 * RLS-free idempotency_key table and each pass runs under that tenant's context.
 */
@Component
@ConditionalOnProperty(name = "meridian.agent-network.sweep.schedule-enabled", havingValue = "true")
public class StockSweepScheduler {
    private static final Logger log = LoggerFactory.getLogger(StockSweepScheduler.class);

    private final Rows rows;
    private final StockSweepService sweeps;

    public StockSweepScheduler(Rows rows, StockSweepService sweeps) {
        this.rows = rows;
        this.sweeps = sweeps;
    }

    @Scheduled(fixedDelayString = "${meridian.agent-network.sweep.interval-ms:86400000}", initialDelay = 120_000)
    public void planSweeps() {
        for (UUID tenant : rows.distinctTenants()) {
            try {
                TenantContext.callAs(tenant, "system", () -> {
                    for (String currency : rows.floatCurrencies()) {
                        ObjectNode body = rows.object();
                        body.put("currency", currency);
                        ObjectNode run = sweeps.plan(body, true);
                        if (run != null) {
                            log.info("Tenant {}: planned auto-deallocation sweep {} ({} wallets, {} {})", tenant,
                                    run.path("id").asString(), run.path("candidate_count").asInt(),
                                    run.path("total_amount").asString(), currency);
                        }
                    }
                    return null;
                });
            } catch (Exception e) {
                log.warn("Auto-deallocation planning failed for tenant {}: {}", tenant, e.toString());
            }
        }
    }
}
