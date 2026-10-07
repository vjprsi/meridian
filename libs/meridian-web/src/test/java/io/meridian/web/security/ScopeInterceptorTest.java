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
        assertThat(call(jwt("meridian-console", "meridian:admin"), "paymentsCreate")).isTrue();
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
                .claim("scope", "platform:operator").claim("tenant_id", operatorTenant)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(call(op, operatorUser, "tenantsList")).isTrue();
        assertThat(call(op, jwt("meridian-service", "platform:operator meridian:internal"), "tenantsList")).isTrue();
        // without a configured operator tenant only service tokens qualify
        assertThatThrownBy(() -> call(interceptor, operatorUser, "tenantsList"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("operator_tenant_required"));
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
        staff.add("channels:*");
        for (String need : needs) {
            boolean customerOp = need.equals(CustomerContext.CUSTOMER_SCOPE);
            boolean channelOp = customerOp || need.equals(AgentContext.AGENT_SCOPE)
                    || need.equals(BusinessContext.BUSINESS_SCOPE);
            assertThat(ScopeInterceptor.permits(customer, need)).as("customer token on %s", need).isEqualTo(customerOp);
            assertThat(ScopeInterceptor.permits(staff, need)).as("staff token on %s", need).isEqualTo(!channelOp);
        }
    }

    private boolean call(Jwt jwt, String method) throws Exception {
        return call(interceptor, jwt, method);
    }

    private static boolean call(ScopeInterceptor interceptor, Jwt jwt, String method) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        return interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new HandlerMethod(new Endpoints(), Endpoints.class.getMethod(method)));
    }

    static Jwt jwt(String azp, String scope) {
        return Jwt.withTokenValue("t").header("alg", "none").subject("user-1").claim("azp", azp)
                .claim("scope", scope).claim("tenant_id", TENANT).claim("customer_id", CUSTOMER)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
