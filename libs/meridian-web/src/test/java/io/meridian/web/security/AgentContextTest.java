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
 * Agent channel identity: which tokens are agent tokens, how TenantFilter binds them (claim, never headers in JWT
 * mode), exact-scope handling in ScopeInterceptor (agent tokens refused by operator and customer endpoints, staff
 * tokens refused by agent endpoints even with wildcards or admin) and ServiceClient never relaying an agent's token.
 */
class AgentContextTest {
    static final String TENANT = "0199a000-0000-7000-8000-00000000000a";
    static final String AGENT = "0199a000-0000-7000-8000-00000000a9e1";

    final ScopeInterceptor interceptor = new ScopeInterceptor(new MeridianProperties("jwt", "tenant_id"));

    static class Endpoints {
        @RequiredScope("wallets:read")
        public void agentsList() {}

        @RequiredScope("wallets:write")
        public void walletTransactionsCashIn() {}

        @RequiredScope("meridian:admin")
        public void platformChange() {}

        @RequiredScope("channels:customer")
        public void myAccountsList() {}

        @RequiredScope("channels:agent")
        public void agentMeGet() {}
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        AgentContext.clear();
        TenantContext.clear();
    }

    @Test
    void onlyTokensWithTheAgentScopeAndAnAgentClaimAreAgentTokens() {
        long authTime = Instant.now().minusSeconds(30).getEpochSecond();
        AgentContext.AgentPrincipal p = AgentContext.fromJwt(token("openid channels:agent", AGENT, authTime)).orElseThrow();
        assertThat(p.agentId()).isEqualTo(UUID.fromString(AGENT));
        assertThat(p.subject()).isEqualTo("kc-user-9");
        assertThat(p.authTime()).isEqualTo(Instant.ofEpochSecond(authTime));
        assertThat(p.principalName()).isEqualTo("agent:" + AGENT);
        assertThat(p.userId()).isEqualTo(UUID.nameUUIDFromBytes("agent-user:kc-user-9".getBytes()));

        assertThat(AgentContext.fromJwt(token("openid channels:agent", null, null))).isEmpty();          // no claim
        assertThat(AgentContext.fromJwt(token("openid channels:agent", "not-a-uuid", null))).isEmpty();
        assertThat(AgentContext.fromJwt(token("wallets:* meridian:admin", AGENT, null))).isEmpty();      // staff
        assertThat(AgentContext.fromJwt(token("meridian:internal channels:agent", AGENT, null))).isEmpty();
        assertThat(AgentContext.fromJwt(token("channels:customer channels:agent", AGENT, null))).isEmpty();
        assertThat(AgentContext.fromJwt(token("channels:agent channels:agent", AGENT, null))).isPresent(); // dup scope
        assertThatThrownBy(AgentContext::require).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("agent_identity_required"));
    }

    @Test
    void tenantFilterBindsTheAgentFromTheClaimNeverFromHeadersInJwtMode() throws Exception {
        TenantFilter filter = new TenantFilter(new MeridianProperties("jwt", "tenant_id"));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(AgentContext.AGENT_HEADER, UUID.randomUUID().toString());   // ignored in JWT mode
        req.addHeader(CustomerContext.AUTH_TIME_HEADER, String.valueOf(Instant.now().getEpochSecond()));
        req.addHeader(TenantFilter.TENANT_HEADER, UUID.randomUUID().toString());
        AtomicReference<Object[]> seen = new AtomicReference<>();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                token("openid channels:agent", AGENT, null)));
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> seen.set(new Object[] {
                TenantContext.current().map(UUID::toString).orElse(null), TenantContext.principal(),
                AgentContext.current().orElse(null), CustomerContext.current().orElse(null)}));
        assertThat(seen.get()[0]).isEqualTo(TENANT);
        assertThat(seen.get()[1]).isEqualTo("agent:" + AGENT);
        AgentContext.AgentPrincipal bound = (AgentContext.AgentPrincipal) seen.get()[2];
        assertThat(bound.agentId()).isEqualTo(UUID.fromString(AGENT));
        assertThat(bound.authTime()).isNull();                                       // X-Auth-Time ignored
        assertThat(seen.get()[3]).isNull();
        assertThat(AgentContext.current()).isEmpty();                                // cleared after the request

        // an internal service token naming a tenant is never an agent, whatever claims it carries
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                token("meridian:internal channels:agent", AGENT, null)));
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> seen.set(new Object[] {
                AgentContext.current().orElse(null)}));
        assertThat(seen.get()[0]).isNull();
    }

    @Test
    void devModeBindsTheAgentFromHeaders() throws Exception {
        TenantFilter filter = new TenantFilter(new MeridianProperties("dev", "tenant_id"));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TenantFilter.TENANT_HEADER, TENANT);
        req.addHeader(AgentContext.AGENT_HEADER, AGENT);
        req.addHeader(TenantFilter.USER_HEADER, "till.anna");
        req.addHeader(CustomerContext.AUTH_TIME_HEADER, "1700000000");
        AtomicReference<AgentContext.AgentPrincipal> seen = new AtomicReference<>();
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> seen.set(AgentContext.current().orElse(null)));
        assertThat(seen.get().agentId()).isEqualTo(UUID.fromString(AGENT));
        assertThat(seen.get().subject()).isEqualTo("till.anna");
        assertThat(seen.get().authTime()).isEqualTo(Instant.ofEpochSecond(1700000000L));
    }

    @Test
    void agentTokensAreRefusedByOperatorAndCustomerEndpointsAndStaffTokensByAgentEndpoints() throws Exception {
        Jwt agent = token("openid profile channels:agent", AGENT, null);
        for (String op : new String[] {"agentsList", "walletTransactionsCashIn", "platformChange", "myAccountsList"}) {
            assertThatThrownBy(() -> call(agent, op)).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.status()).isEqualTo(403);
                assertThat(e.code()).isEqualTo("insufficient_scope");
            });
        }
        assertThat(call(agent, "agentMeGet")).isTrue();
        for (Jwt other : new Jwt[] {token("wallets:* meridian:admin", AGENT, null),
                token("channels:* meridian:internal", AGENT, null), token("channels:customer", AGENT, null)}) {
            assertThatThrownBy(() -> call(other, "agentMeGet")).isInstanceOf(ApiException.class);
        }
    }

    /** Every operation scope of the generated contract: an agent token satisfies channels:agent and nothing else. */
    @Test
    void agentTokensSatisfyOnlyTheAgentScopeOfTheGeneratedContract() throws Exception {
        Path bundle = Path.of("..", "..", "generated", "openapi", "meridian.openapi.yaml");
        Assumptions.assumeTrue(Files.exists(bundle), "generated contract not present");
        Set<String> needs = new TreeSet<>();
        Matcher m = Pattern.compile("x-required-scope: ['\"]?([a-z_-]+:[a-z*_-]+)").matcher(Files.readString(bundle));
        while (m.find()) {
            needs.add(m.group(1));
        }
        assertThat(needs).contains(AgentContext.AGENT_SCOPE);
        Set<String> agent = AgentContext.scopes(token("openid profile email channels:agent", AGENT, null));
        for (String need : needs) {
            assertThat(ScopeInterceptor.permits(agent, need)).as("agent token on %s", need)
                    .isEqualTo(need.equals(AgentContext.AGENT_SCOPE));
        }
    }

    /** An agent's bearer token is never relayed downstream: service identity, tenant, agent:&lt;id&gt; as the user. */
    @Test
    void agentRequestsCallDownstreamWithTheServiceIdentityNeverTheAgentToken() throws Exception {
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
                    + h.getFirst("X-Roles"));
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
            inbound.addHeader("Authorization", "Bearer agent-token");
            inbound.addHeader("X-Roles", "AGENT_SUPERVISOR");
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(inbound));
            var principal = new AgentContext.AgentPrincipal(UUID.fromString(AGENT), "kc-user-9", null, null);
            TenantContext.set(tenant, principal.principalName());
            AgentContext.set(principal);

            MockEnvironment env = new MockEnvironment();
            env.setProperty("meridian.services.wallet-service.url", base);
            assertThatThrownBy(() -> new ServiceClient(JsonMapper.builder().build(), env).get("wallet-service", "/v1/x"))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.code()).isEqualTo("service_identity_not_configured"));
            assertThat(seen.get()).isNull();

            env.setProperty("meridian.service-auth.token-uri", base + "/token");
            env.setProperty("meridian.service-auth.client-id", "meridian-service");
            env.setProperty("meridian.service-auth.client-secret", "s");
            new ServiceClient(JsonMapper.builder().build(), env).get("wallet-service", "/v1/x");
            assertThat(seen.get()).isEqualTo("Bearer svc-token|" + tenant + "|agent:" + AGENT + "|null");
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

    static Jwt token(String scope, String agentId, Long authTime) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("kc-user-9").claim("azp", "meridian-agent-app")
                .claim("scope", scope).claim("tenant_id", TENANT)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (agentId != null) {
            b.claim("agent_id", agentId);
        }
        if (authTime != null) {
            b.claim("auth_time", authTime);
        }
        return b.build();
    }
}
