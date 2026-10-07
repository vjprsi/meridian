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
 * The signed-in business portal user of the current request (business channel only).
 *
 * <p>A request is a business request only when its access token carries the business scope ({@value #BUSINESS_SCOPE})
 * <em>and</em> both a {@value #BUSINESS_CUSTOMER_CLAIM} claim (the business: a customer of customer-service) and a
 * {@value #BUSINESS_USER_CLAIM} claim (the portal user: a business_user of business-channel-service), both UUIDs.
 * Business tokens are issued to the business portal client only (Keycloak {@code meridian-business-portal}, claims from
 * admin-only user attributes); they carry no domain scope, so every operator endpoint refuses them
 * ({@link ScopeInterceptor}). Internal service tokens ({@code meridian:internal}), customer tokens
 * ({@value CustomerContext#CUSTOMER_SCOPE}) and agent tokens ({@value AgentContext#AGENT_SCOPE}) are never business
 * requests, whatever claims they carry. In dev security mode the business and the user come from the
 * {@value #BUSINESS_CUSTOMER_HEADER} and {@value #BUSINESS_USER_HEADER} headers and the last authentication time from
 * {@value CustomerContext#AUTH_TIME_HEADER} (epoch seconds); these headers are ignored in JWT mode (and dropped by the
 * edge gateway).
 *
 * <p>Bound by {@link io.meridian.web.tenancy.TenantFilter} for the duration of the request and cleared afterwards.
 * {@link io.meridian.web.client.ServiceClient} never forwards a business user's token: calls made while a business user
 * is bound use the service identity with the tenant (and {@code business-user:<id>} as the acting user). The claims
 * only say who the caller claims to be; business-channel-service checks the user record (business, status, role).
 */
public final class BusinessContext {
    public static final String BUSINESS_SCOPE = "channels:business";
    public static final String BUSINESS_CUSTOMER_CLAIM = "business_customer_id";
    public static final String BUSINESS_USER_CLAIM = "business_user_id";
    public static final String BUSINESS_CUSTOMER_HEADER = "X-Business-Customer-Id";
    public static final String BUSINESS_USER_HEADER = "X-Business-User-Id";

    /**
     * The business, the portal user and the strength and age of their authentication.
     *
     * @param businessCustomerId business customer id (customer-service)
     * @param businessUserId     portal user id (business-channel-service business_user)
     * @param subject            token subject (identity provider user id; dev mode: X-User); may be null
     * @param authTime           time of the last interactive authentication ({@code auth_time}); null if unknown
     * @param acr                authentication context class ({@code acr}); null if absent
     * @param username           {@code preferred_username}; may be null
     */
    public record BusinessPrincipal(UUID businessCustomerId, UUID businessUserId, String subject, Instant authTime,
                                    String acr, String username) {
        /** Age of the last authentication, empty when the token does not say when it happened. */
        public Optional<Duration> authAge(Instant now) {
            return authTime == null ? Optional.empty() : Optional.of(Duration.between(authTime, now));
        }

        /** Principal name used for audit columns downstream ({@code created_by}) and four-eyes comparisons. */
        public String principalName() {
            return principalName(businessUserId);
        }

        /** Principal name of any portal user (used when acting on behalf of the maker or an approver). */
        public static String principalName(UUID businessUserId) {
            return "business-user:" + businessUserId;
        }
    }

    private static final ThreadLocal<BusinessPrincipal> CURRENT = new ThreadLocal<>();

    private BusinessContext() {}

    public static Optional<BusinessPrincipal> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The signed-in business user; 403 {@code business_identity_required} for any other caller. */
    public static BusinessPrincipal require() {
        BusinessPrincipal p = CURRENT.get();
        if (p == null) {
            throw new ApiException(403, "business_identity_required",
                    "This operation is for signed-in business users only (token with scope " + BUSINESS_SCOPE
                            + " and " + BUSINESS_CUSTOMER_CLAIM + " / " + BUSINESS_USER_CLAIM + " claims)");
        }
        return p;
    }

    /** Shortcut for {@code require().businessCustomerId()}. */
    public static UUID businessCustomerId() {
        return require().businessCustomerId();
    }

    /** Shortcut for {@code require().businessUserId()}. */
    public static UUID businessUserId() {
        return require().businessUserId();
    }

    /** Web layer only (TenantFilter). */
    public static void set(BusinessPrincipal principal) {
        CURRENT.set(principal);
    }

    /** Web layer only (TenantFilter). */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * The business user a token identifies, or empty when it is not a business token (no business scope, a missing or
     * malformed claim, a service token with {@code meridian:internal}, a customer or an agent token).
     */
    public static Optional<BusinessPrincipal> fromJwt(Jwt jwt) {
        Set<String> scopes = scopes(jwt);
        if (!scopes.contains(BUSINESS_SCOPE) || scopes.contains("meridian:internal")
                || scopes.contains(CustomerContext.CUSTOMER_SCOPE) || scopes.contains(AgentContext.AGENT_SCOPE)) {
            return Optional.empty();
        }
        UUID business = parseUuid(jwt.getClaimAsString(BUSINESS_CUSTOMER_CLAIM));
        UUID user = parseUuid(jwt.getClaimAsString(BUSINESS_USER_CLAIM));
        if (business == null || user == null) {
            return Optional.empty();
        }
        return Optional.of(new BusinessPrincipal(business, user, jwt.getSubject(),
                epoch(jwt.getClaims().get("auth_time")), jwt.getClaimAsString("acr"),
                jwt.getClaimAsString("preferred_username")));
    }

    /** Dev security mode: business and user from the X-Business-* headers, auth time from X-Auth-Time. */
    public static Optional<BusinessPrincipal> fromDevHeaders(String businessHeader, String userIdHeader,
                                                             String subjectHeader, String authTimeHeader,
                                                             String acrHeader) {
        UUID business = parseUuid(businessHeader);
        UUID user = parseUuid(userIdHeader);
        if (business == null || user == null) {
            return Optional.empty();
        }
        String subject = subjectHeader == null || subjectHeader.isBlank() ? null : subjectHeader.trim();
        return Optional.of(new BusinessPrincipal(business, user, subject, epoch(authTimeHeader),
                acrHeader == null || acrHeader.isBlank() ? null : acrHeader.trim(), subject));
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
