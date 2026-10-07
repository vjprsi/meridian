package io.meridian.web.security;

import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Token checks beyond signature / issuer / expiry ({@code meridian.security.*}).
 *
 * @param audiences              SEC-14: the token's {@code aud} must contain one of these (default {@code meridian-api};
 *                               the service's own name is always accepted too). Empty list disables the check.
 * @param allowedClients         SEC-14: OAuth clients ({@code azp}) whose tokens this service accepts; empty = any
 *                               client of the realm that carries the audience (e.g. card-vault-service lists only the
 *                               staff console and the service accounts of its callers, so developer-portal / agent-app
 *                               tokens are refused)
 * @param platformOperatorTenant SEC-15: tenant id of the platform operator. {@code platform:operator} operations accept
 *                               a user token only when its tenant claim is this tenant (internal service tokens are
 *                               accepted with the scope). Unset: only internal tokens may perform operator operations.
 * @param legacyServiceClients   GAP-16 migration: shared service clients (e.g. {@code meridian-service}) whose internal
 *                               tokens may still name the calling service in {@code X-Meridian-Service}. Per-service
 *                               clients ({@code svc-<service>}) never need it: their identity is the token's
 *                               {@code azp}. Default empty: the header is never believed in jwt mode.
 * @param privilegedAcr          GAP-13: minimum authentication level ({@code acr}) of a user token whose access rests on
 *                               {@code meridian:admin} or {@code platform:operator} (default 2 = password plus a second
 *                               factor within the level-2 max age); 0 disables the check. Internal service tokens are
 *                               exempt.
 */
@ConfigurationProperties(prefix = "meridian.security")
public record SecurityProperties(List<String> audiences, List<String> allowedClients, String platformOperatorTenant,
                                 List<String> legacyServiceClients, Integer privilegedAcr) {
    public static final String DEFAULT_AUDIENCE = "meridian-api";
    public static final int DEFAULT_PRIVILEGED_ACR = 2;

    @ConstructorBinding
    public SecurityProperties {
        audiences = audiences == null ? List.of(DEFAULT_AUDIENCE) : clean(audiences);
        allowedClients = allowedClients == null ? List.of() : clean(allowedClients);
        platformOperatorTenant = platformOperatorTenant == null || platformOperatorTenant.isBlank() ? null
                : platformOperatorTenant.trim();
        legacyServiceClients = legacyServiceClients == null ? List.of() : clean(legacyServiceClients);
        privilegedAcr = privilegedAcr == null ? DEFAULT_PRIVILEGED_ACR : Math.max(0, privilegedAcr);
    }

    public SecurityProperties(List<String> audiences, List<String> allowedClients, String platformOperatorTenant) {
        this(audiences, allowedClients, platformOperatorTenant, null, null);
    }

    public static SecurityProperties defaults() {
        return new SecurityProperties(null, null, null);
    }

    private static List<String> clean(List<String> values) {
        return values.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
