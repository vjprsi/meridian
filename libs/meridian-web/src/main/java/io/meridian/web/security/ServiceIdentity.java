package io.meridian.web.security;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * GAP-16: every Meridian service has its own confidential OAuth client {@code svc-<service-name>} (client credentials,
 * scope {@code meridian:internal} plus the domain scopes of the services it calls). The token's {@code azp} therefore
 * proves which service is calling; receivers derive {@code TenantContext.callingService()} / {@code via()} from it
 * instead of believing the {@code X-Meridian-Service} header (still honoured in dev mode, and in jwt mode only for the
 * legacy shared client listed in {@code meridian.security.legacy-service-clients} during the migration).
 */
public final class ServiceIdentity {
    /** Prefix of per-service client ids: {@code svc-ledger-service}. */
    public static final String CLIENT_PREFIX = "svc-";
    private static final Pattern SERVICE_NAME = Pattern.compile("^[a-z][a-z0-9-]{1,62}$");

    private ServiceIdentity() {}

    /** The Meridian service of a per-service client id ({@code svc-deposit-service -> deposit-service}). */
    public static Optional<String> serviceOf(String clientId) {
        if (clientId == null || !clientId.startsWith(CLIENT_PREFIX)) {
            return Optional.empty();
        }
        String name = clientId.substring(CLIENT_PREFIX.length());
        return SERVICE_NAME.matcher(name).matches() ? Optional.of(name) : Optional.empty();
    }

    /** The client id of a service ({@code deposit-service -> svc-deposit-service}). */
    public static String clientIdOf(String service) {
        return CLIENT_PREFIX + service;
    }
}
