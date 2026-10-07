package io.meridian.inbox.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.inbox.application.Caller;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Derives the {@link Caller} of the current request. A customer is recognised only when
 * <ul>
 *   <li>an internal service token ({@code meridian:internal}, i.e. {@link TenantContext#via()} is set) relays the call
 *       with {@value #ON_BEHALF_HEADER} naming the customer (the customer channel service sets it); or</li>
 *   <li>the token itself carries a {@code customer_id} claim (a customer token, should one ever reach this service).</li>
 * </ul>
 * Any other authenticated principal is bank-side (staff). A non-internal token sending the header is refused (403),
 * so a staff or customer token can never pick a customer. In dev mode (no tokens) the header is taken as the relay
 * would send it.
 */
@Component
public class Callers {
    public static final String ON_BEHALF_HEADER = "X-On-Behalf-Of-Customer";
    static final String CUSTOMER_CLAIM = "customer_id";

    public Caller current() {
        String header = header(ON_BEHALF_HEADER);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String claim = jwt.getClaimAsString(CUSTOMER_CLAIM);
            if (claim != null && !claim.isBlank()) {
                UUID own = parse(claim, CUSTOMER_CLAIM);
                if (header != null && !own.equals(parse(header, ON_BEHALF_HEADER))) {
                    throw new ApiException(403, "on_behalf_not_allowed", "A customer token cannot act for another customer");
                }
                return Caller.customer(own);
            }
            if (header != null) {
                if (TenantContext.via().isEmpty()) {
                    throw new ApiException(403, "on_behalf_not_allowed",
                            ON_BEHALF_HEADER + " is honoured only for internal service tokens");
                }
                return Caller.customer(parse(header, ON_BEHALF_HEADER));
            }
            return Caller.staff(principalUuid());
        }
        if (header != null) {
            return Caller.customer(parse(header, ON_BEHALF_HEADER));
        }
        return Caller.staff(principalUuid());
    }

    private static String header(String name) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a)) {
            return null;
        }
        HttpServletRequest req = a.getRequest();
        String v = req.getHeader(name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static UUID parse(String value, String what) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("validation_failed", what + " must be a UUID");
        }
    }

    private static UUID principalUuid() {
        try {
            return UUID.fromString(TenantContext.principal());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
