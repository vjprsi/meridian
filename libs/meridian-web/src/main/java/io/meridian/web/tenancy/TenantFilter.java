package io.meridian.web.tenancy;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.MeridianProperties;
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
 */
public class TenantFilter extends OncePerRequestFilter {
    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String USER_HEADER = "X-User";

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
            }
        } else if (props.devMode()) {
            tenant = parse(req.getHeader(TENANT_HEADER));
            String user = req.getHeader(USER_HEADER);
            principal = user == null || user.isBlank() ? "dev-user" : user;
        }
        try {
            if (tenant != null) {
                TenantContext.set(tenant, principal, via);
            }
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
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
