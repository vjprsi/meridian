package io.meridian.router;

import java.util.Set;

/**
 * Where a request for a tenant goes (pure function, no I/O):
 *
 * <ol>
 *   <li>traffic mode BLOCKED: nothing is served (503); READ_ONLY (a move froze the tenant's writes): writes get 503
 *       {@code tenant_read_only} + Retry-After, reads continue;</li>
 *   <li>the home cell serves unless it is OFFLINE / DECOMMISSIONED / without URL (or, with auto-failover, failed its
 *       health checks); then the standby cell takes the tenant if it is itself up, else 503 {@code cell_unavailable};</li>
 *   <li>the chosen cell in READ_ONLY (warm standby not yet promoted, maintenance) serves reads only.</li>
 * </ol>
 */
public final class RoutingPolicy {
    private RoutingPolicy() {}

    static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    public sealed interface Decision permits Route, Refuse {}

    /** Proxy to {@code cell}; {@code failover} when it is the standby standing in for an OFFLINE home cell. */
    public record Route(CellDirectory.View cell, boolean failover) implements Decision {}

    /** Answer locally with {@code status} (503 carries Retry-After). */
    public record Refuse(int status, String code, String detail, CellDirectory.View cell) implements Decision {}

    public static boolean isWrite(String method) {
        return !READ_METHODS.contains(method);
    }

    public static Decision decide(Registry.TenantRouting tenant, String method, CellDirectory.View primary,
                                  CellDirectory.View standby, boolean autoFailover) {
        boolean write = isWrite(method);
        String mode = tenant.trafficMode() == null ? "NORMAL" : tenant.trafficMode();
        if ("BLOCKED".equals(mode)) {
            return new Refuse(503, "tenant_blocked", "The tenant's traffic is paused by the platform operator", primary);
        }
        if (write && "READ_ONLY".equals(mode)) {
            return new Refuse(503, "tenant_read_only",
                    "The tenant is being moved between cells: writes are paused, reads continue", primary);
        }
        if (primary == null) {
            return new Refuse(503, "tenant_not_placed", "The tenant is not placed on a cell", null);
        }
        CellDirectory.View target = primary;
        boolean failover = false;
        if (primary.down(autoFailover)) {
            if (standby == null || standby.down(autoFailover)) {
                return new Refuse(503, "cell_unavailable", "The tenant's cell is unavailable and no standby can take over",
                        primary);
            }
            target = standby;
            failover = true;
        }
        if (write && "READ_ONLY".equals(target.status())) {
            return new Refuse(503, "cell_read_only", failover
                    ? "Failed over to the standby cell, which serves reads until it is promoted"
                    : "The tenant's cell serves reads only (maintenance)", target);
        }
        return new Route(target, failover);
    }
}
