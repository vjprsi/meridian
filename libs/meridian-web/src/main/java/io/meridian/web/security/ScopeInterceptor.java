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
 *       business {@link BusinessContext#BUSINESS_SCOPE}, merchant {@link MerchantContext#MERCHANT_SCOPE});</li>
 *   <li>{@value #OPERATOR_SCOPE} (SEC-15: platform-global writes, the tenant registry, cross-tenant operations), which
 *       additionally requires an internal service token or a user token of the configured platform-operator tenant
 *       ({@code meridian.security.platform-operator-tenant}); {@code meridian:admin} of a bank never satisfies it.</li>
 *   <li>{@value #ROUTING_READ_SCOPE} (the cell router's own client: tenant placement / routing reads only), under the
 *       same internal-or-operator-tenant rule.</li>
 * </ul>
 * {@link RequiredScope#anyOf()} lists scopes accepted instead of the required one; any one of them that is satisfied
 * (under its own rules) admits the request.
 * GAP-13: access that rests on {@value #ADMIN_SCOPE} (the admin shortcut) or {@value #OPERATOR_SCOPE} needs a user token
 * of at least {@code meridian.security.privileged-acr} (default 2: the staff browser flow's level 2, a second factor
 * within its max age); a weaker token gets 401 {@code insufficient_user_authentication} with
 * {@code WWW-Authenticate: Bearer error="insufficient_user_authentication", acr_values="2"} (RFC 9470), so the console
 * re-authorises with {@code acr_values=2}. Internal service tokens are exempt.
 * SEC-29: a {@code /v1} handler without {@link RequiredScope} is refused unless it is marked {@link PublicEndpoint}
 * or the request matches a spec-{@code [public]} route. Dev mode (no tokens) skips the check.
 */
public class ScopeInterceptor implements HandlerInterceptor {
    /** Platform operator scope (cell / tenant registry, global reference data, cross-tenant jobs). */
    public static final String OPERATOR_SCOPE = "platform:operator";
    /** Narrow scope of the cell router (registry routing reads only); never implied by a wildcard or admin scope. */
    public static final String ROUTING_READ_SCOPE = "platform:routing-read";
    /** The tenant superuser scope (satisfies every domain scope of its tenant). */
    public static final String ADMIN_SCOPE = "meridian:admin";

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
        Set<String> granted = scopes(auth);
        boolean operatorDenied = false;
        boolean stepUpNeeded = false;
        for (String candidate : candidates(required)) {
            if (!permits(granted, candidate)) {
                continue;
            }
            if (operatorGrade(candidate) && !operator(auth)) {
                operatorDenied = true;
                continue;
            }
            if (privilegedOnly(granted, candidate) && !strongEnough(auth, granted)) {
                stepUpNeeded = true;
                continue;
            }
            return true;
        }
        if (stepUpNeeded && !operatorDenied) {
            res.setHeader("WWW-Authenticate", "Bearer error=\"insufficient_user_authentication\", error_description=\""
                    + "A stronger authentication is required\", acr_values=\"" + security.privilegedAcr() + "\"");
            throw new ApiException(401, "insufficient_user_authentication", "This operation uses a privileged scope ("
                    + ADMIN_SCOPE + " / " + OPERATOR_SCOPE + ") and needs authentication level (acr) "
                    + security.privilegedAcr() + ": sign in again with acr_values=" + security.privilegedAcr());
        }
        if (operatorDenied) {
            throw new ApiException(403, "operator_tenant_required",
                    "Platform operator operations are reserved to the platform operator tenant");
        }
        throw new ApiException(403, "insufficient_scope", "This operation requires scope " + need
                + (required.anyOf().length == 0 ? "" : " (or " + String.join(", ", required.anyOf()) + ")"));
    }

    /** The required scope followed by the scopes accepted instead. */
    static List<String> candidates(RequiredScope required) {
        List<String> out = new java.util.ArrayList<>();
        out.add(required.value());
        out.addAll(List.of(required.anyOf()));
        return out;
    }

    /** True when only a privileged scope (admin shortcut, operator) grants {@code need}. */
    static boolean privilegedOnly(Set<String> granted, String need) {
        if (OPERATOR_SCOPE.equals(need) || ADMIN_SCOPE.equals(need)) {
            return true;
        }
        if (operatorGrade(need) || !granted.contains(ADMIN_SCOPE)) {
            return false;
        }
        Set<String> withoutAdmin = new HashSet<>(granted);
        withoutAdmin.remove(ADMIN_SCOPE);
        return !permits(withoutAdmin, need);
    }

    /** GAP-13: internal service tokens, or user tokens whose acr reaches {@code meridian.security.privileged-acr}. */
    private boolean strongEnough(Authentication auth, Set<String> granted) {
        if (security.privilegedAcr() <= 0 || granted.contains("meridian:internal")) {
            return true;
        }
        if (!(auth != null && auth.getPrincipal() instanceof Jwt jwt)) {
            return false;
        }
        return acrLevel(jwt.getClaimAsString("acr")) >= security.privilegedAcr();
    }

    /** Numeric level of an acr claim ("1", "2"; anything else counts as 0). */
    static int acrLevel(String acr) {
        if (acr == null) {
            return 0;
        }
        try {
            return Integer.parseInt(acr.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Scopes honoured only for internal service tokens and tokens of the platform-operator tenant. */
    static boolean operatorGrade(String scope) {
        return OPERATOR_SCOPE.equals(scope) || ROUTING_READ_SCOPE.equals(scope);
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
                || BusinessContext.BUSINESS_SCOPE.equals(need) || MerchantContext.MERCHANT_SCOPE.equals(need)
                || operatorGrade(need)) {
            return granted.contains(need);
        }
        String domain = need.substring(0, need.indexOf(':'));
        return granted.contains(need) || granted.contains(domain + ":*") || granted.contains(ADMIN_SCOPE)
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
