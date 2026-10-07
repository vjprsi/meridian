package io.meridian.web.security;

import io.meridian.common.error.ApiException;
import io.meridian.web.MeridianProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiredScope} (found on the generated interface method) against the token's scopes.
 * Accepts the exact scope, the domain wildcard {@code <domain>:*}, {@code <domain>:write} for reads, or
 * {@code meridian:admin} (the tenant's superuser). Some scopes are only satisfied by themselves:
 * <ul>
 *   <li>the channel scopes (customer {@link CustomerContext#CUSTOMER_SCOPE}, agent {@link AgentContext#AGENT_SCOPE},
 *       business {@link BusinessContext#BUSINESS_SCOPE});</li>
 *   <li>{@value #OPERATOR_SCOPE} (SEC-15: platform-global writes, the tenant registry, cross-tenant operations), which
 *       additionally requires an internal service token or a user token of the configured platform-operator tenant
 *       ({@code meridian.security.platform-operator-tenant}); {@code meridian:admin} of a bank never satisfies it.</li>
 * </ul>
 * SEC-29: a {@code /v1} handler without {@link RequiredScope} is refused unless it is marked {@link PublicEndpoint}
 * or the request matches a spec-{@code [public]} route. Dev mode (no tokens) skips the check.
 */
public class ScopeInterceptor implements HandlerInterceptor {
    /** Platform operator scope (cell / tenant registry, global reference data, cross-tenant jobs). */
    public static final String OPERATOR_SCOPE = "platform:operator";

    private final MeridianProperties props;
    private final SecurityProperties security;
    private final Predicate<HttpServletRequest> publicRoute;

    public ScopeInterceptor(MeridianProperties props) {
        this(props, SecurityProperties.defaults(), r -> false);
    }

    public ScopeInterceptor(MeridianProperties props, SecurityProperties security, Predicate<HttpServletRequest> publicRoute) {
        this.props = props;
        this.security = security;
        this.publicRoute = publicRoute;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (props.devMode() || !(handler instanceof HandlerMethod hm)) {
            return true;
        }
        RequiredScope required = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), RequiredScope.class);
        if (required == null) {
            if (AnnotatedElementUtils.hasAnnotation(hm.getMethod(), PublicEndpoint.class)
                    || AnnotatedElementUtils.hasAnnotation(hm.getBeanType(), PublicEndpoint.class)
                    || publicRoute.test(req)) {
                return true;
            }
            throw new ApiException(403, "insufficient_scope", "This operation declares no scope and is not public");
        }
        String need = required.value();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!permits(scopes(auth), need)) {
            throw new ApiException(403, "insufficient_scope", "This operation requires scope " + need);
        }
        if (OPERATOR_SCOPE.equals(need) && !operator(auth)) {
            throw new ApiException(403, "operator_tenant_required",
                    "Platform operator operations are reserved to the platform operator tenant");
        }
        return true;
    }

    /** The operator scope counts for internal service tokens and for users of the platform-operator tenant only. */
    private boolean operator(Authentication auth) {
        if (!(auth != null && auth.getPrincipal() instanceof Jwt jwt)) {
            return false;
        }
        if (scopes(auth).contains("meridian:internal")) {
            return true;
        }
        String tenant = jwt.getClaimAsString(props.tenantClaim());
        return security.platformOperatorTenant() != null && security.platformOperatorTenant().equalsIgnoreCase(tenant);
    }

    /**
     * Whether granted scopes satisfy {@code need}. Customer endpoints take customer tokens only (no wildcard / admin
     * shortcut); every other endpoint needs a domain scope, which customer tokens never carry, so they are refused.
     */
    static boolean permits(Set<String> granted, String need) {
        if (CustomerContext.CUSTOMER_SCOPE.equals(need) || AgentContext.AGENT_SCOPE.equals(need)
                || BusinessContext.BUSINESS_SCOPE.equals(need) || OPERATOR_SCOPE.equals(need)) {
            return granted.contains(need);
        }
        String domain = need.substring(0, need.indexOf(':'));
        return granted.contains(need) || granted.contains(domain + ":*") || granted.contains("meridian:admin")
                || (need.endsWith(":read") && granted.contains(domain + ":write"));
    }

    static Set<String> scopes(Authentication auth) {
        Set<String> out = new HashSet<>();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            Object scope = jwt.getClaims().get("scope");
            if (scope instanceof String s) {
                out.addAll(Set.of(s.split(" ")));
            } else if (scope instanceof Collection<?> c) {
                c.forEach(x -> out.add(String.valueOf(x)));
            }
        }
        return out;
    }

    /** Request matcher over {method, ant pattern} pairs (the spec's public routes). */
    public static Predicate<HttpServletRequest> routes(List<String[]> routes) {
        org.springframework.util.AntPathMatcher ant = new org.springframework.util.AntPathMatcher();
        return req -> {
            String path = req.getRequestURI().substring(req.getContextPath() == null ? 0 : req.getContextPath().length());
            for (String[] r : routes) {
                if (r[0].equalsIgnoreCase(req.getMethod()) && ant.match(r[1], path)) {
                    return true;
                }
            }
            return false;
        };
    }
}
