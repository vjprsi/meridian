package io.meridian.common.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * The tenant of the current request. Set by the web layer from the access token, read by the data layer to
 * scope every connection with {@code app.tenant_id} (row-level security). Works with virtual threads.
 */
public final class TenantContext {
    /** Sentinel used when no tenant is bound: matches no rows under row-level security. */
    public static final UUID NO_TENANT = new UUID(0L, 0L);

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> PRINCIPAL = new ThreadLocal<>();
    private static final ThreadLocal<String> VIA = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID tenantId, String principal) {
        CURRENT.set(tenantId);
        PRINCIPAL.set(principal);
    }

    /**
     * Binds tenant and principal for a call made by another service on behalf of {@code principal}: {@code via} is the
     * calling service's client id. Four-eyes checks compare principals (the humans); {@link #via()} only says that a
     * service relayed the call.
     */
    public static void set(UUID tenantId, String principal, String via) {
        set(tenantId, principal);
        VIA.set(via);
    }

    /** The service that relayed the current call on behalf of the principal, if any. */
    public static Optional<String> via() {
        return Optional.ofNullable(VIA.get());
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID require() {
        UUID t = CURRENT.get();
        if (t == null) {
            throw new io.meridian.common.error.ApiException(401, "tenant_required",
                    "No tenant: send a bearer token with a tenant_id claim (or X-Tenant-Id in dev mode)");
        }
        return t;
    }

    public static String principal() {
        String p = PRINCIPAL.get();
        return p == null ? "system" : p;
    }

    public static void clear() {
        CURRENT.remove();
        PRINCIPAL.remove();
        VIA.remove();
    }

    /** Runs {@code body} with the given tenant bound, restoring the previous binding afterwards. */
    public static <T> T callAs(UUID tenantId, String principal, Callable<T> body) throws Exception {
        UUID prevTenant = CURRENT.get();
        String prevPrincipal = PRINCIPAL.get();
        set(tenantId, principal);
        try {
            return body.call();
        } finally {
            if (prevTenant == null) {
                clear();
            } else {
                set(prevTenant, prevPrincipal);
            }
        }
    }
}
