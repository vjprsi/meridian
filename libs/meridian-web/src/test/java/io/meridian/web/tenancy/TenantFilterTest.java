package io.meridian.web.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.MeridianProperties;
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
