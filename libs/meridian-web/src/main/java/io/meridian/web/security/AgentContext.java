package io.meridian.web.security;

import io.meridian.common.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The signed-in agent of the current request (agent channel only).
 *
 * <p>A request is an agent request only when its access token carries the agent scope ({@value #AGENT_SCOPE})
 * <em>and</em> an {@value #AGENT_CLAIM} claim (a UUID of agent-network-service). Agent tokens are issued to the agent
 * app client only (Keycloak {@code meridian-agent-app}); they carry no domain scope, so every operator endpoint refuses
 * them ({@link ScopeInterceptor}). Internal service tokens ({@code meridian:internal}) and customer tokens
 * ({@value CustomerContext#CUSTOMER_SCOPE}) are never agent requests, whatever claims they carry. In dev security mode
 * the agent comes from the {@value #AGENT_HEADER} header and the last authentication time from
 * {@value CustomerContext#AUTH_TIME_HEADER} (epoch seconds); both headers are ignored in JWT mode.
 *
 * <p>Bound by {@link io.meridian.web.tenancy.TenantFilter} for the duration of the request and cleared afterwards.
 * {@link io.meridian.web.client.ServiceClient} never forwards an agent's token: calls made while an agent is bound use
 * the service identity with the tenant (and {@code agent:<id>} as the acting user).
 */
public final class AgentContext {
    public static final String AGENT_SCOPE = "channels:agent";
    public static final String AGENT_CLAIM = "agent_id";
    public static final String AGENT_HEADER = "X-Agent-Id";

    /**
     * The agent, the person using the agent app and the strength and age of their authentication.
     *
     * @param agentId  agent id (agent-network-service)
     * @param subject  token subject (dev mode: X-User), the person operating the app; may be null
     * @param authTime time of the last interactive authentication ({@code auth_time}); null if unknown
     * @param acr      authentication context class ({@code acr}); null if absent
     */
    public record AgentPrincipal(UUID agentId, String subject, Instant authTime, String acr) {
        /** Age of the last authentication, empty when the token does not say when it happened. */
        public Optional<Duration> authAge(Instant now) {
            return authTime == null ? Optional.empty() : Optional.of(Duration.between(authTime, now));
        }

        /** Principal name used for audit columns downstream ({@code created_by}). */
        public String principalName() {
            return "agent:" + agentId;
        }

        /**
         * The operator's user id: the subject when it is a UUID (Keycloak user id), else a stable name-based UUID of
         * the subject (dev-mode user names).
         */
        public UUID userId() {
            String s = subject == null || subject.isBlank() ? principalName() : subject.trim();
            UUID parsed = parseUuid(s);
            return parsed != null ? parsed : UUID.nameUUIDFromBytes(("agent-user:" + s).getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static final ThreadLocal<AgentPrincipal> CURRENT = new ThreadLocal<>();

    private AgentContext() {}

    public static Optional<AgentPrincipal> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The signed-in agent; 403 {@code agent_identity_required} for any other caller (staff, services, customers). */
    public static AgentPrincipal require() {
        AgentPrincipal p = CURRENT.get();
        if (p == null) {
            throw new ApiException(403, "agent_identity_required",
                    "This operation is for signed-in agents only (token with scope " + AGENT_SCOPE + " and an "
                            + AGENT_CLAIM + " claim)");
        }
        return p;
    }

    /** Shortcut for {@code require().agentId()}. */
    public static UUID agentId() {
        return require().agentId();
    }

    /** Web layer only (TenantFilter). */
    public static void set(AgentPrincipal principal) {
        CURRENT.set(principal);
    }

    /** Web layer only (TenantFilter). */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * The agent a token identifies, or empty when it is not an agent token (no agent scope, no or malformed agent_id
     * claim, a service token with {@code meridian:internal}, or a customer token).
     */
    public static Optional<AgentPrincipal> fromJwt(Jwt jwt) {
        Set<String> scopes = scopes(jwt);
        if (!scopes.contains(AGENT_SCOPE) || scopes.contains("meridian:internal")
                || scopes.contains(CustomerContext.CUSTOMER_SCOPE)) {
            return Optional.empty();
        }
        UUID agent = parseUuid(jwt.getClaimAsString(AGENT_CLAIM));
        if (agent == null) {
            return Optional.empty();
        }
        return Optional.of(new AgentPrincipal(agent, jwt.getSubject(), epoch(jwt.getClaims().get("auth_time")),
                jwt.getClaimAsString("acr")));
    }

    /** Dev security mode: agent from {@value #AGENT_HEADER}, user from X-User, auth time from X-Auth-Time. */
    public static Optional<AgentPrincipal> fromDevHeaders(String agentHeader, String userHeader, String authTimeHeader,
                                                          String acrHeader) {
        UUID agent = parseUuid(agentHeader);
        if (agent == null) {
            return Optional.empty();
        }
        return Optional.of(new AgentPrincipal(agent, userHeader == null || userHeader.isBlank() ? null : userHeader.trim(),
                epoch(authTimeHeader), acrHeader == null || acrHeader.isBlank() ? null : acrHeader.trim()));
    }

    static Set<String> scopes(Jwt jwt) {
        Object scope = jwt.getClaims().get("scope");
        if (scope instanceof String s) {
            return Set.copyOf(java.util.Arrays.asList(s.trim().split("\\s+")));   // tolerates duplicates
        }
        if (scope instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }

    private static Instant epoch(Object value) {
        if (value instanceof Instant i) {
            return i;
        }
        if (value instanceof Number n) {
            return Instant.ofEpochSecond(n.longValue());
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(s.trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
