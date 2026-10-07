package io.meridian.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.MeridianProperties;
import io.meridian.web.client.ServiceClient;
import io.meridian.web.tenancy.TenantFilter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import tools.jackson.databind.json.JsonMapper;

/**
 * Merchant channel identity: which tokens are merchant tokens (scope plus both claims), how TenantFilter binds them
 * (claims, never headers in JWT mode), exact-scope handling in ScopeInterceptor (merchant tokens refused by operator,
 * customer, agent and business endpoints; staff tokens refused by merchant endpoints even with wildcards or admin) and
 * ServiceClient never relaying a merchant user's token.
 */
class MerchantContextTest {
    static final String TENANT = "0199a000-0000-7000-8000-00000000000a";
    static final String MERCHANT = "0199a000-0000-7000-8000-0000000d0001";
    static final String USER = "0199a000-0000-7000-8000-0000000d0a01";

    final ScopeInterceptor interceptor = new ScopeInterceptor(new MeridianProperties("jwt", "tenant_id"));

    static class Endpoints {
        @RequiredScope("payments:write")
        public void paymentsCreate() {}

        @RequiredScope("meridian:admin")
        public void platformChange() {}

        @RequiredScope("channels:customer")
        public void myAccountsList() {}

        @RequiredScope("channels:agent")
        public void agentMeGet() {}

        @RequiredScope("channels:business")
        public void businessMeGet() {}

        @RequiredScope("channels:merchant")
        public void merchantAppMeGet() {}
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        MerchantContext.clear();
        BusinessContext.clear();
        AgentContext.clear();
        CustomerContext.clear();
        TenantContext.clear();
    }

    @Test
    void onlyTokensWithTheMerchantScopeAndBothClaimsAreMerchantTokens() {
        long authTime = Instant.now().minusSeconds(30).getEpochSecond();
        MerchantContext.MerchantPrincipal p = MerchantContext.fromJwt(
                token("openid channels:merchant", MERCHANT, USER, authTime)).orElseThrow();
        assertThat(p.merchantId()).isEqualTo(UUID.fromString(MERCHANT));
        assertThat(p.merchantUserId()).isEqualTo(UUID.fromString(USER));
        assertThat(p.subject()).isEqualTo("kc-user-7");
        assertThat(p.username()).isEqualTo("joe.cashier");
        assertThat(p.authTime()).isEqualTo(Instant.ofEpochSecond(authTime));
        assertThat(p.principalName()).isEqualTo("merchant-user:" + USER);

        assertThat(MerchantContext.fromJwt(token("openid channels:merchant", null, USER, null))).isEmpty();
        assertThat(MerchantContext.fromJwt(token("openid channels:merchant", MERCHANT, null, null))).isEmpty();
        assertThat(MerchantContext.fromJwt(token("openid channels:merchant", "x", USER, null))).isEmpty();
        assertThat(MerchantContext.fromJwt(token("payments:* meridian:admin", MERCHANT, USER, null))).isEmpty();
        assertThat(MerchantContext.fromJwt(token("meridian:internal channels:merchant", MERCHANT, USER, null))).isEmpty();
        assertThat(MerchantContext.fromJwt(token("channels:customer channels:merchant", MERCHANT, USER, null))).isEmpty();
        assertThat(MerchantContext.fromJwt(token("channels:agent channels:merchant", MERCHANT, USER, null))).isEmpty();
        assertThat(MerchantContext.fromJwt(token("channels:business channels:merchant", MERCHANT, USER, null))).isEmpty();
        assertThatThrownBy(MerchantContext::require).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("merchant_identity_required"));
    }

    @Test
    void tenantFilterBindsTheMerchantUserFromClaimsNeverFromHeadersInJwtMode() throws Exception {
        TenantFilter filter = new TenantFilter(new MeridianProperties("jwt", "tenant_id"));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(MerchantContext.MERCHANT_HEADER, UUID.randomUUID().toString());   // ignored in JWT mode
        req.addHeader(MerchantContext.MERCHANT_USER_HEADER, UUID.randomUUID().toString());
        req.addHeader(CustomerContext.AUTH_TIME_HEADER, String.valueOf(Instant.now().getEpochSecond()));
        req.addHeader(TenantFilter.TENANT_HEADER, UUID.randomUUID().toString());
        AtomicReference<Object[]> seen = new AtomicReference<>();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                token("openid channels:merchant", MERCHANT, USER, null)));
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> seen.set(new Object[] {
                TenantContext.current().map(UUID::toString).orElse(null), TenantContext.principal(),
                MerchantContext.current().orElse(null), CustomerContext.current().orElse(null),
                AgentContext.current().orElse(null)}));
        assertThat(seen.get()[0]).isEqualTo(TENANT);
        assertThat(seen.get()[1]).isEqualTo("merchant-user:" + USER);
        MerchantContext.MerchantPrincipal bound = (MerchantContext.MerchantPrincipal) seen.get()[2];
        assertThat(bound.merchantId()).isEqualTo(UUID.fromString(MERCHANT));
        assertThat(bound.authTime()).isNull();                                         // X-Auth-Time ignored
        assertThat(seen.get()[3]).isNull();
        assertThat(seen.get()[4]).isNull();
        assertThat(MerchantContext.current()).isEmpty();                               // cleared after the request

        // an internal service token naming a tenant is never a merchant user, whatever claims it carries
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                token("meridian:internal channels:merchant", MERCHANT, USER, null)));
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> seen.set(new Object[] {
                MerchantContext.current().orElse(null)}));
        assertThat(seen.get()[0]).isNull();
    }

    @Test
    void devModeBindsTheMerchantUserFromHeaders() throws Exception {
        TenantFilter filter = new TenantFilter(new MeridianProperties("dev", "tenant_id"));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TenantFilter.TENANT_HEADER, TENANT);
        req.addHeader(MerchantContext.MERCHANT_HEADER, MERCHANT);
        req.addHeader(MerchantContext.MERCHANT_USER_HEADER, USER);
        req.addHeader(CustomerContext.AUTH_TIME_HEADER, "1700000000");
        AtomicReference<Object[]> seen = new AtomicReference<>();
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> seen.set(new Object[] {
                MerchantContext.current().orElse(null), TenantContext.principal()}));
        MerchantContext.MerchantPrincipal p = (MerchantContext.MerchantPrincipal) seen.get()[0];
        assertThat(p.merchantUserId()).isEqualTo(UUID.fromString(USER));
        assertThat(p.authTime()).isEqualTo(Instant.ofEpochSecond(1700000000L));
        assertThat(seen.get()[1]).isEqualTo("merchant-user:" + USER);

        // an agent header wins over merchant headers: never two channel identities at once
        req.addHeader(AgentContext.AGENT_HEADER, UUID.randomUUID().toString());
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> seen.set(new Object[] {
                MerchantContext.current().orElse(null)}));
        assertThat(seen.get()[0]).isNull();

        // so do business headers
        MockHttpServletRequest both = new MockHttpServletRequest();
        both.addHeader(TenantFilter.TENANT_HEADER, TENANT);
        both.addHeader(MerchantContext.MERCHANT_HEADER, MERCHANT);
        both.addHeader(MerchantContext.MERCHANT_USER_HEADER, USER);
        both.addHeader(BusinessContext.BUSINESS_CUSTOMER_HEADER, UUID.randomUUID().toString());
        both.addHeader(BusinessContext.BUSINESS_USER_HEADER, UUID.randomUUID().toString());
        filter.doFilter(both, new MockHttpServletResponse(), (rq, rs) -> seen.set(new Object[] {
                MerchantContext.current().orElse(null), BusinessContext.current().orElse(null)}));
        assertThat(seen.get()[0]).isNull();
        assertThat(seen.get()[1]).isNotNull();
    }

    @Test
    void merchantTokensAreRefusedElsewhereAndStaffTokensByMerchantEndpoints() throws Exception {
        Jwt merchant = token("openid profile channels:merchant", MERCHANT, USER, null);
        for (String op : new String[] {"paymentsCreate", "platformChange", "myAccountsList", "agentMeGet", "businessMeGet"}) {
            assertThatThrownBy(() -> call(merchant, op)).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.status()).isEqualTo(403);
                assertThat(e.code()).isEqualTo("insufficient_scope");
            });
        }
        assertThat(call(merchant, "merchantAppMeGet")).isTrue();
        for (Jwt other : new Jwt[] {token("payments:* meridian:admin", MERCHANT, USER, null),
                token("channels:* meridian:internal", MERCHANT, USER, null), token("channels:customer", MERCHANT, USER, null),
                token("channels:agent", MERCHANT, USER, null), token("channels:business", MERCHANT, USER, null)}) {
            assertThatThrownBy(() -> call(other, "merchantAppMeGet")).isInstanceOf(ApiException.class);
        }
    }

    /** Every operation scope of the generated contract: a merchant token satisfies channels:merchant and nothing else. */
    @Test
    void merchantTokensSatisfyOnlyTheMerchantScopeOfTheGeneratedContract() throws Exception {
        Path bundle = Path.of("..", "..", "generated", "openapi", "meridian.openapi.yaml");
        Assumptions.assumeTrue(Files.exists(bundle), "generated contract not present");
        Set<String> needs = new TreeSet<>();
        Matcher m = Pattern.compile("x-required-scope: ['\"]?([a-z_-]+:[a-z*_-]+)").matcher(Files.readString(bundle));
        while (m.find()) {
            needs.add(m.group(1));
        }
        assertThat(needs).contains(MerchantContext.MERCHANT_SCOPE);
        Set<String> merchant = MerchantContext.scopes(token("openid profile email channels:merchant", MERCHANT, USER, null));
        for (String need : needs) {
            assertThat(ScopeInterceptor.permits(merchant, need)).as("merchant token on %s", need)
                    .isEqualTo(need.equals(MerchantContext.MERCHANT_SCOPE));
        }
    }

    /** A merchant user's bearer token is never relayed: service identity, tenant, merchant-user:&lt;id&gt; as the user. */
    @Test
    void merchantRequestsCallDownstreamWithTheServiceIdentityNeverTheUsersToken() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var seen = new AtomicReference<String>();
        server.createContext("/token", ex -> {
            byte[] body = "{\"access_token\":\"svc-token\",\"expires_in\":300}".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/v1/x", ex -> {
            var h = ex.getRequestHeaders();
            seen.set(h.getFirst("Authorization") + "|" + h.getFirst("X-Tenant-Id") + "|" + h.getFirst("X-User") + "|"
                    + h.getFirst("X-Roles") + "|" + h.getFirst(CustomerContext.ON_BEHALF_HEADER));
            byte[] body = "{}".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        UUID tenant = UUID.randomUUID();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            var inbound = new MockHttpServletRequest();
            inbound.addHeader("Authorization", "Bearer merchant-token");
            inbound.addHeader("X-Roles", "ADMIN");
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(inbound));
            var principal = new MerchantContext.MerchantPrincipal(UUID.fromString(MERCHANT), UUID.fromString(USER),
                    "kc-user-7", null, null, null);
            TenantContext.set(tenant, principal.principalName());
            MerchantContext.set(principal);

            MockEnvironment env = new MockEnvironment();
            env.setProperty("meridian.services.payment-service.url", base);
            assertThatThrownBy(() -> new ServiceClient(JsonMapper.builder().build(), env).get("payment-service", "/v1/x"))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.code()).isEqualTo("service_identity_not_configured"));
            assertThat(seen.get()).isNull();

            env.setProperty("meridian.service-auth.token-uri", base + "/token");
            env.setProperty("meridian.service-auth.client-id", "meridian-service");
            env.setProperty("meridian.service-auth.client-secret", "s");
            new ServiceClient(JsonMapper.builder().build(), env).get("payment-service", "/v1/x");
            assertThat(seen.get()).isEqualTo("Bearer svc-token|" + tenant + "|merchant-user:" + USER + "|null|null");
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            server.stop(0);
        }
    }

    private boolean call(Jwt jwt, String method) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        return interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new HandlerMethod(new Endpoints(), Endpoints.class.getMethod(method)));
    }

    static Jwt token(String scope, String merchantId, String userId, Long authTime) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("kc-user-7")
                .claim("azp", "meridian-merchant-app").claim("preferred_username", "joe.cashier")
                .claim("scope", scope).claim("tenant_id", TENANT)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (merchantId != null) {
            b.claim("merchant_id", merchantId);
        }
        if (userId != null) {
            b.claim("merchant_user_id", userId);
        }
        if (authTime != null) {
            b.claim("auth_time", authTime);
        }
        return b.build();
    }
}
