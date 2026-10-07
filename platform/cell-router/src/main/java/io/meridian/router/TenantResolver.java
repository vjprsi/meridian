package io.meridian.router;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.StringUtils;

/**
 * Which tenant a request belongs to.
 *
 * <ul>
 *   <li>jwt mode (default): the {@code tenant_id} claim of a bearer token whose signature (IdP JWKS), issuer, expiry
 *       and audience (SEC-14) check out. Client-supplied tenant headers ({@code X-Tenant-Id}) are never read, and are
 *       stripped before the request reaches the cell (SEC-13).</li>
 *   <li>Public provider callbacks (routes marked public in the contract, no bearer token): the {@code tenant_id}
 *       query parameter of the callback URL; the cell's service still verifies the provider signature (SEC-17).</li>
 *   <li>dev mode (local stack only): {@code X-Tenant-Id}, then {@code tenant_id}.</li>
 * </ul>
 */
public class TenantResolver {
    public record Resolution(UUID tenant, int status, String code, String detail) {
        static Resolution of(UUID tenant) {
            return new Resolution(tenant, 0, null, null);
        }

        static Resolution error(int status, String code, String detail) {
            return new Resolution(null, status, code, detail);
        }

        public boolean ok() {
            return tenant != null;
        }
    }

    private final boolean devMode;
    private final String tenantClaim;
    private final Supplier<JwtDecoder> decoder;

    public TenantResolver(boolean devMode, String tenantClaim, Supplier<JwtDecoder> decoder) {
        this.devMode = devMode;
        this.tenantClaim = tenantClaim;
        this.decoder = decoder;
    }

    /** Decoder for the configured JWKS / issuer / audiences (lazy: the JWKS is fetched on first use and cached). */
    public static JwtDecoder decoder(RouterProperties.Jwt jwt) {
        if (!StringUtils.hasText(jwt.jwkSetUri())) {
            throw new IllegalStateException("meridian.router.jwt.jwk-set-uri is required in jwt mode");
        }
        NimbusJwtDecoder d = NimbusJwtDecoder.withJwkSetUri(jwt.jwkSetUri()).build();
        OAuth2TokenValidator<Jwt> base = StringUtils.hasText(jwt.issuer())
                ? JwtValidators.createDefaultWithIssuer(jwt.issuer()) : JwtValidators.createDefault();
        List<String> audiences = jwt.audiences();
        OAuth2TokenValidator<Jwt> audience = token -> audiences.isEmpty()
                || token.getAudience() != null && token.getAudience().stream().anyMatch(audiences::contains)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "audience not accepted", null));
        d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(base, audience));
        return d;
    }

    public Resolution resolve(HttpServletRequest req, boolean publicRoute) {
        if (devMode) {
            UUID t = uuid(req.getHeader("X-Tenant-Id"));
            if (t == null) {
                t = uuid(queryParam(req, "tenant_id"));
            }
            return t != null ? Resolution.of(t)
                    : Resolution.error(400, "tenant_required", "X-Tenant-Id or tenant_id is required (dev mode)");
        }
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            Jwt jwt;
            try {
                jwt = decoder.get().decode(auth.substring(7).trim());
            } catch (JwtException e) {
                return Resolution.error(401, "invalid_token", "The access token is invalid or expired");
            }
            UUID t = uuid(jwt.getClaimAsString(tenantClaim));
            return t != null ? Resolution.of(t)
                    : Resolution.error(403, "tenant_claim_missing", "The access token carries no " + tenantClaim + " claim");
        }
        if (publicRoute) {
            UUID t = uuid(queryParam(req, "tenant_id"));
            return t != null ? Resolution.of(t)
                    : Resolution.error(400, "tenant_required", "Callbacks through the cell router must carry tenant_id");
        }
        return Resolution.error(401, "unauthenticated", "A bearer token is required");
    }

    /**
     * A query-string parameter, read from the raw query (never {@code getParameter}, which would consume a form body
     * that must be streamed to the cell untouched).
     */
    static String queryParam(HttpServletRequest req, String name) {
        String q = req.getQueryString();
        if (q == null) {
            return null;
        }
        try {
            for (String pair : q.split("&")) {
                int eq = pair.indexOf('=');
                String key = java.net.URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
                if (key.equals(name)) {
                    return eq < 0 ? "" : java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        } catch (IllegalArgumentException e) {
            return null;    // malformed escape: treat as absent
        }
        return null;
    }

    static UUID uuid(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(v.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
