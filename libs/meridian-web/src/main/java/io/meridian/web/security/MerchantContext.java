package io.meridian.web.security;

import io.meridian.common.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The signed-in merchant app user of the current request (merchant channel only).
 *
 * <p>A request is a merchant request only when its access token carries the merchant scope ({@value #MERCHANT_SCOPE})
 * <em>and</em> both a {@value #MERCHANT_CLAIM} claim (the merchant: a merchant of merchant-service) and a
 * {@value #MERCHANT_USER_CLAIM} claim (the app user: a merchant_user of merchant-channel-service), both UUIDs. Merchant
 * tokens are issued to the merchant app client only (Keycloak {@code meridian-merchant-app}, claims from admin-only user
 * attributes); they carry no domain scope, so every operator endpoint refuses them ({@link ScopeInterceptor}). Internal
 * service tokens ({@code meridian:internal}), customer ({@value CustomerContext#CUSTOMER_SCOPE}), agent
 * ({@value AgentContext#AGENT_SCOPE}) and business ({@value BusinessContext#BUSINESS_SCOPE}) tokens are never merchant
 * requests, whatever claims they carry. In dev security mode the merchant and the user come from the
 * {@value #MERCHANT_HEADER} and {@value #MERCHANT_USER_HEADER} headers and the last authentication time from
 * {@value CustomerContext#AUTH_TIME_HEADER} (epoch seconds); these headers are ignored in JWT mode (and dropped by the
 * edge gateway).
 *
 * <p>Bound by {@link io.meridian.web.tenancy.TenantFilter} for the duration of the request and cleared afterwards.
 * {@link io.meridian.web.client.ServiceClient} never forwards a merchant user's token: calls made while a merchant user
 * is bound use the service identity with the tenant (and {@code merchant-user:<id>} as the acting user). The claims
 * only say who the caller claims to be; merchant-channel-service checks the user record (merchant, status, role, stores).
 */
public final class MerchantContext {
    public static final String MERCHANT_SCOPE = "channels:merchant";
    public static final String MERCHANT_CLAIM = "merchant_id";
    public static final String MERCHANT_USER_CLAIM = "merchant_user_id";
    public static final String MERCHANT_HEADER = "X-Merchant-Id";
    public static final String MERCHANT_USER_HEADER = "X-Merchant-User-Id";

    /**
     * The merchant, the app user and the strength and age of their authentication.
     *
     * @param merchantId     merchant id (merchant-service)
     * @param merchantUserId app user id (merchant-channel-service merchant_user)
     * @param subject        token subject (identity provider user id; dev mode: X-User); may be null
     * @param authTime       time of the last interactive authentication ({@code auth_time}); null if unknown
     * @param acr            authentication context class ({@code acr}); null if absent
     * @param username       {@code preferred_username}; may be null
     */
    public record MerchantPrincipal(UUID merchantId, UUID merchantUserId, String subject, Instant authTime, String acr,
                                    String username) {
        /** Age of the last authentication, empty when the token does not say when it happened. */
        public Optional<Duration> authAge(Instant now) {
            return authTime == null ? Optional.empty() : Optional.of(Duration.between(authTime, now));
        }

        /** Principal name used for audit columns downstream ({@code created_by}) and four-eyes comparisons. */
        public String principalName() {
            return principalName(merchantUserId);
        }

        /** Principal name of any app user (used when acting on behalf of a requester or an approver). */
        public static String principalName(UUID merchantUserId) {
            return "merchant-user:" + merchantUserId;
        }
    }

    private static final ThreadLocal<MerchantPrincipal> CURRENT = new ThreadLocal<>();

    private MerchantContext() {}

    public static Optional<MerchantPrincipal> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The signed-in merchant user; 403 {@code merchant_identity_required} for any other caller. */
    public static MerchantPrincipal require() {
        MerchantPrincipal p = CURRENT.get();
        if (p == null) {
            throw new ApiException(403, "merchant_identity_required",
                    "This operation is for signed-in merchant users only (token with scope " + MERCHANT_SCOPE
                            + " and " + MERCHANT_CLAIM + " / " + MERCHANT_USER_CLAIM + " claims)");
        }
        return p;
    }

    /** Shortcut for {@code require().merchantId()}. */
    public static UUID merchantId() {
        return require().merchantId();
    }

    /** Shortcut for {@code require().merchantUserId()}. */
    public static UUID merchantUserId() {
        return require().merchantUserId();
    }

    /** Web layer only (TenantFilter). */
    public static void set(MerchantPrincipal principal) {
        CURRENT.set(principal);
    }

    /** Web layer only (TenantFilter). */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * The merchant user a token identifies, or empty when it is not a merchant token (no merchant scope, a missing or
     * malformed claim, a service token with {@code meridian:internal}, a customer, agent or business token).
     */
    public static Optional<MerchantPrincipal> fromJwt(Jwt jwt) {
        Set<String> scopes = BusinessContext.scopes(jwt);
        if (!scopes.contains(MERCHANT_SCOPE) || scopes.contains("meridian:internal")
                || scopes.contains(CustomerContext.CUSTOMER_SCOPE) || scopes.contains(AgentContext.AGENT_SCOPE)
                || scopes.contains(BusinessContext.BUSINESS_SCOPE)) {
            return Optional.empty();
        }
        UUID merchant = parseUuid(jwt.getClaimAsString(MERCHANT_CLAIM));
        UUID user = parseUuid(jwt.getClaimAsString(MERCHANT_USER_CLAIM));
        if (merchant == null || user == null) {
            return Optional.empty();
        }
        return Optional.of(new MerchantPrincipal(merchant, user, jwt.getSubject(),
                epoch(jwt.getClaims().get("auth_time")), jwt.getClaimAsString("acr"),
                jwt.getClaimAsString("preferred_username")));
    }

    /** Dev security mode: merchant and user from the X-Merchant-* headers, auth time from X-Auth-Time. */
    public static Optional<MerchantPrincipal> fromDevHeaders(String merchantHeader, String userIdHeader,
                                                             String subjectHeader, String authTimeHeader,
                                                             String acrHeader) {
        UUID merchant = parseUuid(merchantHeader);
        UUID user = parseUuid(userIdHeader);
        if (merchant == null || user == null) {
            return Optional.empty();
        }
        String subject = subjectHeader == null || subjectHeader.isBlank() ? null : subjectHeader.trim();
        return Optional.of(new MerchantPrincipal(merchant, user, subject, epoch(authTimeHeader),
                acrHeader == null || acrHeader.isBlank() ? null : acrHeader.trim(), subject));
    }

    static Set<String> scopes(Jwt jwt) {
        return BusinessContext.scopes(jwt);
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
