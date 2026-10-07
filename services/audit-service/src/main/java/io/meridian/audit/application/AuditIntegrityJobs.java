package io.meridian.audit.application;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import io.meridian.web.client.ServiceClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Daily audit integrity run (GAP-07), every tenant: verify the hash chain (from the first event by default, so a
 * rewritten field anywhere is caught; {@code meridian.audit.integrity.full-daily=false} verifies incrementally from the
 * last valid run instead, for very large tenants), then seal and export
 * yesterday's batch to WORM storage. Tenants are the ACTIVE tenants of tenant-service plus every tenant with recent
 * audit activity (so a tenant-service outage never skips one). Failures are logged per tenant and do not stop the
 * others; breaks are alerted through {@code platform.audit_chain.broken} / {@code meridian.audit.chain.breaks}.
 * Schedule: {@code meridian.audit.integrity.cron} (UTC, default 01:15); off with
 * {@code meridian.audit.integrity.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "meridian.audit.integrity.enabled", havingValue = "true", matchIfMissing = true)
public class AuditIntegrityJobs {
    private static final Logger log = LoggerFactory.getLogger(AuditIntegrityJobs.class);

    private final AuditIntegrityService integrity;
    private final io.meridian.audit.infrastructure.AuditEventStore store;
    private final ServiceClient services;
    private final boolean fullDaily;

    public AuditIntegrityJobs(AuditIntegrityService integrity, io.meridian.audit.infrastructure.AuditEventStore store,
                              ServiceClient services,
                              @org.springframework.beans.factory.annotation.Value("${meridian.audit.integrity.full-daily:true}")
                              boolean fullDaily) {
        this.integrity = integrity;
        this.fullDaily = fullDaily;
        this.store = store;
        this.services = services;
    }

    @Scheduled(cron = "${meridian.audit.integrity.cron:0 15 1 * * *}", zone = "UTC")
    public void daily() {
        LocalDate yesterday = Clocks.now().toLocalDate().minusDays(1);
        for (UUID tenant : tenants()) {
            runFor(tenant, yesterday);
        }
    }

    /** Verification then seal for one tenant (each step independent: a failed seal still leaves the verification). */
    public void runFor(UUID tenant, LocalDate day) {
        try {
            TenantContext.callAs(tenant, "system", () -> integrity.verifyChain("SCHEDULED", fullDaily));
        } catch (Exception e) {
            log.error("Audit chain verification failed for tenant {}: {}", tenant, e.toString());
        }
        try {
            TenantContext.callAs(tenant, "system", () -> integrity.seal(day));
        } catch (Exception e) {
            log.error("Audit seal of {} failed for tenant {}: {}", day, tenant, e.toString());
        }
    }

    Set<UUID> tenants() {
        Set<UUID> out = new LinkedHashSet<>();
        try {
            String cursor = null;
            for (int page = 0; page < 100; page++) {
                String path = "/v1/platform/tenants?status=ACTIVE&limit=200"
                        + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
                JsonNode res = services.get("tenant-service", path);
                if (res == null) {
                    break;
                }
                res.path("data").forEach(t -> {
                    if (t.hasNonNull("id")) {
                        out.add(UUID.fromString(t.get("id").asString()));
                    }
                });
                if (!res.path("has_more").asBoolean(false) || !res.hasNonNull("next_cursor")) {
                    break;
                }
                cursor = res.get("next_cursor").asString();
            }
        } catch (RuntimeException e) {
            log.warn("tenant-service unavailable for the audit integrity run ({}); using tenants with recent activity", e.toString());
        }
        out.addAll(store.recentlyActiveTenants(Clocks.now().minusDays(2)));
        return out;
    }
}
