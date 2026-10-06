package io.meridian.businessday.application;

import io.meridian.businessday.domain.EodTrigger;
import io.meridian.common.tenancy.TenantContext;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Settings of the cross-tenant EOD trigger ({@code meridian.business-day.auto-eod.*}).
 * <ul>
 *   <li>{@code enabled} (false): run the platform scheduler; the operator endpoint works regardless;</li>
 *   <li>{@code interval} (PT5M) / {@code initial-delay} (PT1M): scheduler tick;</li>
 *   <li>{@code default-enabled} (true): tenants without a policy get automatic EOD at the default cut-off;</li>
 *   <li>{@code default-cut-off} (22:00) / {@code default-time-zone} (UTC): platform default policy (the zone is
 *       used only when neither the legal entity nor the tenant has one);</li>
 *   <li>{@code platform-tenant-id} (nil UUID): tenant under which platform-level sweep events are published and
 *       tenant-service is queried;</li>
 *   <li>{@code tenant-concurrency} (8) / {@code tenant-timeout} (PT2M): tenants evaluated in parallel, and the
 *       time after which one tenant is abandoned (marked failed) so it cannot hold up the others.</li>
 * </ul>
 */
@Component
public class AutoEodSettings {
    public static final String SCHEDULER = "SCHEDULER";

    private final Duration interval;
    private final boolean defaultEnabled;
    private final LocalTime defaultCutOff;
    private final ZoneId defaultZone;
    private final UUID platformTenant;
    private final int tenantConcurrency;
    private final Duration tenantTimeout;

    public AutoEodSettings(@Value("${meridian.business-day.auto-eod.interval:PT5M}") Duration interval,
                           @Value("${meridian.business-day.auto-eod.default-enabled:true}") boolean defaultEnabled,
                           @Value("${meridian.business-day.auto-eod.default-cut-off:22:00}") String defaultCutOff,
                           @Value("${meridian.business-day.auto-eod.default-time-zone:UTC}") String defaultZone,
                           @Value("${meridian.business-day.auto-eod.platform-tenant-id:}") String platformTenant,
                           @Value("${meridian.business-day.auto-eod.tenant-concurrency:8}") int tenantConcurrency,
                           @Value("${meridian.business-day.auto-eod.tenant-timeout:PT2M}") Duration tenantTimeout) {
        this.interval = interval.isNegative() || interval.isZero() ? Duration.ofMinutes(5) : interval;
        this.defaultEnabled = defaultEnabled;
        this.defaultCutOff = EodTrigger.parseCutOff(defaultCutOff);
        this.defaultZone = EodTrigger.parseZone(defaultZone);
        this.platformTenant = platformTenant == null || platformTenant.isBlank() ? TenantContext.NO_TENANT
                : UUID.fromString(platformTenant.trim());
        this.tenantConcurrency = Math.max(1, tenantConcurrency);
        this.tenantTimeout = tenantTimeout;
    }

    public Duration interval() {
        return interval;
    }

    public boolean defaultEnabled() {
        return defaultEnabled;
    }

    public LocalTime defaultCutOff() {
        return defaultCutOff;
    }

    public ZoneId defaultZone() {
        return defaultZone;
    }

    public UUID platformTenant() {
        return platformTenant;
    }

    public int tenantConcurrency() {
        return tenantConcurrency;
    }

    public Duration tenantTimeout() {
        return tenantTimeout;
    }
}
