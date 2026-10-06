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
 * {@code meridian:admin}. Dev mode (no tokens) skips the check.
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
        Set<String> granted = scopes(SecurityContextHolder.getContext().getAuthentication());
        String need = required.value();
        String domain = need.substring(0, need.indexOf(':'));
        boolean ok = granted.contains(need) || granted.contains(domain + ":*") || granted.contains("meridian:admin")
                || (need.endsWith(":read") && granted.contains(domain + ":write"));
        if (!ok) {
            throw new ApiException(403, "insufficient_scope", "This operation requires scope " + need);
        }
        return true;
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
