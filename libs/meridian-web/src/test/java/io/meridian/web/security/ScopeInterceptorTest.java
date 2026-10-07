package io.meridian.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import io.meridian.web.MeridianProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;

/**
 * Customer tokens (scope channels:customer + customer_id claim) against operator endpoints, and staff tokens against
 * customer endpoints. The exhaustive check walks every operation scope of the generated contract.
 */
class ScopeInterceptorTest {
    static final String TENANT = "0199a000-0000-7000-8000-00000000000a";
    static final String CUSTOMER = "0199a000-0000-7000-8000-0000000c0001";
    static final Jwt CUSTOMER_TOKEN = jwt("meridian-customer-web", "openid profile email channels:customer");

    final ScopeInterceptor interceptor = new ScopeInterceptor(new MeridianProperties("jwt", "tenant_id"));

    /** Stand-ins for generated API interface methods. */
    static class Endpoints {
        @RequiredScope("accounts:read")
        public void depositAccountsList() {}

        @RequiredScope("payments:write")
        public void paymentsCreate() {}

        @RequiredScope("meridian:admin")
        public void platformChange() {}

        @RequiredScope("channels:customer")
        public void myAccountsList() {}

        @RequiredScope("platform:operator")
        public void tenantsList() {}

        @RequiredScope(value = "platform:routing-read", anyOf = {"platform:operator"})
        public void tenantsRouting() {}

        public void handWritten() {}

        @PublicEndpoint
        public void markedPublic() {}
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void customerTokenIsRefusedByOperatorEndpointsAndAcceptedByCustomerEndpoints() throws Exception {
        for (String op : new String[] {"depositAccountsList", "paymentsCreate", "platformChange"}) {
            assertThatThrownBy(() -> call(CUSTOMER_TOKEN, op)).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.status()).isEqualTo(403);
                assertThat(e.code()).isEqualTo("insufficient_scope");
            });
        }
        assertThat(call(CUSTOMER_TOKEN, "myAccountsList")).isTrue();
    }

    @Test
    void customerEndpointsRefuseStaffTokensEvenWithWildcardsOrAdmin() throws Exception {
        for (Jwt staff : new Jwt[] {jwt("meridian-console", "accounts:* payments:* meridian:admin"),
                jwt("meridian-service", "channels:* meridian:internal")}) {
            assertThatThrownBy(() -> call(staff, "myAccountsList")).isInstanceOf(ApiException.class);
        }
        assertThat(call(jwt("meridian-console", "accounts:write"), "depositAccountsList")).isTrue();
        assertThat(call(jwt("meridian-console", "meridian:admin", "2"), "paymentsCreate")).isTrue();
    }

    /**
     * GAP-13: access resting on meridian:admin (the admin shortcut) or platform:operator needs acr 2; a password-only
     * (or acr-less) staff token gets 401 insufficient_user_authentication with the RFC 9470 challenge. Domain scopes,
     * internal service tokens and a disabled check (privileged-acr 0) are unaffected.
     */
    @Test
    void privilegedScopesNeedAuthenticationLevelTwo() throws Exception {
        for (String acr : new String[] {null, "0", "1", "silver"}) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            assertThatThrownBy(() -> call(interceptor, jwt("meridian-console", "meridian:admin", acr), "paymentsCreate", res))
                    .as("acr %s", acr).isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(401);
                        assertThat(e.code()).isEqualTo("insufficient_user_authentication");
                    });
            assertThat(res.getHeader("WWW-Authenticate")).contains("insufficient_user_authentication")
                    .contains("acr_values=\"2\"");
        }
        assertThatThrownBy(() -> call(jwt("meridian-console", "meridian:admin", "1"), "platformChange"))
                .isInstanceOf(ApiException.class);
        assertThat(call(jwt("meridian-console", "meridian:admin", "2"), "platformChange")).isTrue();
        // a domain scope that grants the operation needs no step-up, even next to meridian:admin
        assertThat(call(jwt("meridian-console", "payments:write meridian:admin", "1"), "paymentsCreate")).isTrue();
        assertThat(call(jwt("meridian-console", "accounts:read"), "depositAccountsList")).isTrue();
        // service tokens are exempt
        assertThat(call(jwt("svc-deposit-service", "meridian:admin meridian:internal"), "paymentsCreate")).isTrue();
        // check disabled
        ScopeInterceptor off = new ScopeInterceptor(new MeridianProperties("jwt", "tenant_id"),
                new SecurityProperties(null, null, null, null, 0), r -> false);
        assertThat(call(off, jwt("meridian-console", "meridian:admin"), "paymentsCreate")).isTrue();
        assertThat(ScopeInterceptor.privilegedOnly(Set.of("meridian:admin"), "payments:write")).isTrue();
        assertThat(ScopeInterceptor.privilegedOnly(Set.of("meridian:admin", "payments:*"), "payments:write")).isFalse();
    }

    @Test
    void devModeSkipsTheCheck() throws Exception {
        ScopeInterceptor dev = new ScopeInterceptor(new MeridianProperties("dev", "tenant_id"));
        assertThat(dev.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new HandlerMethod(new Endpoints(), Endpoints.class.getMethod("paymentsCreate")))).isTrue();
    }

    @Test
    void operatorScopeNeedsTheOperatorTenantOrAnInternalTokenAndIsNotImpliedByAdmin() throws Exception {
        String operatorTenant = "0199a000-0000-7000-8000-0000000000f0";
        ScopeInterceptor op = new ScopeInterceptor(new MeridianProperties("jwt", "tenant_id"),
                new SecurityProperties(null, null, operatorTenant), r -> false);
        for (Jwt notOperator : new Jwt[] {jwt("meridian-console", "meridian:admin platform:*"),
                jwt("meridian-console", "platform:operator"),          // right scope, a bank's tenant
                jwt("meridian-service", "meridian:admin")}) {
            assertThatThrownBy(() -> call(op, notOperator, "tenantsList")).isInstanceOf(ApiException.class);
        }
        Jwt operatorUser = Jwt.withTokenValue("t").header("alg", "none").subject("op-1").claim("azp", "meridian-console")
                .claim("scope", "platform:operator").claim("tenant_id", operatorTenant).claim("acr", "2")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(call(op, operatorUser, "tenantsList")).isTrue();
        assertThat(call(op, jwt("meridian-service", "platform:operator meridian:internal"), "tenantsList")).isTrue();
        // without a configured operator tenant only service tokens qualify
        assertThatThrownBy(() -> call(interceptor, operatorUser, "tenantsList"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("operator_tenant_required"));
    }

    @Test
    void routingReadIsTheRoutersNarrowScopeWithOperatorStillAccepted() throws Exception {
        String operatorTenant = "0199a000-0000-7000-8000-0000000000f0";
        ScopeInterceptor op = new ScopeInterceptor(new MeridianProperties("jwt", "tenant_id"),
                new SecurityProperties(null, null, operatorTenant), r -> false);
        Jwt router = Jwt.withTokenValue("t").header("alg", "none").subject("svc-router").claim("azp", "meridian-cell-router")
                .claim("scope", "platform:routing-read").claim("tenant_id", operatorTenant)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(call(op, router, "tenantsRouting")).isTrue();
        assertThat(call(op, jwt("meridian-service", "platform:operator meridian:internal"), "tenantsRouting")).isTrue();
        // the router's client reads routing only: no other registry operation, no domain operation
        for (String other : new String[] {"tenantsList", "paymentsCreate", "depositAccountsList", "platformChange"}) {
            assertThatThrownBy(() -> call(op, router, other)).as(other).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo("insufficient_scope"));
        }
        // never implied by a bank's admin or wildcard scopes; the scope from a bank's tenant is refused too
        assertThatThrownBy(() -> call(op, jwt("meridian-console", "meridian:admin platform:*"), "tenantsRouting"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("insufficient_scope"));
        assertThatThrownBy(() -> call(op, jwt("meridian-cell-router", "platform:routing-read"), "tenantsRouting"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("operator_tenant_required"));
        assertThat(ScopeInterceptor.permits(Set.of("platform:*", "meridian:admin"), ScopeInterceptor.ROUTING_READ_SCOPE))
                .isFalse();
    }

    @Test
    void handlersWithoutAScopeAreRefusedUnlessPublic() throws Exception {
        Jwt admin = jwt("meridian-console", "meridian:admin");
        assertThatThrownBy(() -> call(interceptor, admin, "handWritten")).isInstanceOf(ApiException.class);
        assertThat(call(interceptor, admin, "markedPublic")).isTrue();
        ScopeInterceptor withRoutes = new ScopeInterceptor(new MeridianProperties("jwt", "tenant_id"),
                SecurityProperties.defaults(), ScopeInterceptor.routes(java.util.List.<String[]>of(
                        new String[] {"POST", "/v1/payment-rails/*/inbound"})));
        SecurityContextHolder.clearContext();
        MockHttpServletRequest callback = new MockHttpServletRequest("POST", "/v1/payment-rails/abc/inbound");
        assertThat(withRoutes.preHandle(callback, new MockHttpServletResponse(),
                new HandlerMethod(new Endpoints(), Endpoints.class.getMethod("handWritten")))).isTrue();
        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/v1/payment-rails/abc/other");
        assertThatThrownBy(() -> withRoutes.preHandle(other, new MockHttpServletResponse(),
                new HandlerMethod(new Endpoints(), Endpoints.class.getMethod("handWritten")))).isInstanceOf(ApiException.class);
    }

    /**
     * Every operation scope in the generated contract: a customer token satisfies only channels:customer; a token with
     * every staff scope satisfies all the others but not channels:customer.
     */
    @Test
    void everyGeneratedOperationScopeKeepsCustomersAndStaffApart() throws Exception {
        Path bundle = Path.of("..", "..", "generated", "openapi", "meridian.openapi.yaml");
        Assumptions.assumeTrue(Files.exists(bundle), "generated contract not present");
        Set<String> needs = new TreeSet<>();
        Matcher m = Pattern.compile("x-required-scope: ['\"]?([a-z_-]+:[a-z*_-]+)").matcher(Files.readString(bundle));
        while (m.find()) {
            needs.add(m.group(1));
        }
        assertThat(needs).contains(CustomerContext.CUSTOMER_SCOPE, "accounts:read", "payments:write")
                .hasSizeGreaterThan(20);
        Set<String> customer = CustomerContext.scopes(CUSTOMER_TOKEN);
        Set<String> staff = new TreeSet<>(needs);
        staff.remove(CustomerContext.CUSTOMER_SCOPE);
        staff.remove(AgentContext.AGENT_SCOPE);
        staff.remove(BusinessContext.BUSINESS_SCOPE);
        staff.remove(MerchantContext.MERCHANT_SCOPE);
        staff.add("channels:*");
        for (String need : needs) {
            boolean customerOp = need.equals(CustomerContext.CUSTOMER_SCOPE);
            boolean channelOp = customerOp || need.equals(AgentContext.AGENT_SCOPE)
                    || need.equals(BusinessContext.BUSINESS_SCOPE) || need.equals(MerchantContext.MERCHANT_SCOPE);
            assertThat(ScopeInterceptor.permits(customer, need)).as("customer token on %s", need).isEqualTo(customerOp);
            assertThat(ScopeInterceptor.permits(staff, need)).as("staff token on %s", need).isEqualTo(!channelOp);
        }
    }

    private boolean call(Jwt jwt, String method) throws Exception {
        return call(interceptor, jwt, method);
    }

    private static boolean call(ScopeInterceptor interceptor, Jwt jwt, String method) throws Exception {
        return call(interceptor, jwt, method, new MockHttpServletResponse());
    }

    private static boolean call(ScopeInterceptor interceptor, Jwt jwt, String method, MockHttpServletResponse res)
            throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        return interceptor.preHandle(new MockHttpServletRequest(), res,
                new HandlerMethod(new Endpoints(), Endpoints.class.getMethod(method)));
    }

    static Jwt jwt(String azp, String scope, String acr) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("user-1").claim("azp", azp)
                .claim("scope", scope).claim("tenant_id", TENANT).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        return acr == null ? b.build() : b.claim("acr", acr).build();
    }

    static Jwt jwt(String azp, String scope) {
        return Jwt.withTokenValue("t").header("alg", "none").subject("user-1").claim("azp", azp)
                .claim("scope", scope).claim("tenant_id", TENANT).claim("customer_id", CUSTOMER)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
