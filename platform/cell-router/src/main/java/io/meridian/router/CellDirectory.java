package io.meridian.router;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The cells the router knows: registry status and gateway URL (refreshed periodically, from routing lookups and from
 * {@code platform.cell.*} events), break-glass overrides, and the health-check verdict with hysteresis.
 */
public class CellDirectory {
    private static final Logger log = LoggerFactory.getLogger(CellDirectory.class);
    static final Set<String> OVERRIDABLE = Set.of("ACTIVE", "READ_ONLY", "OFFLINE");

    /** Health verdict of one cell: flips only after N consecutive opposite probe results. */
    static final class Health {
        private final int failureThreshold;
        private final int successThreshold;
        private boolean healthy = true;
        private int failures;
        private int successes;

        Health(int failureThreshold, int successThreshold) {
            this.failureThreshold = failureThreshold;
            this.successThreshold = successThreshold;
        }

        /** Records one probe; returns true when the verdict changed. */
        synchronized boolean record(boolean ok) {
            if (ok) {
                failures = 0;
                if (!healthy && ++successes >= successThreshold) {
                    healthy = true;
                    successes = 0;
                    return true;
                }
            } else {
                successes = 0;
                if (healthy && ++failures >= failureThreshold) {
                    healthy = false;
                    failures = 0;
                    return true;
                }
            }
            return false;
        }

        synchronized boolean healthy() {
            return healthy;
        }
    }

    /** What routing needs of a cell: effective status (override over registry), URL, and whether it may take traffic. */
    public record View(UUID id, String code, String status, String url, boolean healthy) {
        /** OFFLINE / DECOMMISSIONED (or health failure under auto-failover) means the tenant needs its standby. */
        public boolean down(boolean autoFailover) {
            return url == null || "OFFLINE".equals(status) || "DECOMMISSIONED".equals(status) || (autoFailover && !healthy);
        }
    }

    private final Map<UUID, Registry.Cell> cells = new ConcurrentHashMap<>();
    private final Map<UUID, Health> health = new ConcurrentHashMap<>();
    private final Map<String, String> overrides;
    private final RouterProperties.Health healthProps;

    public CellDirectory(RouterProperties props) {
        this.healthProps = props.health();
        Map<String, String> o = new ConcurrentHashMap<>();
        props.cellOverrides().forEach((k, v) -> {
            String status = v == null ? "" : v.trim().toUpperCase(Locale.ROOT);
            if (!OVERRIDABLE.contains(status)) {
                throw new IllegalArgumentException("meridian.router.cell-overrides." + k + ": " + v
                        + " (expected ACTIVE, READ_ONLY or OFFLINE)");
            }
            o.put(k.trim().toLowerCase(Locale.ROOT), status);
        });
        this.overrides = Map.copyOf(o);
        if (!overrides.isEmpty()) {
            log.warn("Cell overrides in force (break-glass): {}", overrides);
        }
    }

    public void put(Registry.Cell cell) {
        Registry.Cell previous = cells.put(cell.id(), cell);
        if (previous != null && !String.valueOf(previous.status()).equals(String.valueOf(cell.status()))) {
            log.info("Cell {} is now {} (was {})", cell.code(), cell.status(), previous.status());
        }
    }

    public void replaceAll(Collection<Registry.Cell> all) {
        all.forEach(this::put);
    }

    public Optional<Registry.Cell> cell(UUID id) {
        return Optional.ofNullable(id == null ? null : cells.get(id));
    }

    public Collection<Registry.Cell> all() {
        return cells.values();
    }

    public boolean known(UUID id) {
        return id != null && cells.containsKey(id);
    }

    public View view(UUID id) {
        Registry.Cell c = id == null ? null : cells.get(id);
        if (c == null) {
            return null;
        }
        return new View(c.id(), c.code(), effectiveStatus(c), c.gatewayUrl(), healthy(c.id()));
    }

    String effectiveStatus(Registry.Cell c) {
        String o = overrides.get(c.id().toString());
        if (o == null && c.code() != null) {
            o = overrides.get(c.code().toLowerCase(Locale.ROOT));
        }
        return o != null ? o : c.status();
    }

    public boolean healthy(UUID id) {
        Health h = health.get(id);
        return h == null || h.healthy();
    }

    /** Records a probe result for the cell; true when its verdict flipped. */
    public boolean recordProbe(UUID id, boolean ok) {
        boolean changed = health.computeIfAbsent(id, k -> new Health(healthProps.failureThreshold(),
                healthProps.successThreshold())).record(ok);
        if (changed) {
            Registry.Cell c = cells.get(id);
            log.warn("Cell {} health check: now {}", c == null ? id : c.code(), healthy(id) ? "HEALTHY" : "UNHEALTHY");
        }
        return changed;
    }

    public String probeUrl(Registry.Cell c) {
        if (c.healthUrl() != null && !c.healthUrl().isBlank()) {
            return c.healthUrl();
        }
        return c.gatewayUrl() == null ? null : c.gatewayUrl() + healthProps.path();
    }
}
