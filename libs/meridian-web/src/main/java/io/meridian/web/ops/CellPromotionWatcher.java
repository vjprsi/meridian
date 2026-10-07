package io.meridian.web.ops;

import io.meridian.common.cell.CellRole;
import io.meridian.common.tenancy.TenantContext;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * MR-GAME-DAY: promotes a standby service automatically when the tenant registry says its cell is serving.
 *
 * <p>While this service's cell role is STANDBY, every {@code meridian.cell.promotion.poll-interval} (default 5 s) it
 * reads the cell list from the registry (tenant-service, {@code GET /v1/platform/cells}, scope
 * {@code platform:routing-read} of the service's own client; the registry URL is the usual
 * {@code meridian.services.tenant-service.url}, pointed at the control plane in a standby cell). When the row whose
 * {@code code} equals {@code meridian.cell.id} is ACTIVE (the operator's {@code POST /cells/{id}/online}, which also
 * publishes {@code platform.cell.status_changed}), the service promotes itself in place. Kafka cannot carry the
 * signal: a standby cell's listeners are off by design. The watcher never demotes. Registry errors are logged and
 * retried; the operator endpoint stays available as the manual path.
 */
public class CellPromotionWatcher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CellPromotionWatcher.class);

    private final CellRole role;
    private final Function<String, JsonNode> registryGet;
    private final UUID operatorTenant;
    private final Duration interval;
    private ScheduledExecutorService executor;
    private volatile String lastError;

    /**
     * @param registryGet GET of a tenant-service path with this service's own identity (ServiceClient.asService)
     */
    public CellPromotionWatcher(CellRole role, Function<String, JsonNode> registryGet, UUID operatorTenant,
                                Duration interval) {
        this.role = role;
        this.registryGet = registryGet;
        this.operatorTenant = operatorTenant;
        this.interval = interval;
    }

    public CellPromotionWatcher start() {
        if (role.active() || role.cellId().isBlank()) {
            return this;
        }
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("cell-promotion-watcher").factory());
        executor.scheduleWithFixedDelay(this::poll, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("Cell {} is standby: watching the registry every {} for promotion", role.cellId(), interval);
        return this;
    }

    /** One check; true when it promoted. */
    public boolean poll() {
        if (role.active()) {
            stop();
            return false;
        }
        try {
            Optional<String> status = cellStatus();
            lastError = null;
            if (status.isPresent() && "ACTIVE".equals(status.get())) {
                boolean changed = role.promote("registry: cell " + role.cellId() + " is ACTIVE", "cell-registry");
                if (changed) {
                    log.warn("Cell {} is ACTIVE in the registry: promoting this service to primary", role.cellId());
                }
                stop();
                return changed;
            }
        } catch (RuntimeException e) {
            if (!String.valueOf(e.getMessage()).equals(lastError)) {
                log.warn("Cell promotion watcher cannot read the registry: {}", e.getMessage());
            }
            lastError = String.valueOf(e.getMessage());
        }
        return false;
    }

    Optional<String> cellStatus() {
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            String path = "/v1/platform/cells?limit=200" + (cursor == null ? ""
                    : "&cursor=" + java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8));
            JsonNode body = operatorTenant == null ? registryGet.apply(path) : callAs(path);
            for (JsonNode c : body.path("data")) {
                if (role.cellId().equals(c.path("code").asString(null))) {
                    return Optional.ofNullable(c.path("status").asString(null));
                }
            }
            cursor = body.path("next_cursor").asString(null);
            if (cursor == null || !body.path("has_more").asBoolean(false)) {
                break;
            }
        }
        return Optional.empty();
    }

    private JsonNode callAs(String path) {
        try {
            return TenantContext.callAs(operatorTenant, "cell-promotion-watcher", () -> registryGet.apply(path));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void stop() {
        ScheduledExecutorService e = executor;
        if (e != null) {
            e.shutdown();
        }
    }

    @Override
    public void close() {
        stop();
    }
}
