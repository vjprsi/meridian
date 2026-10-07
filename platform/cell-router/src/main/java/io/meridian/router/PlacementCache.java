package io.meridian.router;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tenant placement cache: one registry lookup per tenant and TTL (concurrent misses share one in-flight lookup),
 * dropped at once by an invalidation event; unknown tenants are remembered briefly (negative TTL). While the
 * registry is unreachable an expired entry keeps serving for up to {@code max-stale} (stale-if-error), so a control
 * plane outage does not take routing down with it.
 */
public class PlacementCache {
    private static final Logger log = LoggerFactory.getLogger(PlacementCache.class);

    record Entry(Optional<Registry.TenantRouting> routing, Instant fetched, Instant expires) {}

    private final ConcurrentHashMap<UUID, CompletableFuture<Entry>> entries = new ConcurrentHashMap<>();
    private final RegistryClient registry;
    private final CellDirectory cells;
    private final RouterProperties props;
    private final Clock clock;
    private final Counter hits;
    private final Counter misses;
    private final Counter stale;
    private final Counter invalidations;

    public PlacementCache(RegistryClient registry, CellDirectory cells, RouterProperties props, MeterRegistry meters,
                          Clock clock) {
        this.registry = registry;
        this.cells = cells;
        this.props = props;
        this.clock = clock;
        this.hits = meters.counter("meridian.router.placement.lookups", "result", "hit");
        this.misses = meters.counter("meridian.router.placement.lookups", "result", "miss");
        this.stale = meters.counter("meridian.router.placement.lookups", "result", "stale");
        this.invalidations = meters.counter("meridian.router.placement.invalidations");
    }

    /** The tenant's routing (empty: unknown tenant). Throws {@link Registry.UnavailableException} without a usable entry. */
    public Optional<Registry.TenantRouting> get(UUID tenant) {
        Instant now = clock.instant();
        CompletableFuture<Entry> current = entries.get(tenant);
        Entry cached = completed(current);
        if (cached != null && now.isBefore(cached.expires())) {
            hits.increment();
            return cached.routing();
        }
        misses.increment();
        CompletableFuture<Entry> mine = new CompletableFuture<>();
        CompletableFuture<Entry> winner = current == null || !entries.replace(tenant, current, mine)
                ? entries.putIfAbsent(tenant, mine) : null;
        if (winner != null && winner != mine) {
            Entry other = join(winner);         // someone else is loading (or just loaded) it
            if (other != null) {
                return other.routing();
            }
        }
        try {
            Optional<Registry.TenantRouting> loaded = registry.routing(tenant, cells::put);
            Instant at = clock.instant();
            Entry e = new Entry(loaded, at, at.plus(loaded.isPresent() ? props.cacheTtl() : props.negativeTtl()));
            mine.complete(e);
            return loaded;
        } catch (RuntimeException ex) {
            if (cached != null && cached.routing().isPresent()
                    && now.isBefore(cached.fetched().plus(props.maxStale()))) {
                stale.increment();
                log.warn("Tenant registry unavailable ({}); serving the placement of {} cached at {}", ex.getMessage(),
                        tenant, cached.fetched());
                // retry the registry after the negative TTL, not on every request (connect timeouts would pile up)
                mine.complete(new Entry(cached.routing(), cached.fetched(), now.plus(props.negativeTtl())));
                return cached.routing();
            }
            entries.remove(tenant, mine);
            mine.completeExceptionally(ex);
            throw ex instanceof Registry.UnavailableException u ? u : new Registry.UnavailableException(ex.getMessage());
        }
    }

    /** Drops the tenant's entry (placement, standby, traffic mode or lifecycle changed). */
    public void invalidate(UUID tenant) {
        if (entries.remove(tenant) != null) {
            invalidations.increment();
        }
    }

    /** Drops every entry (e.g. after a gap in the event stream). */
    public void clear() {
        entries.clear();
        invalidations.increment();
    }

    public int size() {
        return entries.size();
    }

    private static Entry completed(CompletableFuture<Entry> f) {
        return f != null && f.isDone() && !f.isCompletedExceptionally() ? f.getNow(null) : null;
    }

    private static Entry join(CompletableFuture<Entry> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }
}
