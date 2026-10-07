package io.meridian.tenant.domain;

import io.meridian.common.error.ApiException;
import java.util.Map;
import java.util.Set;

/**
 * Allowed status transitions of the tenant-service aggregates. Status changes happen only through explicit
 * actions; anything else is rejected with {@code 409 invalid_transition}.
 */
public final class Lifecycle {
    private Lifecycle() {}

    /** Tenant: PROVISIONING -> ACTIVE <-> SUSPENDED -> OFFBOARDING -> TERMINATED. */
    public static final Map<String, Set<String>> TENANT = Map.of(
            "PROVISIONING", Set.of("ACTIVE", "TERMINATED"),
            "ACTIVE", Set.of("SUSPENDED", "OFFBOARDING"),
            "SUSPENDED", Set.of("ACTIVE", "OFFBOARDING"),
            "OFFBOARDING", Set.of("TERMINATED"),
            "TERMINATED", Set.of());

    /**
     * Cell: PROVISIONING -> ACTIVE -> DRAINING -> DECOMMISSIONED, plus the serving states the cell router acts on:
     * READ_ONLY (writes refused: warm DR standby, maintenance) and OFFLINE (tenants fail over to their standby cell).
     * A warm standby may start READ_ONLY; OFFLINE and READ_ONLY return to ACTIVE through /online.
     */
    public static final Map<String, Set<String>> CELL = Map.of(
            "PROVISIONING", Set.of("ACTIVE", "READ_ONLY", "DECOMMISSIONED"),
            "ACTIVE", Set.of("DRAINING", "READ_ONLY", "OFFLINE"),
            "DRAINING", Set.of("ACTIVE", "READ_ONLY", "OFFLINE", "DECOMMISSIONED"),
            "READ_ONLY", Set.of("ACTIVE", "DRAINING", "OFFLINE", "DECOMMISSIONED"),
            "OFFLINE", Set.of("ACTIVE", "READ_ONLY", "DECOMMISSIONED"),
            "DECOMMISSIONED", Set.of());

    /**
     * Tenant move: PENDING_APPROVAL -> APPROVED -> FROZEN -> COPYING -> COPIED -> SWITCHED -> COMPLETED. Rejected in
     * workflow-service -> REJECTED; cancellable until the switch (after it the tenant already lives on the target).
     */
    public static final Map<String, Set<String>> TENANT_MOVE = Map.of(
            "PENDING_APPROVAL", Set.of("APPROVED", "REJECTED", "CANCELLED"),
            "APPROVED", Set.of("FROZEN", "CANCELLED"),
            "FROZEN", Set.of("COPYING", "CANCELLED"),
            "COPYING", Set.of("COPIED", "CANCELLED"),
            "COPIED", Set.of("SWITCHED", "CANCELLED"),
            "SWITCHED", Set.of("COMPLETED"),
            "REJECTED", Set.of(),
            "COMPLETED", Set.of(),
            "CANCELLED", Set.of());

    /** Branch: ACTIVE <-> TEMPORARILY_CLOSED -> CLOSED (terminal). */
    public static final Map<String, Set<String>> BRANCH = Map.of(
            "ACTIVE", Set.of("TEMPORARILY_CLOSED", "CLOSED"),
            "TEMPORARILY_CLOSED", Set.of("ACTIVE", "CLOSED"),
            "CLOSED", Set.of());

    /** Legal entity: ACTIVE <-> INACTIVE -> DISSOLVED (terminal). */
    public static final Map<String, Set<String>> LEGAL_ENTITY = Map.of(
            "ACTIVE", Set.of("INACTIVE", "DISSOLVED"),
            "INACTIVE", Set.of("ACTIVE", "DISSOLVED"),
            "DISSOLVED", Set.of());

    public static boolean allowed(Map<String, Set<String>> machine, String from, String to) {
        return machine.getOrDefault(from, Set.of()).contains(to);
    }

    /** Throws 409 invalid_transition unless {@code from -> to} is allowed. */
    public static void require(Map<String, Set<String>> machine, String entity, String from, String to) {
        if (!allowed(machine, from, to)) {
            throw ApiException.conflict("invalid_transition",
                    entity + " cannot move from " + from + " to " + to);
        }
    }
}
