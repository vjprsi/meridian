package io.meridian.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.MeridianProperties;
import io.meridian.web.security.SecurityProperties;
import io.meridian.web.tenancy.TenantFilter;
import io.meridian.workflow.application.ApprovalOwners;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * GAP-16 / SEC-03: in jwt mode the owner of an approval request type is proven by the caller's per-service client
 * ({@code azp = svc-<service>}), not by the X-Meridian-Service header: a service cannot open requests for another
 * service's types by naming it, and the shared client's header is believed only while the migration flag lists it.
 */
class ApprovalOwnersServiceIdentityTest {
    static final String TENANT = "0199a000-0000-7000-8000-00000000000a";
    final ApprovalOwners owners = new ApprovalOwners(Map.of("overdraft_facility", "deposit-service",
            "loan_application", "loan-origination-service"), Set.of("generic_request"));
    final TenantFilter jwtFilter = new TenantFilter(new MeridianProperties("jwt", "tenant_id"));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theOwningServiceIsProvenByItsOwnClient() throws Exception {
        assertThat(open(jwtFilter, token("svc-deposit-service", "meridian:internal platform:write"), "deposit-service",
                "overdraft_facility")).isEqualTo("deposit-service");
        assertThat(open(jwtFilter, token("svc-loan-origination-service", "meridian:internal platform:write"),
                "loan-origination-service", "loan_application")).isEqualTo("loan-origination-service");
    }

    @Test
    void anotherServiceCannotClaimTheOwnersIdentityWithTheHeader() {
        assertThatThrownBy(() -> open(jwtFilter, token("svc-loan-service", "meridian:internal platform:write"),
                "deposit-service", "overdraft_facility"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("not_resource_owner");
                    assertThat(e.getMessage()).contains("not loan-service");
                });
    }

    @Test
    void theSharedClientsHeaderCountsOnlyWhileTheMigrationFlagListsIt() throws Exception {
        Jwt shared = token("meridian-service", "meridian:internal platform:write");
        assertThatThrownBy(() -> open(jwtFilter, shared, "deposit-service", "overdraft_facility"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("service_owned_resource_type"));
        TenantFilter migrating = new TenantFilter(new MeridianProperties("jwt", "tenant_id"),
                new SecurityProperties(null, null, null, List.of("meridian-service"), null));
        assertThat(open(migrating, shared, "deposit-service", "overdraft_facility")).isEqualTo("deposit-service");
    }

    @Test
    void userTokensNeverOpenServiceOwnedTypes() {
        assertThatThrownBy(() -> open(jwtFilter, token("meridian-console", "platform:write"), "deposit-service",
                "overdraft_facility"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("service_owned_resource_type"));
    }

    /** Runs the request through the filter and authorises the type with the calling service it bound. */
    private String open(TenantFilter filter, Jwt jwt, String serviceHeader, String type) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/approval-requests");
        req.addHeader(TenantFilter.TENANT_HEADER, TENANT);
        req.addHeader(TenantFilter.USER_HEADER, "ops.maker");
        req.addHeader(TenantFilter.SERVICE_HEADER, serviceHeader);
        AtomicReference<Object> outcome = new AtomicReference<>();
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> {
            try {
                outcome.set(owners.authorize(type, TenantContext.callingService()));
            } catch (ApiException e) {
                outcome.set(e);
            }
        });
        if (outcome.get() instanceof ApiException e) {
            throw e;
        }
        return (String) outcome.get();
    }

    private static Jwt token(String azp, String scope) {
        return Jwt.withTokenValue("t").header("alg", "none").subject("user-1").claim("azp", azp).claim("scope", scope)
                .claim("tenant_id", TENANT).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
