package io.meridian.web.security;

import io.meridian.common.error.ApiException;
import io.meridian.web.MeridianProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiredScope} (found on the generated interface method) against the token's scopes.
 * Accepts the exact scope, the domain wildcard {@code <domain>:*}, {@code <domain>:write} for reads, or
 * {@code meridian:admin}. The channel scopes (customer {@link CustomerContext#CUSTOMER_SCOPE}, agent
 * {@link AgentContext#AGENT_SCOPE}) are only satisfied by themselves.
 * Dev mode (no tokens) skips the check.
 */
public class ScopeInterceptor implements HandlerInterceptor {
    private final MeridianProperties props;

    public ScopeInterceptor(MeridianProperties props) {
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (props.devMode() || !(handler instanceof HandlerMethod hm)) {
            return true;
        }
        RequiredScope required = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), RequiredScope.class);
        if (required == null) {
            return true;
        }
        String need = required.value();
        if (!permits(scopes(SecurityContextHolder.getContext().getAuthentication()), need)) {
            throw new ApiException(403, "insufficient_scope", "This operation requires scope " + need);
        }
        return true;
    }

    /**
     * Whether granted scopes satisfy {@code need}. Customer endpoints take customer tokens only (no wildcard / admin
     * shortcut); every other endpoint needs a domain scope, which customer tokens never carry, so they are refused.
     */
    static boolean permits(Set<String> granted, String need) {
        if (CustomerContext.CUSTOMER_SCOPE.equals(need) || AgentContext.AGENT_SCOPE.equals(need)) {
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
}
