package io.meridian.web.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.MeridianProperties;
import io.meridian.web.security.CustomerContext;
import io.meridian.web.security.CustomerContext.CustomerPrincipal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class TenantFilterTest {
    static final String HOME = "0199a000-0000-7000-8000-00000000000a";
    final TenantFilter filter = new TenantFilter(new MeridianProperties("jwt", "tenant_id"));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void serviceActingForAUserHasTheUserAsPrincipalAndTheServiceAsVia() throws Exception {
        String[] seen = run(jwt("meridian-service", "meridian:internal"), UUID.randomUUID().toString(), "ops.anna");
        assertThat(seen[1]).isEqualTo("ops.anna");      // four-eyes compares this with the maker
        assertThat(seen[2]).isEqualTo("meridian-service");
    }

    @Test
    void serviceActingAloneIsSvcPrincipalAndUserTokensCannotChooseTenantOrVia() throws Exception {
        String other = UUID.randomUUID().toString();
        String[] svc = run(jwt("meridian-service", "meridian:internal"), other, null);
        assertThat(svc).containsExactly(other, "svc:meridian-service", "meridian-service");
        String[] user = run(jwt("meridian-console", "payments:write"), other, "someone-else");
        assertThat(user).containsExactly(HOME, "user-1", null);
    }

    @Test
    void customerTokenBindsTheCustomerFromTheClaimNeverFromHeaders() throws Exception {
        String customer = "0199a000-0000-7000-8000-0000000c0001";
        long authTime = Instant.now().minusSeconds(42).getEpochSecond();
        Jwt token = Jwt.withTokenValue("t").header("alg", "none").subject("kc-user-7").claim("azp", "meridian-customer-web")
                .claim("scope", "openid profile channels:customer").claim("tenant_id", HOME)
                .claim("customer_id", customer).claim("auth_time", authTime).claim("acr", "1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(CustomerContext.CUSTOMER_HEADER, UUID.randomUUID().toString());   // ignored in JWT mode
        req.addHeader(TenantFilter.TENANT_HEADER, UUID.randomUUID().toString());       // ignored: not internal
        AtomicReference<Object[]> seen = new AtomicReference<>();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> seen.set(new Object[] {
                TenantContext.current().map(UUID::toString).orElse(null), TenantContext.principal(),
                CustomerContext.current().orElse(null)}));
        assertThat(seen.get()[0]).isEqualTo(HOME);
        assertThat(seen.get()[1]).isEqualTo("customer:" + customer);
        CustomerPrincipal p = (CustomerPrincipal) seen.get()[2];
        assertThat(p.customerId()).hasToString(customer);
        assertThat(p.authTime()).isEqualTo(Instant.ofEpochSecond(authTime));
        assertThat(p.acr()).isEqualTo("1");
        assertThat(CustomerContext.current()).isEmpty();                                // cleared after the request
    }

    @Test
    void staffAndServiceTokensAreNeverCustomersEvenWithACustomerIdClaim() throws Exception {
        for (Jwt token : new Jwt[] {jwtWithCustomer("meridian-console", "accounts:read customers:read"),
                jwtWithCustomer("meridian-service", "meridian:internal channels:customer"),
                // customer scope without a usable customer_id claim
                Jwt.withTokenValue("t").header("alg", "none").subject("u").claim("scope", "channels:customer")
                        .claim("tenant_id", HOME).claim("customer_id", "not-a-uuid").issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60)).build()}) {
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
            AtomicReference<Boolean> bound = new AtomicReference<>();
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                    (rq, rs) -> bound.set(CustomerContext.current().isPresent()));
            assertThat(bound.get()).as(token.getClaimAsString("azp")).isFalse();
        }
    }

    @Test
    void devModeTakesTheCustomerAndAuthTimeFromHeadersOnlyInDevMode() throws Exception {
        String customer = UUID.randomUUID().toString();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TenantFilter.TENANT_HEADER, HOME);
        req.addHeader(CustomerContext.CUSTOMER_HEADER, customer);
        req.addHeader(CustomerContext.AUTH_TIME_HEADER, "1790000000");
        AtomicReference<CustomerPrincipal> seen = new AtomicReference<>();
        new TenantFilter(new MeridianProperties("dev", "tenant_id")).doFilter(req, new MockHttpServletResponse(),
                (rq, rs) -> seen.set(CustomerContext.current().orElse(null)));
        assertThat(seen.get().customerId()).hasToString(customer);
        assertThat(seen.get().authTime()).isEqualTo(Instant.ofEpochSecond(1_790_000_000L));

        seen.set(null);
        filter.doFilter(req, new MockHttpServletResponse(),                               // JWT mode, no token
                (rq, rs) -> seen.set(CustomerContext.current().orElse(null)));
        assertThat(seen.get()).isNull();
    }

    @Test
    void callingServiceHeaderIsHonouredOnlyWithAnInternalToken() throws Exception {
        for (Object[] c : new Object[][] {{jwt("meridian-service", "meridian:internal"), "deposit-service"},
                {jwt("meridian-console", "ledger:write platform:write"), null}}) {
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken((Jwt) c[0]));
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader(TenantFilter.TENANT_HEADER, HOME);
            req.addHeader(TenantFilter.SERVICE_HEADER, "deposit-service");
            AtomicReference<String> seen = new AtomicReference<>("unset");
            filter.doFilter(req, new MockHttpServletResponse(),
                    (rq, rs) -> seen.set(TenantContext.callingService().orElse(null)));
            assertThat(seen.get()).isEqualTo(c[1]);
            assertThat(TenantContext.callingService()).isEmpty();                   // cleared after the request
        }
    }

    private static Jwt jwtWithCustomer(String azp, String scope) {
        return Jwt.withTokenValue("t").header("alg", "none").subject("user-1").claim("azp", azp)
                .claim("scope", scope).claim("tenant_id", HOME).claim("customer_id", UUID.randomUUID().toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    private String[] run(Jwt jwt, String tenantHeader, String userHeader) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TenantFilter.TENANT_HEADER, tenantHeader);
        if (userHeader != null) {
            req.addHeader(TenantFilter.USER_HEADER, userHeader);
        }
        AtomicReference<String[]> seen = new AtomicReference<>();
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> seen.set(new String[] {
                TenantContext.current().map(UUID::toString).orElse(null), TenantContext.principal(),
                TenantContext.via().orElse(null)}));
        assertThat(TenantContext.via()).isEmpty();      // cleared after the request
        return seen.get();
    }

    private static Jwt jwt(String azp, String scope) {
        return Jwt.withTokenValue("t").header("alg", "none").subject("user-1").claim("azp", azp)
                .claim("scope", scope).claim("tenant_id", HOME).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
