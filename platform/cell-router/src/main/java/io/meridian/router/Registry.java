package io.meridian.router;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** What the router knows from the tenant registry (tenant-service): cells and tenant placements. */
public final class Registry {
    private Registry() {}

    /** A deployment cell as the router sees it. */
    public record Cell(UUID id, String code, String region, String status, String gatewayUrl, String healthUrl) {
        static Cell of(JsonNode n) {
            return new Cell(UUID.fromString(n.get("id").asString()), text(n, "code"), text(n, "region"),
                    text(n, "status"), trimSlash(text(n, "gateway_url")), text(n, "health_url"));
        }
    }

    /** One tenant's routing: lifecycle status, traffic mode (NORMAL / READ_ONLY / BLOCKED), home and standby cells. */
    public record TenantRouting(UUID tenantId, String status, String trafficMode, UUID primaryCellId, UUID standbyCellId) {
        static TenantRouting of(JsonNode n) {
            return new TenantRouting(UUID.fromString(n.get("tenant_id").asString()), text(n, "status"),
                    n.hasNonNull("traffic_mode") ? n.get("traffic_mode").asString() : "NORMAL",
                    id(n.get("primary")), id(n.get("standby")));
        }

        private static UUID id(JsonNode cell) {
            return cell == null || cell.isNull() || !cell.hasNonNull("id") ? null : UUID.fromString(cell.get("id").asString());
        }
    }

    static String text(JsonNode n, String field) {
        return n != null && n.hasNonNull(field) ? n.get(field).asString() : null;
    }

    private static String trimSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** The registry could not be reached or answered with an error (not "unknown tenant"). */
    public static final class UnavailableException extends RuntimeException {
        public UnavailableException(String message) {
            super(message);
        }
    }
}
