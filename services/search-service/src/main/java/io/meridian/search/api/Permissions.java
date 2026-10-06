package io.meridian.search.api;

import io.meridian.web.MeridianProperties;
import java.util.Arrays;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Caller permissions relevant to search. PII is shown unmasked only to callers holding {@code pii:read}: a
 * token authority ({@code SCOPE_pii:read}, {@code ROLE_PII_READER}) or, in dev security mode only, the
 * {@code X-Permissions} request header (comma-separated).
 */
@Component
class Permissions {
    private static final Set<String> PII = Set.of("SCOPE_pii:read", "pii:read", "ROLE_PII_READER", "SCOPE_operations:pii");

    private final MeridianProperties props;

    Permissions(MeridianProperties props) {
        this.props = props;
    }

    boolean piiAllowed() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(PII::contains)) {
            return true;
        }
        if (props.devMode() && RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            String header = a.getRequest().getHeader("X-Permissions");
            return header != null && Arrays.stream(header.split(",")).map(String::trim).anyMatch(PII::contains);
        }
        return false;
    }
}
