package io.meridian.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform-wide settings for every service ({@code meridian.*}).
 *
 * @param securityMode {@code jwt} (default): OAuth2 bearer tokens with a {@code tenant_id} claim;
 *                     {@code dev}: no token, tenant from the {@code X-Tenant-Id} header (local development only)
 * @param tenantClaim  JWT claim carrying the tenant id
 */
@ConfigurationProperties(prefix = "meridian")
public record MeridianProperties(String securityMode, String tenantClaim) {
    public MeridianProperties {
        if (securityMode == null || securityMode.isBlank()) {
            securityMode = "jwt";
        }
        if (tenantClaim == null || tenantClaim.isBlank()) {
            tenantClaim = "tenant_id";
        }
    }

    public boolean devMode() {
        return "dev".equalsIgnoreCase(securityMode);
    }
}
