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
 * Business channel identity: which tokens are business tokens (scope plus both claims), how TenantFilter binds them
 * (claims, never headers in JWT mode), exact-scope handling in ScopeInterceptor (business tokens refused by operator,
 * customer and agent endpoints; staff tokens refused by business endpoints even with wildcards or admin) and
 * ServiceClient never relaying a business user's token.
 */
class BusinessContextTest {
    static final String TENANT = "0199a000-0000-7000-8000-00000000000a";
    static final String BUSINESS = "0199a000-0000-7000-8000-0000000b0001";
    static final String USER = "0199a000-0000-7000-8000-0000000b0a01";

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
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        BusinessContext.clear();
        AgentContext.clear();
        CustomerContext.clear();
        TenantContext.clear();
    }

    @Test
    void onlyTokensWithTheBusinessScopeAndBothClaimsAreBusinessTokens() {
        long authTime = Instant.now().minusSeconds(30).getEpochSecond();
        BusinessContext.BusinessPrincipal p = BusinessContext.fromJwt(
                token("openid channels:business", BUSINESS, USER, authTime)).orElseThrow();
        assertThat(p.businessCustomerId()).isEqualTo(UUID.fromString(BUSINESS));
        assertThat(p.businessUserId()).isEqualTo(UUID.fromString(USER));
        assertThat(p.subject()).isEqualTo("kc-user-7");
        assertThat(p.username()).isEqualTo("anna.finance");
        assertThat(p.authTime()).isEqualTo(Instant.ofEpochSecond(authTime));
        assertThat(p.principalName()).isEqualTo("business-user:" + USER);

        assertThat(BusinessContext.fromJwt(token("openid channels:business", null, USER, null))).isEmpty();
        assertThat(BusinessContext.fromJwt(token("openid channels:business", BUSINESS, null, null))).isEmpty();
        assertThat(BusinessContext.fromJwt(token("openid channels:business", "x", USER, null))).isEmpty();
        assertThat(BusinessContext.fromJwt(token("payments:* meridian:admin", BUSINESS, USER, null))).isEmpty();
        assertThat(BusinessContext.fromJwt(token("meridian:internal channels:business", BUSINESS, USER, null))).isEmpty();
        assertThat(BusinessContext.fromJwt(token("channels:customer channels:business", BUSINESS, USER, null))).isEmpty();
        assertThat(BusinessContext.fromJwt(token("channels:agent channels:business", BUSINESS, USER, null))).isEmpty();
        assertThatThrownBy(BusinessContext::require).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("business_identity_required"));
    }

    @Test
    void machineToMachineTokensCarryTheMarkerTheClientAndTheirIssueTimeAsAuthentication() {
        Instant issued = Instant.now().minusSeconds(20).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Jwt m2m = Jwt.withTokenValue("t").header("alg", "none").subject("service-account-cli_1")
                .claim("azp", "cli_1").claim("scope", "channels:business").claim("tenant_id", TENANT)
                .claim("business_customer_id", BUSINESS).claim("business_user_id", USER).claim("m2m", true)
                .issuedAt(issued).expiresAt(Instant.now().plusSeconds(60)).build();
        BusinessContext.BusinessPrincipal p = BusinessContext.fromJwt(m2m).orElseThrow();
        assertThat(p.m2m()).isTrue();
        assertThat(p.clientId()).isEqualTo("cli_1");
        assertThat(p.authTime()).isEqualTo(issued);
        // a portal token is never m2m, whatever auth_time says; the marker as text counts too
        BusinessContext.BusinessPrincipal portal = BusinessContext.fromJwt(
                token("openid channels:business", BUSINESS, USER, issued.getEpochSecond())).orElseThrow();
        assertThat(portal.m2m()).isFalse();
        assertThat(portal.clientId()).isEqualTo("meridian-business-portal");
        assertThat(BusinessContext.fromDevHeaders(BUSINESS, USER, null, null, null, "cli_9").orElseThrow().m2m()).isTrue();
        assertThat(BusinessContext.fromDevHeaders(BUSINESS, USER, null, null, null, null).orElseThrow().m2m()).isFalse();
    }

    @Test
    void tenantFilterBindsTheBusinessUserFromClaimsNeverFromHeadersInJwtMode() throws Exception {
        TenantFilter filter = new TenantFilter(new MeridianProperties("jwt", "tenant_id"));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(BusinessContext.BUSINESS_CUSTOMER_HEADER, UUID.randomUUID().toString());   // ignored in JWT mode
        req.addHeader(BusinessContext.BUSINESS_USER_HEADER, UUID.randomUUID().toString());
        req.addHeader(CustomerContext.AUTH_TIME_HEADER, String.valueOf(Instant.now().getEpochSecond()));
        req.addHeader(TenantFilter.TENANT_HEADER, UUID.randomUUID().toString());
        AtomicReference<Object[]> seen = new AtomicReference<>();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                token("openid channels:business", BUSINESS, USER, null)));
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> seen.set(new Object[] {
                TenantContext.current().map(UUID::toString).orElse(null), TenantContext.principal(),
                BusinessContext.current().orElse(null), CustomerContext.current().orElse(null),
                AgentContext.current().orElse(null)}));
        assertThat(seen.get()[0]).isEqualTo(TENANT);
        assertThat(seen.get()[1]).isEqualTo("business-user:" + USER);
        BusinessContext.BusinessPrincipal bound = (BusinessContext.BusinessPrincipal) seen.get()[2];
        assertThat(bound.businessCustomerId()).isEqualTo(UUID.fromString(BUSINESS));
        assertThat(bound.authTime()).isNull();                                         // X-Auth-Time ignored
        assertThat(seen.get()[3]).isNull();
        assertThat(seen.get()[4]).isNull();
        assertThat(BusinessContext.current()).isEmpty();                               // cleared after the request

        // an internal service token naming a tenant is never a business user, whatever claims it carries
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                token("meridian:internal channels:business", BUSINESS, USER, null)));
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> seen.set(new Object[] {
                BusinessContext.current().orElse(null)}));
        assertThat(seen.get()[0]).isNull();
    }

    @Test
    void devModeBindsTheBusinessUserFromHeaders() throws Exception {
        TenantFilter filter = new TenantFilter(new MeridianProperties("dev", "tenant_id"));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TenantFilter.TENANT_HEADER, TENANT);
        req.addHeader(BusinessContext.BUSINESS_CUSTOMER_HEADER, BUSINESS);
        req.addHeader(BusinessContext.BUSINESS_USER_HEADER, USER);
        req.addHeader(CustomerContext.AUTH_TIME_HEADER, "1700000000");
        AtomicReference<Object[]> seen = new AtomicReference<>();
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> seen.set(new Object[] {
                BusinessContext.current().orElse(null), TenantContext.principal()}));
        BusinessContext.BusinessPrincipal p = (BusinessContext.BusinessPrincipal) seen.get()[0];
        assertThat(p.businessUserId()).isEqualTo(UUID.fromString(USER));
        assertThat(p.authTime()).isEqualTo(Instant.ofEpochSecond(1700000000L));
        assertThat(seen.get()[1]).isEqualTo("business-user:" + USER);

        // an agent header wins over business headers: never two channel identities at once
        req.addHeader(AgentContext.AGENT_HEADER, UUID.randomUUID().toString());
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> seen.set(new Object[] {
                BusinessContext.current().orElse(null)}));
        assertThat(seen.get()[0]).isNull();
    }

    @Test
    void businessTokensAreRefusedElsewhereAndStaffTokensByBusinessEndpoints() throws Exception {
        Jwt business = token("openid profile channels:business", BUSINESS, USER, null);
        for (String op : new String[] {"paymentsCreate", "platformChange", "myAccountsList", "agentMeGet"}) {
            assertThatThrownBy(() -> call(business, op)).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.status()).isEqualTo(403);
                assertThat(e.code()).isEqualTo("insufficient_scope");
            });
        }
        assertThat(call(business, "businessMeGet")).isTrue();
        for (Jwt other : new Jwt[] {token("payments:* meridian:admin", BUSINESS, USER, null),
                token("channels:* meridian:internal", BUSINESS, USER, null), token("channels:customer", BUSINESS, USER, null),
                token("channels:agent", BUSINESS, USER, null)}) {
            assertThatThrownBy(() -> call(other, "businessMeGet")).isInstanceOf(ApiException.class);
        }
    }

    /** Every operation scope of the generated contract: a business token satisfies channels:business and nothing else. */
    @Test
    void businessTokensSatisfyOnlyTheBusinessScopeOfTheGeneratedContract() throws Exception {
        Path bundle = Path.of("..", "..", "generated", "openapi", "meridian.openapi.yaml");
        Assumptions.assumeTrue(Files.exists(bundle), "generated contract not present");
        Set<String> needs = new TreeSet<>();
        Matcher m = Pattern.compile("x-required-scope: ['\"]?([a-z_-]+:[a-z*_-]+)").matcher(Files.readString(bundle));
        while (m.find()) {
            needs.add(m.group(1));
        }
        assertThat(needs).contains(BusinessContext.BUSINESS_SCOPE);
        Set<String> business = BusinessContext.scopes(token("openid profile email channels:business", BUSINESS, USER, null));
        for (String need : needs) {
            assertThat(ScopeInterceptor.permits(business, need)).as("business token on %s", need)
                    .isEqualTo(need.equals(BusinessContext.BUSINESS_SCOPE));
        }
    }

    /** A business user's bearer token is never relayed: service identity, tenant, business-user:&lt;id&gt; as the user. */
    @Test
    void businessRequestsCallDownstreamWithTheServiceIdentityNeverTheUsersToken() throws Exception {
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
            inbound.addHeader("Authorization", "Bearer business-token");
            inbound.addHeader("X-Roles", "ADMIN");
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(inbound));
            var principal = new BusinessContext.BusinessPrincipal(UUID.fromString(BUSINESS), UUID.fromString(USER),
                    "kc-user-7", null, null, null);
            TenantContext.set(tenant, principal.principalName());
            BusinessContext.set(principal);

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
            assertThat(seen.get()).isEqualTo("Bearer svc-token|" + tenant + "|business-user:" + USER + "|null|null");
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

    static Jwt token(String scope, String businessId, String userId, Long authTime) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("kc-user-7")
                .claim("azp", "meridian-business-portal").claim("preferred_username", "anna.finance")
                .claim("scope", scope).claim("tenant_id", TENANT)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (businessId != null) {
            b.claim("business_customer_id", businessId);
        }
        if (userId != null) {
            b.claim("business_user_id", userId);
        }
        if (authTime != null) {
            b.claim("auth_time", authTime);
        }
        return b.build();
    }
}
