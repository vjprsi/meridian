package io.meridian.tenant.domain;

import io.meridian.common.error.ApiException;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Placement rules for tenants on cells (pure functions over tenant / cell rows): which tenants may move, which cells
 * may receive a tenant or stand by for one, data residency, and how a cell's deployment model relates to capacity.
 */
public final class Placement {
    private Placement() {}

    /** Tenant states whose data may move between cells (a terminated or offboarding tenant stays where it is). */
    public static final Set<String> MOVABLE_TENANT = Set.of("PROVISIONING", "ACTIVE", "SUSPENDED");
    /** Cell states that can take over a tenant's traffic as DR standby (READ_ONLY = warm standby, promoted on failover). */
    public static final Set<String> STANDBY_READY = Set.of("ACTIVE", "READ_ONLY");
    /** Move states that hold the tenant (only one move per tenant at a time). */
    public static final Set<String> OPEN_MOVE = Set.of("PENDING_APPROVAL", "APPROVED", "FROZEN", "COPYING", "COPIED",
            "SWITCHED");

    /**
     * Data residency: a tenant with {@code data_residency_country} may only live (or stand by) on a cell hosted in that
     * country; when the cell does not declare its country, only in the tenant's home region.
     */
    public static boolean residencyAllows(JsonNode tenant, JsonNode cell) {
        String country = text(tenant, "data_residency_country");
        if (country == null) {
            return true;
        }
        String cellCountry = text(cell, "country");
        if (cellCountry != null) {
            return cellCountry.equalsIgnoreCase(country);
        }
        String home = text(tenant, "home_region");
        return home != null && home.equals(text(cell, "region"));
    }

    public static void requireResidency(JsonNode tenant, JsonNode cell) {
        if (!residencyAllows(tenant, cell)) {
            throw ApiException.unprocessable("data_residency_violation", "Tenant data must stay in "
                    + (text(cell, "country") != null || text(tenant, "home_region") == null
                    ? "country " + text(tenant, "data_residency_country")
                    : "region " + text(tenant, "home_region")) + "; cell " + text(cell, "code") + " is not allowed");
        }
    }

    /** Maximum tenants of a cell: one for a dedicated or self-hosted cell, the configured capacity for a shared cell. */
    public static int capacity(JsonNode cell) {
        return dedicated(cell) ? 1 : cell.path("capacity_tenants").asInt(0);
    }

    public static boolean dedicated(JsonNode cell) {
        String model = text(cell, "deployment_model");
        return cell.path("is_dedicated").asBoolean(false) || "DEDICATED".equals(model) || "SELF_HOSTED".equals(model);
    }

    /** A cell receiving a moving tenant: ACTIVE, with room, allowed by residency, not the source. */
    public static void requireMoveTarget(JsonNode tenant, JsonNode from, JsonNode to) {
        if (from != null && text(from, "id").equals(text(to, "id"))) {
            throw ApiException.unprocessable("same_cell", "The tenant is already placed on cell " + text(to, "code"));
        }
        if (!"ACTIVE".equals(text(to, "status"))) {
            throw ApiException.unprocessable("cell_not_accepting_tenants",
                    "Cell " + text(to, "code") + " is " + text(to, "status"));
        }
        int count = to.path("tenant_count").asInt(0);
        if (count >= capacity(to)) {
            throw ApiException.unprocessable("cell_capacity_exhausted",
                    "Cell " + text(to, "code") + " has no capacity (" + count + "/" + capacity(to) + ")");
        }
        requireResidency(tenant, to);
    }

    /** A DR standby for a tenant: another cell, ready to serve (ACTIVE or warm READ_ONLY), allowed by residency. */
    public static void requireStandby(JsonNode tenant, JsonNode primary, JsonNode standby) {
        if (primary != null && text(primary, "id").equals(text(standby, "id"))) {
            throw ApiException.unprocessable("standby_is_home_cell", "The standby cell must differ from the home cell");
        }
        if (!STANDBY_READY.contains(text(standby, "status"))) {
            throw ApiException.unprocessable("cell_not_standby_ready",
                    "Cell " + text(standby, "code") + " is " + text(standby, "status") + " (needs ACTIVE or READ_ONLY)");
        }
        requireResidency(tenant, standby);
    }

    private static String text(JsonNode n, String field) {
        return n != null && n.hasNonNull(field) ? n.get(field).asString() : null;
    }
}
