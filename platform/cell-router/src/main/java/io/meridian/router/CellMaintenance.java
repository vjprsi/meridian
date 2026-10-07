package io.meridian.router;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the cell directory current: a full refresh from the registry every {@code cells-refresh} (statuses, gateway
 * URLs; events make OFFLINE take effect sooner) and active health checks of each cell gateway every
 * {@code health.interval}, with hysteresis (see {@link CellDirectory.Health}). Exposes a per-cell health gauge.
 */
@Component
public class CellMaintenance {
    private static final Logger log = LoggerFactory.getLogger(CellMaintenance.class);

    private final RegistryClient registry;
    private final CellDirectory cells;
    private final RouterProperties props;
    private final MeterRegistry meters;
    private final Set<UUID> gauged = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean loaded = new AtomicBoolean();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1).build();

    public CellMaintenance(RegistryClient registry, CellDirectory cells, RouterProperties props, MeterRegistry meters) {
        this.registry = registry;
        this.cells = cells;
        this.props = props;
        this.meters = meters;
    }

    /** True once the cell directory was loaded from the registry at least once (readiness). */
    public boolean loaded() {
        return loaded.get();
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${meridian.router.cells-refresh:15s}")
    public void refresh() {
        try {
            cells.replaceAll(registry.cells());
            loaded.set(true);
        } catch (Registry.UnavailableException e) {
            log.warn("Cell directory refresh failed (keeping the last known cells): {}", e.getMessage());
        }
        cells.all().forEach(this::gauge);
    }

    @Scheduled(initialDelay = 1000, fixedDelayString = "${meridian.router.health.interval:5s}")
    public void probe() {
        if (!props.health().enabled()) {
            return;
        }
        for (Registry.Cell c : cells.all()) {
            String url = cells.probeUrl(c);
            if (url == null || "DECOMMISSIONED".equals(c.status())) {
                continue;
            }
            cells.recordProbe(c.id(), check(url));
        }
    }

    boolean check(String url) {
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(props.health().probeTimeout())
                    .GET().build(), HttpResponse.BodyHandlers.discarding());
            return r.statusCode() >= 200 && r.statusCode() < 300;
        } catch (java.io.IOException | IllegalArgumentException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void gauge(Registry.Cell c) {
        if (gauged.add(c.id())) {
            Gauge.builder("meridian.router.cell.healthy", cells, d -> d.healthy(c.id()) ? 1 : 0)
                    .tag("cell", String.valueOf(c.code())).description("1 while the cell passes its health checks")
                    .register(meters);
            Gauge.builder("meridian.router.cell.serving", cells, d -> {
                CellDirectory.View v = d.view(c.id());
                return v == null || v.down(false) ? 0 : "READ_ONLY".equals(v.status()) ? 0.5 : 1;
            }).tag("cell", String.valueOf(c.code())).description("1 serving, 0.5 read-only, 0 offline").register(meters);
        }
    }
}
