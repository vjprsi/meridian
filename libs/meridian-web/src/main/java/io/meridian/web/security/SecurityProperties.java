package io.meridian.web.security;

import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token checks beyond signature / issuer / expiry ({@code meridian.security.*}).
 *
 * @param audiences              SEC-14: the token's {@code aud} must contain one of these (default {@code meridian-api};
 *                               the service's own name is always accepted too). Empty list disables the check.
 * @param allowedClients         SEC-14: OAuth clients ({@code azp}) whose tokens this service accepts; empty = any
 *                               client of the realm that carries the audience (e.g. card-vault-service lists only the
 *                               staff console and the service account, so developer-portal / agent-app tokens are refused)
 * @param platformOperatorTenant SEC-15: tenant id of the platform operator. {@code platform:operator} operations accept
 *                               a user token only when its tenant claim is this tenant (internal service tokens are
 *                               accepted with the scope). Unset: only internal tokens may perform operator operations.
 */
@ConfigurationProperties(prefix = "meridian.security")
public record SecurityProperties(List<String> audiences, List<String> allowedClients, String platformOperatorTenant) {
    public static final String DEFAULT_AUDIENCE = "meridian-api";

    public SecurityProperties {
        audiences = audiences == null ? List.of(DEFAULT_AUDIENCE)
                : audiences.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).toList();
        allowedClients = allowedClients == null ? List.of()
                : allowedClients.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).toList();
        platformOperatorTenant = platformOperatorTenant == null || platformOperatorTenant.isBlank() ? null
                : platformOperatorTenant.trim();
    }

    public static SecurityProperties defaults() {
        return new SecurityProperties(null, null, null);
    }
}
