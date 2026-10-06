package io.meridian.businessday.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Tenants and legal entities from tenant-service. Called without an inbound request, so {@link ServiceClient} uses
 * the service's client-credentials token ({@code meridian:internal}) and the bound tenant as {@code X-Tenant-Id}.
 */
@Component
public class TenantDirectory {
    static final String TENANT_SERVICE = "tenant-service";
    private static final Logger log = LoggerFactory.getLogger(TenantDirectory.class);
    private static final int MAX_PAGES = 100;

    private final ServiceClient services;

    public TenantDirectory(ServiceClient services) {
        this.services = services;
    }

    /** A tenant as the trigger needs it. */
    public record Tenant(UUID id, String code, String timezone) {}

    /** Every ACTIVE tenant (paged through tenant-service's platform API). */
    public List<Tenant> activeTenants() {
        List<Tenant> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String path = "/v1/platform/tenants?status=ACTIVE&limit=200"
                    + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            JsonNode res = services.get(TENANT_SERVICE, path);
            if (res == null) {
                break;
            }
            for (JsonNode t : res.path("data")) {
                if (t.hasNonNull("id") && "ACTIVE".equals(t.path("status").asString("ACTIVE"))) {
                    out.add(new Tenant(UUID.fromString(t.get("id").asString()), t.path("code").asString(null),
                            t.path("timezone").asString(null)));
                }
            }
            if (!res.path("has_more").asBoolean(false) || !res.hasNonNull("next_cursor")) {
                break;
            }
            cursor = res.get("next_cursor").asString();
        }
        return out;
    }

    /** One tenant (best effort: empty when tenant-service cannot answer). */
    public Optional<Tenant> tenant(UUID id) {
        try {
            JsonNode t = services.get(TENANT_SERVICE, "/v1/platform/tenants/" + id);
            return t == null || !t.hasNonNull("id") ? Optional.empty()
                    : Optional.of(new Tenant(id, t.path("code").asString(null), t.path("timezone").asString(null)));
        } catch (ApiException | IllegalStateException | IllegalArgumentException e) {
            log.debug("tenant {} lookup failed: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    /** IANA time zone of a legal entity of the bound tenant (best effort: empty when unknown or unreachable). */
    public Optional<String> legalEntityTimezone(UUID legalEntityId) {
        try {
            JsonNode le = services.get(TENANT_SERVICE, "/v1/legal-entities/" + legalEntityId);
            return le == null || !le.hasNonNull("timezone") ? Optional.empty() : Optional.of(le.get("timezone").asString());
        } catch (ApiException | IllegalStateException | IllegalArgumentException e) {
            log.debug("legal entity {} lookup failed: {}", legalEntityId, e.getMessage());
            return Optional.empty();
        }
    }
}
