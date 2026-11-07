package io.meridian.web.config;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.client.ServiceClient;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Per-tenant ledger routing read from config-service, so one deployment can serve many tenants: the ledger book and
 * the internal (GL / clearing / income) ledger accounts a service posts to are tenant data, not deployment constants.
 *
 * <p>Source: config-service namespace {@value #NAMESPACE} (approval-gated, versioned), resolved with
 * {@code GET /v1/config/entries/resolve?namespace=ledger}. One key per service (e.g. {@code deposit-service}) whose
 * value mirrors that service's {@code meridian.<x>.*} ledger properties in snake_case, e.g.
 * <pre>{"ledger_id": "...", "gl": {"clearing": "...", "fee_income": "..."}}</pre>
 * plus an optional {@code default} key whose {@code ledger_id} is the tenant's main book for every service.
 *
 * <p>Precedence per value: the tenant's service key, then (for {@code ledger_id}) the tenant's {@code default} key,
 * then the deployment property (single-tenant installs, tests). Results are cached per tenant for
 * {@code meridian.tenant-settings.ttl-ms} (default 30 s); a missing or unreachable config-service means "not set".
 *
 * <p>Services expose it through their configuration records' accessors (a record accessor can consult
 * {@link #uuid}), so call sites keep reading {@code props.ledgerId()} / {@code props.gl().clearing()}.
 */
public class TenantSettings {
    public static final String NAMESPACE = "ledger";
    static final String CONFIG = "config-service";
    private static final Logger log = LoggerFactory.getLogger(TenantSettings.class);
    private static volatile TenantSettings installed;

    private final ServiceClient client;
    private final long ttlMs;
    private final LongSupplier clock;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(JsonNode values, long expiresAt) {}

    public TenantSettings(ServiceClient client, long ttlMs) {
        this(client, ttlMs, System::currentTimeMillis);
    }

    TenantSettings(ServiceClient client, long ttlMs, LongSupplier clock) {
        this.client = client;
        this.ttlMs = ttlMs;
        this.clock = clock;
    }

    /** Makes this instance the one used by {@link #uuid(String, String, UUID)} (done by the auto-configuration). */
    public TenantSettings install() {
        installed = this;
        return this;
    }

    /** Removes the installed instance (tests). */
    public static void uninstall() {
        installed = null;
    }

    /**
     * The tenant's value at {@code path} (dot separated, snake_case) of the service's ledger settings, else
     * {@code fallback}. Without a bound tenant or an installed instance returns {@code fallback}.
     */
    public static UUID uuid(String service, String path, UUID fallback) {
        TenantSettings s = installed;
        if (s == null) {
            return fallback;
        }
        return s.lookup(service, path).map(TenantSettings::toUuid).orElse(fallback);
    }

    /**
     * The tenant's JSON value at {@code path} of the service's settings (objects, arrays, numbers), or empty without a
     * bound tenant, an installed instance or a value. Lets services keep currency-dependent values (approval
     * thresholds, limits) per tenant instead of one deployment constant.
     */
    public static Optional<JsonNode> json(String service, String path) {
        TenantSettings s = installed;
        return s == null ? Optional.empty() : s.lookup(service, path);
    }

    /** Instance form of {@link #uuid(String, String, UUID)}. */
    public Optional<JsonNode> lookup(String service, String path) {
        Optional<UUID> tenant = TenantContext.current();
        if (tenant.isEmpty()) {
            return Optional.empty();
        }
        JsonNode values = values(tenant.get());
        Optional<JsonNode> own = at(values.path(service), path);
        if (own.isPresent()) {
            return own;
        }
        return "ledger_id".equals(path) ? at(values.path("default"), path) : Optional.empty();
    }

    /** Drops cached settings (all tenants), e.g. after a configuration change event. */
    public void evict() {
        cache.clear();
    }

    private JsonNode values(UUID tenant) {
        long now = clock.getAsLong();
        Cached c = cache.get(tenant);
        if (c != null && c.expiresAt() > now) {
            return c.values();
        }
        JsonNode values = fetch();
        if (values == null) { // unreachable: retry soon rather than after a full TTL
            values = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            cache.put(tenant, new Cached(values, now + Math.min(ttlMs, 5_000)));
            return values;
        }
        cache.put(tenant, new Cached(values, now + ttlMs));
        return values;
    }

    private JsonNode fetch() {
        try {
            JsonNode resolved = client.get(CONFIG, "/v1/config/entries/resolve?namespace=" + NAMESPACE);
            JsonNode values = resolved == null ? null : resolved.get("values");
            return values == null || !values.isObject() ? tools.jackson.databind.node.JsonNodeFactory.instance.objectNode() : values;
        } catch (RuntimeException e) {
            log.debug("tenant ledger settings unavailable: {}", e.getMessage());
            return null;
        }
    }

    private static Optional<JsonNode> at(JsonNode node, String path) {
        JsonNode n = node;
        for (String part : path.split("\\.")) {
            if (n == null || !n.isObject()) {
                return Optional.empty();
            }
            n = n.get(part);
        }
        return n == null || n.isNull() || (n.isString() && n.asString().isBlank()) ? Optional.empty() : Optional.of(n);
    }

    private static UUID toUuid(JsonNode n) {
        try {
            return UUID.fromString(n.asString().trim());
        } catch (IllegalArgumentException e) {
            log.warn("tenant ledger setting is not a uuid: {}", n);
            return null;
        }
    }
}
