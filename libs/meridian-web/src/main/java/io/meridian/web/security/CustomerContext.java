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
 * The signed-in customer of the current request (customer channel only).
 *
 * <p>A request is a customer request only when its access token carries the customer scope
 * ({@value #CUSTOMER_SCOPE}) <em>and</em> a {@value #CUSTOMER_CLAIM} claim (a UUID). Customer tokens are issued to
 * customer clients only (Keycloak {@code meridian-customer-web}); they carry no domain scope, so every operator
 * endpoint refuses them ({@link ScopeInterceptor}). In dev security mode the customer comes from the
 * {@value #CUSTOMER_HEADER} header and the last authentication time from {@value #AUTH_TIME_HEADER} (epoch
 * seconds); both headers are ignored in JWT mode.
 *
 * <p>Bound by {@link io.meridian.web.tenancy.TenantFilter} for the duration of the request and cleared afterwards.
 * {@link io.meridian.web.client.ServiceClient} never forwards a customer's token: calls made while a customer is
 * bound use the service identity with the tenant (and {@code customer:<id>} as the acting user).
 */
public final class CustomerContext {
    public static final String CUSTOMER_SCOPE = "channels:customer";
    public static final String CUSTOMER_CLAIM = "customer_id";
    public static final String CUSTOMER_HEADER = "X-Customer-Id";
    public static final String AUTH_TIME_HEADER = "X-Auth-Time";
    public static final String ACR_HEADER = "X-Acr";
    /**
     * Sent by {@link io.meridian.web.client.ServiceClient} on calls made for a customer: the customer the calling
     * service acts for. Receivers may honour it only together with an internal service token.
     */
    public static final String ON_BEHALF_HEADER = "X-On-Behalf-Of-Customer";

    /**
     * The customer and the strength and age of their authentication.
     *
     * @param customerId customer id (customer-service)
     * @param authTime   time of the last interactive authentication ({@code auth_time}); null if unknown
     * @param acr        authentication context class ({@code acr}); null if absent
     */
    public record CustomerPrincipal(UUID customerId, Instant authTime, String acr) {
        /** Age of the last authentication, empty when the token does not say when it happened. */
        public Optional<Duration> authAge(Instant now) {
            return authTime == null ? Optional.empty() : Optional.of(Duration.between(authTime, now));
        }

        /** Principal name used for audit columns downstream ({@code created_by}). */
        public String principalName() {
            return "customer:" + customerId;
        }
    }

    private static final ThreadLocal<CustomerPrincipal> CURRENT = new ThreadLocal<>();

    private CustomerContext() {}

    public static Optional<CustomerPrincipal> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The signed-in customer; 403 {@code customer_identity_required} for any other caller (staff, services). */
    public static CustomerPrincipal require() {
        CustomerPrincipal p = CURRENT.get();
        if (p == null) {
            throw new ApiException(403, "customer_identity_required",
                    "This operation is for signed-in customers only (token with scope " + CUSTOMER_SCOPE
                            + " and a " + CUSTOMER_CLAIM + " claim)");
        }
        return p;
    }

    /** Shortcut for {@code require().customerId()}. */
    public static UUID customerId() {
        return require().customerId();
    }

    /** Web layer only (TenantFilter). */
    public static void set(CustomerPrincipal principal) {
        CURRENT.set(principal);
    }

    /** Web layer only (TenantFilter). */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * The customer a token identifies, or empty when it is not a customer token (no customer scope, no or malformed
     * customer_id claim, or a service token with {@code meridian:internal}).
     */
    public static Optional<CustomerPrincipal> fromJwt(Jwt jwt) {
        Set<String> scopes = scopes(jwt);
        if (!scopes.contains(CUSTOMER_SCOPE) || scopes.contains("meridian:internal")) {
            return Optional.empty();
        }
        UUID customer = parseUuid(jwt.getClaimAsString(CUSTOMER_CLAIM));
        if (customer == null) {
            return Optional.empty();
        }
        return Optional.of(new CustomerPrincipal(customer, epoch(jwt.getClaims().get("auth_time")),
                jwt.getClaimAsString("acr")));
    }

    /** Dev security mode: customer from {@value #CUSTOMER_HEADER}, auth time from {@value #AUTH_TIME_HEADER}. */
    public static Optional<CustomerPrincipal> fromDevHeaders(String customerHeader, String authTimeHeader,
                                                             String acrHeader) {
        UUID customer = parseUuid(customerHeader);
        if (customer == null) {
            return Optional.empty();
        }
        return Optional.of(new CustomerPrincipal(customer, epoch(authTimeHeader),
                acrHeader == null || acrHeader.isBlank() ? null : acrHeader.trim()));
    }

    static Set<String> scopes(Jwt jwt) {
        Object scope = jwt.getClaims().get("scope");
        if (scope instanceof String s) {
            return Set.of(s.trim().split("\\s+"));
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
