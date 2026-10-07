package io.meridian.web.time;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.client.ServiceClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The business date ("today" for value dates, due dates, accruals) as kept by business-day-service, instead of the
 * UTC calendar date: a legal entity far from UTC, or one still running yesterday's end of day, must not book on the
 * wrong day near midnight.
 *
 * <p>Per legal entity: its current business day. Per tenant (callers without an entity): the current business day
 * when all the tenant's open entities agree. Answers are cached briefly ({@code meridian.business-date.cache-seconds},
 * default 30). When business-day-service has no answer (no business day opened yet, not reachable, or disabled with
 * {@code meridian.business-date.source=clock}) the caller's fallback is used, typically its own clock, so tests that
 * drive dates with a fixed or mutable clock keep working.
 */
public class BusinessDates {
    private static final Logger log = LoggerFactory.getLogger(BusinessDates.class);
    static final String SERVICE = "business-day-service";

    private record Cached(LocalDate date, Instant expires) {}

    private final ServiceClient client;
    private final String application;
    private final boolean enabled;
    private final Duration ttl;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public BusinessDates(ServiceClient client, String application, boolean enabled, Duration ttl) {
        this.client = client;
        this.application = application;
        this.enabled = enabled;
        this.ttl = ttl;
    }

    /** The tenant's business date, else {@code fallback}. */
    public LocalDate today(Supplier<LocalDate> fallback) {
        return resolve(null, fallback);
    }

    /** The legal entity's business date, else {@code fallback}. */
    public LocalDate today(UUID legalEntityId, Supplier<LocalDate> fallback) {
        return resolve(legalEntityId, fallback);
    }

    /** The tenant's business date, else the UTC calendar date. */
    public LocalDate today() {
        return today(() -> LocalDate.now(ZoneOffset.UTC));
    }

    private LocalDate resolve(UUID legalEntityId, Supplier<LocalDate> fallback) {
        UUID tenant = TenantContext.current().orElse(null);
        if (!enabled || tenant == null || SERVICE.equals(application)) {
            return fallback.get();
        }
        String key = tenant + ":" + legalEntityId;
        Cached c = cache.get(key);
        if (c != null && Instant.now().isBefore(c.expires())) {
            return c.date() != null ? c.date() : fallback.get();
        }
        LocalDate date = null;
        try {
            date = legalEntityId != null ? fromCurrent(legalEntityId) : fromTenant();
        } catch (RuntimeException e) {
            log.debug("business date unavailable ({}), using fallback: {}", key, e.getMessage());
        }
        cache.put(key, new Cached(date, Instant.now().plus(ttl)));
        return date != null ? date : fallback.get();
    }

    private LocalDate fromCurrent(UUID legalEntityId) {
        JsonNode day = client.get(SERVICE, "/v1/operations/business-days/current?legal_entity_id=" + legalEntityId);
        return day != null && day.hasNonNull("business_date") ? LocalDate.parse(day.get("business_date").asString()) : null;
    }

    private LocalDate fromTenant() {
        JsonNode page = client.get(SERVICE, "/v1/operations/business-days?is_current=true&limit=50");
        if (page == null) {
            return null;
        }
        Set<LocalDate> dates = new HashSet<>();
        for (JsonNode d : page.path("data")) {
            if (d.hasNonNull("business_date")) {
                dates.add(LocalDate.parse(d.get("business_date").asString()));
            }
        }
        return dates.size() == 1 ? dates.iterator().next() : null; // none, or entities disagree: caller decides
    }

    /** Drops cached dates (e.g. after a day roll this service observed). */
    public void invalidate() {
        cache.clear();
    }
}
