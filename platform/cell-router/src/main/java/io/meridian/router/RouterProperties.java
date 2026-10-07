package io.meridian.router;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Cell router configuration ({@code meridian.router.*}).
 *
 * @param registryUrl    base URL of tenant-service (the tenant registry of the control plane)
 * @param operatorTenant platform-operator tenant id sent as {@code X-Tenant-Id} (honoured only for internal tokens;
 *                       the router's own client already carries the operator tenant in its token)
 * @param auth           client credentials of the router's service identity (empty token URI: no token, dev only)
 * @param jwt            verification of callers' access tokens (JWKS, issuer, audiences, tenant claim)
 * @param cacheTtl       how long a tenant's placement is cached without an invalidation event
 * @param negativeTtl    how long an unknown tenant is remembered (protects the registry from random ids)
 * @param maxStale       how long a cached placement keeps serving while the registry is unreachable
 * @param cellsRefresh   period of the full cell directory refresh (statuses, gateway URLs)
 * @param timeout        upstream request timeout
 * @param retryAfter     Retry-After sent with 503 for frozen tenants and unavailable cells
 * @param cellOverrides  break-glass serving state per cell id or code (OFFLINE / READ_ONLY / ACTIVE), applied over the
 *                       registry, for a regional failover while the control plane itself is unreachable
 */
@ConfigurationProperties(prefix = "meridian.router")
public record RouterProperties(String registryUrl, String operatorTenant, Auth auth, Jwt jwt, Duration cacheTtl,
                               Duration negativeTtl, Duration maxStale, Duration cellsRefresh, Duration timeout,
                               Duration retryAfter, Health health, Events events, Map<String, String> cellOverrides,
                               List<String> trustedProxies, DataSize maxBodySize) {
    public RouterProperties {
        auth = auth == null ? new Auth(null, null, null, null) : auth;
        jwt = jwt == null ? new Jwt(null, null, null, null) : jwt;
        cacheTtl = cacheTtl == null ? Duration.ofSeconds(30) : cacheTtl;
        negativeTtl = negativeTtl == null ? Duration.ofSeconds(5) : negativeTtl;
        maxStale = maxStale == null ? Duration.ofMinutes(15) : maxStale;
        cellsRefresh = cellsRefresh == null ? Duration.ofSeconds(15) : cellsRefresh;
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        retryAfter = retryAfter == null ? Duration.ofSeconds(30) : retryAfter;
        health = health == null ? new Health(true, null, null, null, 0, 0, false) : health;
        events = events == null ? new Events(true, null) : events;
        cellOverrides = cellOverrides == null ? Map.of() : cellOverrides;
        trustedProxies = trustedProxies == null ? List.of() : trustedProxies;
        maxBodySize = maxBodySize == null ? DataSize.ofMegabytes(64) : maxBodySize;
    }

    /**
     * OAuth2 client credentials of the router's own client ({@value #CLIENT_ID}: scope {@value #SCOPE} only, tokens of
     * the platform-operator tenant), never the shared {@code meridian-service} client with {@code meridian:internal}
     * and {@code platform:operator}.
     */
    public record Auth(String tokenUri, String clientId, String clientSecret, String scope) {
        public static final String CLIENT_ID = "meridian-cell-router";
        public static final String SCOPE = "platform:routing-read";

        public Auth {
            clientId = clientId == null || clientId.isBlank() ? CLIENT_ID : clientId;
            scope = scope == null || scope.isBlank() ? SCOPE : scope;
        }
    }

    /** Access-token verification: signature (JWKS), issuer, audience (SEC-14), tenant claim name. */
    public record Jwt(String jwkSetUri, String issuer, List<String> audiences, String tenantClaim) {
        public Jwt {
            audiences = audiences == null ? List.of() : audiences.stream().filter(a -> a != null && !a.isBlank()).toList();
            tenantClaim = tenantClaim == null || tenantClaim.isBlank() ? "tenant_id" : tenantClaim;
        }
    }

    /**
     * Active health checks of cell gateways with hysteresis: a cell is unhealthy after {@code failureThreshold}
     * consecutive failures and healthy again after {@code successThreshold} consecutive successes. Health only
     * triggers failover when {@code autoFailover} is set (use it where standby cells are READ_ONLY warm standbys, so
     * an automatic failover never sends writes to an unpromoted database); otherwise an operator marks the cell OFFLINE.
     */
    public record Health(Boolean enabled, Duration interval, Duration probeTimeout, String path, int failureThreshold,
                         int successThreshold, boolean autoFailover) {
        public Health {
            enabled = enabled == null || enabled;
            interval = interval == null ? Duration.ofSeconds(5) : interval;
            probeTimeout = probeTimeout == null ? Duration.ofSeconds(2) : probeTimeout;
            path = path == null || path.isBlank() ? "/actuator/health/readiness" : path;
            failureThreshold = failureThreshold <= 0 ? 3 : failureThreshold;
            successThreshold = successThreshold <= 0 ? 5 : successThreshold;
        }
    }

    /** Invalidation events from tenant-service ({@code meridian.platform.v1}); every router instance reads them all. */
    public record Events(Boolean enabled, String topic) {
        public Events {
            enabled = enabled == null || enabled;
            topic = topic == null || topic.isBlank() ? "meridian.platform.v1" : topic;
        }
    }
}
