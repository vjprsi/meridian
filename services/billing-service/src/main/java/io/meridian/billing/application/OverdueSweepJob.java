package io.meridian.billing.application;

import io.meridian.billing.infrastructure.BillingStore;
import io.meridian.common.tenancy.TenantContext;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically flags invoices past their due date. Row-level security scopes every query to one tenant, so the
 * job discovers tenants from the (RLS-free) idempotency_key table: every tenant that issued invoices has used it.
 */
@Component
@ConditionalOnProperty(name = "meridian.billing.overdue-sweep-enabled", havingValue = "true", matchIfMissing = true)
public class OverdueSweepJob {
    private static final Logger log = LoggerFactory.getLogger(OverdueSweepJob.class);

    private final InvoiceService invoices;
    private final BillingStore store;
    private final io.meridian.web.time.BusinessDates businessDates;

    public OverdueSweepJob(InvoiceService invoices, BillingStore store,
                           io.meridian.web.time.BusinessDates businessDates) {
        this.businessDates = businessDates;
        this.invoices = invoices;
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${meridian.billing.overdue-sweep-interval-ms:3600000}", initialDelay = 60_000)
    public void sweep() {
        for (UUID tenant : store.tenantsWithActivity()) {
            try {
                int n = TenantContext.callAs(tenant, "system", () -> invoices.markOverdue(
                        businessDates.today(() -> LocalDate.now(ZoneOffset.UTC))));
                if (n > 0) {
                    log.info("Tenant {}: {} subscription(s) with newly overdue invoices", tenant, n);
                }
            } catch (Exception e) {
                log.warn("Overdue sweep failed for tenant {}: {}", tenant, e.toString());
            }
        }
    }
}
