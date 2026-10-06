package io.meridian.workflow.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.MeridianProperties;
import io.meridian.workflow.domain.Caller;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the acting user's roles and step-up MFA state. With JWTs: roles from the {@code roles} claim (or Keycloak
 * {@code realm_access.roles}), MFA from the {@code amr} claim. In dev mode: {@code X-Roles} (comma separated) and
 * {@code X-MFA-Verified} (default true) headers.
 */
@Component
public class CallerResolver {
    static final String ROLES_HEADER = "X-Roles";
    static final String MFA_HEADER = "X-MFA-Verified";
    private static final Set<String> MFA_METHODS = Set.of("mfa", "otp", "hwk", "swk", "sms", "fpt", "face");

    private final MeridianProperties props;

    public CallerResolver(MeridianProperties props) {
        this.props = props;
    }

    public Caller current() {
        TenantContext.require();
        String principal = TenantContext.principal();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            Set<String> roles = new LinkedHashSet<>();
            addAll(roles, jwt.getClaim("roles"));
            Object realm = jwt.getClaim("realm_access");
            if (realm instanceof Map<?, ?> m) {
                addAll(roles, m.get("roles"));
            }
            List<String> amr = jwt.getClaimAsStringList("amr");
            boolean mfa = amr != null && amr.stream().anyMatch(a -> MFA_METHODS.contains(a.toLowerCase()));
            return new Caller(principal, roles, mfa);
        }
        if (props.devMode() && RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            String header = a.getRequest().getHeader(ROLES_HEADER);
            Set<String> roles = new LinkedHashSet<>();
            if (header != null) {
                Arrays.stream(header.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(roles::add);
            }
            boolean mfa = !"false".equalsIgnoreCase(a.getRequest().getHeader(MFA_HEADER));
            return new Caller(principal, roles, mfa);
        }
        return new Caller(principal, Set.of(), false);
    }

    private static void addAll(Set<String> roles, Object claim) {
        if (claim instanceof Collection<?> c) {
            c.forEach(x -> roles.add(String.valueOf(x)));
        } else if (claim instanceof String s && !s.isBlank()) {
            Arrays.stream(s.split("[ ,]")).filter(x -> !x.isBlank()).forEach(roles::add);
        }
    }
}
