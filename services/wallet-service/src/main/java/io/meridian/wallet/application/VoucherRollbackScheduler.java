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
 * WE-TXN-1 (G-03, MQ-FIN-4/17): rolls back expired vouchers on a schedule, per tenant. Each pass runs the idempotent
 * {@link VoucherService#expireAndRollback()} under the tenant's context, so SENDER vouchers return their funds
 * (suspense -&gt; sender) and UNCLAIMED vouchers are marked EXPIRED to age via the WE-FLOAT-1 settlement run. Opt-in
 * per deployment ({@code meridian.wallet.voucher.schedule-enabled=true}); tenants are discovered from the RLS-free
 * idempotency_key table. The same work is also callable over HTTP (POST /v1/wallet-vouchers/expire-rollback).
 */
@Component
@ConditionalOnProperty(name = "meridian.wallet.voucher.schedule-enabled", havingValue = "true")
public class VoucherRollbackScheduler {
    private static final Logger log = LoggerFactory.getLogger(VoucherRollbackScheduler.class);

    private final Rows rows;
    private final VoucherService vouchers;

    public VoucherRollbackScheduler(Rows rows, VoucherService vouchers) {
        this.rows = rows;
        this.vouchers = vouchers;
    }

    @Scheduled(fixedDelayString = "${meridian.wallet.voucher.interval-ms:3600000}", initialDelay = 120_000)
    public void rollbackExpired() {
        for (UUID tenant : rows.distinctTenants()) {
            try {
                TenantContext.callAs(tenant, "system:voucher-rollback", () -> {
                    ObjectNode out = vouchers.expireAndRollback();
                    if (out.path("rolled_back").asInt() > 0 || out.path("aged_to_unclaimed").asInt() > 0
                            || !out.path("failed_ids").isEmpty()) {
                        log.info("Tenant {}: voucher rollback - {} returned to sender, {} aged to unclaimed, {} failed",
                                tenant, out.path("rolled_back").asInt(), out.path("aged_to_unclaimed").asInt(),
                                out.path("failed_ids").size());
                    }
                    return null;
                });
            } catch (Exception e) {
                log.warn("voucher rollback failed for tenant {}: {}", tenant, e.toString());
            }
        }
    }
}
