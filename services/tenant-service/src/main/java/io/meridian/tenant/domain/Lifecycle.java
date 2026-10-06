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

    /** Cell: PROVISIONING -> ACTIVE -> DRAINING -> READ_ONLY -> DECOMMISSIONED (DRAINING may return to ACTIVE). */
    public static final Map<String, Set<String>> CELL = Map.of(
            "PROVISIONING", Set.of("ACTIVE", "DECOMMISSIONED"),
            "ACTIVE", Set.of("DRAINING"),
            "DRAINING", Set.of("ACTIVE", "READ_ONLY", "DECOMMISSIONED"),
            "READ_ONLY", Set.of("DRAINING", "DECOMMISSIONED"),
            "DECOMMISSIONED", Set.of());

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
