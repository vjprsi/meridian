package io.meridian.wallet.application;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.wallet.infrastructure.Rows;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/**
 * WE-TXN-2 (G-07): auto-rolls-back cash-out authorisations that expire unredeemed on a schedule, per tenant - chiefly
 * cardless ATM OTPs whose hold must return to the customer when the dispense never happens. Each pass runs the
 * idempotent {@link CashoutAuthorisationService#expireAndRollback()} under the tenant's context (suspense -&gt;
 * customer). Opt-in per deployment ({@code meridian.wallet.cashout-auth.schedule-enabled=true}); tenants are discovered
 * from the RLS-free idempotency_key table. The same work is also callable over HTTP
 * (POST /v1/wallet-cashout-authorisations/expire-rollback).
 */
@Component
@ConditionalOnProperty(name = "meridian.wallet.cashout-auth.schedule-enabled", havingValue = "true")
public class CashoutRollbackScheduler {
    private static final Logger log = LoggerFactory.getLogger(CashoutRollbackScheduler.class);

    private final Rows rows;
    private final CashoutAuthorisationService authorisations;

    public CashoutRollbackScheduler(Rows rows, CashoutAuthorisationService authorisations) {
        this.rows = rows;
        this.authorisations = authorisations;
    }

    @Scheduled(fixedDelayString = "${meridian.wallet.cashout-auth.interval-ms:300000}", initialDelay = 120_000)
    public void rollbackExpired() {
        for (UUID tenant : rows.distinctTenants()) {
            try {
                TenantContext.callAs(tenant, "system:cashout-rollback", () -> {
                    ObjectNode out = authorisations.expireAndRollback();
                    if (out.path("rolled_back").asInt() > 0 || !out.path("failed_ids").isEmpty()) {
                        log.info("Tenant {}: cash-out authorisation rollback - {} returned to customer, {} failed",
                                tenant, out.path("rolled_back").asInt(), out.path("failed_ids").size());
                    }
                    return null;
                });
            } catch (Exception e) {
                log.warn("cash-out authorisation rollback failed for tenant {}: {}", tenant, e.toString());
            }
        }
    }
}
