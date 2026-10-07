package io.meridian.inbox.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.inbox.application.Caller;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Caller derivation in JWT mode: X-On-Behalf-Of-Customer is honoured only for internal service tokens. */
class CallersTest {
    static final UUID TENANT = UUID.fromString("0199a000-0000-7000-8000-0000000000e1");
    static final UUID CUSTOMER = UUID.fromString("0199a000-0000-7000-8000-00000000a101");
    static final UUID STAFF = UUID.fromString("0199a000-0000-7000-8000-00000000f101");

    final Callers callers = new Callers();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        TenantContext.clear();
    }

    @Test
    void internalRelayWithHeaderIsTheCustomer() {
        request(CUSTOMER.toString());
        token(Map.of("scope", "meridian:internal", "azp", "customer-channel-service"));
        TenantContext.set(TENANT, "svc:customer-channel-service", "customer-channel-service");
        Caller c = callers.current();
        assertThat(c.customer()).isTrue();
        assertThat(c.customerId()).isEqualTo(CUSTOMER);
        assertThat(c.userId()).isNull();
    }

    @Test
    void staffTokenCannotClaimACustomer() {
        request(CUSTOMER.toString());
        token(Map.of("scope", "engagement:write"));
        TenantContext.set(TENANT, STAFF.toString());
        assertThatThrownBy(callers::current).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("on_behalf_not_allowed"));
    }

    @Test
    void staffTokenIsStaffWithItsUserId() {
        request(null);
        token(Map.of("scope", "engagement:write"));
        TenantContext.set(TENANT, STAFF.toString());
        Caller c = callers.current();
        assertThat(c.customer()).isFalse();
        assertThat(c.userId()).isEqualTo(STAFF);
    }

    @Test
    void internalRelayWithoutHeaderIsBankSide() {
        request(null);
        token(Map.of("scope", "meridian:internal", "azp", "crm-service"));
        TenantContext.set(TENANT, "svc:crm-service", "crm-service");
        assertThat(callers.current().customer()).isFalse();
    }

    @Test
    void customerTokenIsBoundToItsClaim() {
        request(null);
        token(Map.of("scope", "openid", "customer_id", CUSTOMER.toString()));
        TenantContext.set(TENANT, "some-subject");
        assertThat(callers.current().customerId()).isEqualTo(CUSTOMER);

        request(UUID.randomUUID().toString());
        assertThatThrownBy(callers::current).isInstanceOf(ApiException.class);
    }

    private static void request(String onBehalf) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (onBehalf != null) {
            req.addHeader(Callers.ON_BEHALF_HEADER, onBehalf);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private static void token(Map<String, Object> claims) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("sub")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(b::claim);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(b.build()));
    }
}
