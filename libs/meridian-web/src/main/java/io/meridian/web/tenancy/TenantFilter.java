package io.meridian.web.tenancy;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.MeridianProperties;
import io.meridian.web.security.AgentContext;
import io.meridian.web.security.AgentContext.AgentPrincipal;
import io.meridian.web.security.BusinessContext;
import io.meridian.web.security.BusinessContext.BusinessPrincipal;
import io.meridian.web.security.CustomerContext;
import io.meridian.web.security.CustomerContext.CustomerPrincipal;
import io.meridian.web.security.MerchantContext;
import io.meridian.web.security.MerchantContext.MerchantPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the request's tenant to {@link TenantContext}. In JWT mode the tenant comes only from the token's
 * tenant claim; in dev mode from the {@code X-Tenant-Id} header. Runs after Spring Security.
 *
 * <p>Also binds {@link CustomerContext} for customer tokens (scope {@code channels:customer} with a
 * {@code customer_id} claim; dev mode: {@code X-Customer-Id}). The principal of a customer request is
 * {@code customer:<id>}. Likewise {@link AgentContext} (agent tokens) and {@link BusinessContext} (business portal tokens:
 * scope {@code channels:business} with business_customer_id and business_user_id claims; dev mode
 * {@code X-Business-Customer-Id} / {@code X-Business-User-Id}) and {@link MerchantContext} (merchant app tokens: scope
 * {@code channels:merchant} with merchant_id and merchant_user_id claims; dev mode {@code X-Merchant-Id} /
 * {@code X-Merchant-User-Id}); at most one of the four is bound, never for an internal token.
 */
public class TenantFilter extends OncePerRequestFilter {
    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String USER_HEADER = "X-User";
    /** Name of the calling Meridian service; honoured only with an internal token (and in dev mode). */
    public static final String SERVICE_HEADER = "X-Meridian-Service";

    private final MeridianProperties props;

    public TenantFilter(MeridianProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        UUID tenant = null;
        String principal = "anonymous";
        String via = null;
        String callingService = null;
        CustomerPrincipal customer = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String claim = jwt.getClaimAsString(props.tenantClaim());
            tenant = parse(claim);
            principal = jwt.getSubject();
            // Internal service tokens act for the tenant named in X-Tenant-Id (scheduled jobs, callbacks).
            // User tokens can never choose their tenant: the claim always wins for them.
            if (isInternal(jwt) && parse(req.getHeader(TENANT_HEADER)) != null) {
                tenant = parse(req.getHeader(TENANT_HEADER));
                // The principal is the human the service acts for (so maker != checker compares people, not
                // "svc:x/anna" vs "anna"); without one it is the service itself. The relaying service is kept apart.
                String onBehalf = req.getHeader(USER_HEADER);
                via = jwt.getClaimAsString("azp");
                principal = onBehalf == null || onBehalf.isBlank() ? "svc:" + via : onBehalf;
                callingService = req.getHeader(SERVICE_HEADER);
            } else {
                customer = CustomerContext.fromJwt(jwt).orElse(null);
            }
        } else if (props.devMode()) {
            tenant = parse(req.getHeader(TENANT_HEADER));
            String user = req.getHeader(USER_HEADER);
            principal = user == null || user.isBlank() ? "dev-user" : user;
            callingService = req.getHeader(SERVICE_HEADER);
            customer = CustomerContext.fromDevHeaders(req.getHeader(CustomerContext.CUSTOMER_HEADER),
                    req.getHeader(CustomerContext.AUTH_TIME_HEADER), req.getHeader(CustomerContext.ACR_HEADER),
                    req.getHeader(CustomerContext.AMR_HEADER), req.getHeader(CustomerContext.SESSION_HEADER))
                    .orElse(null);
        }
        if (customer != null) {
            principal = customer.principalName();
        }
        // Agent channel: an agent token (scope channels:agent + agent_id claim; dev mode X-Agent-Id), never together
        // with a customer or an internal token.
        AgentPrincipal agent = null;
        if (customer == null && via == null) {
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                agent = AgentContext.fromJwt(jwt).orElse(null);
            } else if (props.devMode()) {
                agent = AgentContext.fromDevHeaders(req.getHeader(AgentContext.AGENT_HEADER), req.getHeader(USER_HEADER),
                        req.getHeader(CustomerContext.AUTH_TIME_HEADER), req.getHeader(CustomerContext.ACR_HEADER))
                        .orElse(null);
            }
        }
        if (agent != null) {
            principal = agent.principalName();
        }
        // Business channel: a business portal token (scope channels:business + business_customer_id + business_user_id;
        // dev mode X-Business-Customer-Id / X-Business-User-Id), never together with a customer, agent or internal token.
        BusinessPrincipal business = null;
        if (customer == null && agent == null && via == null) {
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                business = BusinessContext.fromJwt(jwt).orElse(null);
            } else if (props.devMode()) {
                business = BusinessContext.fromDevHeaders(req.getHeader(BusinessContext.BUSINESS_CUSTOMER_HEADER),
                        req.getHeader(BusinessContext.BUSINESS_USER_HEADER), req.getHeader(USER_HEADER),
                        req.getHeader(CustomerContext.AUTH_TIME_HEADER), req.getHeader(CustomerContext.ACR_HEADER),
                        req.getHeader(BusinessContext.BUSINESS_M2M_HEADER)).orElse(null);
            }
        }
        if (business != null) {
            principal = business.principalName();
        }
        // Merchant channel: a merchant app token (scope channels:merchant + merchant_id + merchant_user_id; dev mode
        // X-Merchant-Id / X-Merchant-User-Id), never together with another channel identity or an internal token.
        MerchantPrincipal merchant = null;
        if (customer == null && agent == null && business == null && via == null) {
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                merchant = MerchantContext.fromJwt(jwt).orElse(null);
            } else if (props.devMode()) {
                merchant = MerchantContext.fromDevHeaders(req.getHeader(MerchantContext.MERCHANT_HEADER),
                        req.getHeader(MerchantContext.MERCHANT_USER_HEADER), req.getHeader(USER_HEADER),
                        req.getHeader(CustomerContext.AUTH_TIME_HEADER), req.getHeader(CustomerContext.ACR_HEADER))
                        .orElse(null);
            }
        }
        if (merchant != null) {
            principal = merchant.principalName();
        }
        try {
            if (tenant != null) {
                TenantContext.set(tenant, principal, via);
                TenantContext.setCallingService(callingService);
            }
            if (customer != null) {
                CustomerContext.set(customer);
            }
            if (agent != null) {
                AgentContext.set(agent);
            }
            if (business != null) {
                BusinessContext.set(business);
            }
            if (merchant != null) {
                MerchantContext.set(merchant);
            }
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
            CustomerContext.clear();
            AgentContext.clear();
            BusinessContext.clear();
            MerchantContext.clear();
        }
    }

    static boolean isInternal(Jwt jwt) {
        Object scope = jwt.getClaims().get("scope");
        return scope instanceof String s && java.util.Arrays.asList(s.split(" ")).contains("meridian:internal");
    }

    private static UUID parse(String value) {
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
