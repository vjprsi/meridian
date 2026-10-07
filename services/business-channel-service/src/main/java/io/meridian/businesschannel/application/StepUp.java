package io.meridian.businesschannel.application;

import io.meridian.businesschannel.config.BusinessChannelProperties;
import io.meridian.common.error.ApiException;
import io.meridian.web.security.BusinessContext;
import io.meridian.web.security.BusinessContext.BusinessPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Recent-authentication check for approvals, money movement and administration. The user's last interactive
 * authentication (token {@code auth_time}; dev mode {@code X-Auth-Time}) must be no older than
 * {@code meridian.business.step-up-max-age} and, when {@code meridian.business.step-up-acr} is set, the token's
 * {@code acr} must be one of those values. Otherwise 401 {@code step_up_required} with
 * {@code WWW-Authenticate: Bearer error="insufficient_user_authentication", max_age=<seconds>} (RFC 9470): the portal
 * re-authenticates (OIDC {@code max_age}) and retries with the same Idempotency-Key. Checked after authorization (an
 * unentitled user is refused, not asked to re-authenticate) and before anything is changed.
 */
@Component
public class StepUp {
    private final BusinessChannelProperties props;
    private final ActionAudit audit;
    private final Clock clock;

    public StepUp(BusinessChannelProperties props, ActionAudit audit, Optional<Clock> clock) {
        this.props = props;
        this.audit = audit;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    public void require(String action, String objectType, Object objectId) {
        BusinessPrincipal user = BusinessContext.require();
        Duration max = props.stepUpMaxAge();
        Optional<Duration> age = user.authAge(clock.instant());
        boolean recent = age.isPresent() && !age.get().isNegative() && age.get().compareTo(max) <= 0;
        // a machine-to-machine token authenticates the client at issue (no user acr): only its age counts
        boolean strong = user.m2m() || props.stepUpAcr().isEmpty() || props.stepUpAcr().contains(user.acr());
        if (recent && strong) {
            return;
        }
        audit.stepUpRequired(action, objectType, objectId);
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a && a.getResponse() != null) {
            a.getResponse().setHeader("WWW-Authenticate", "Bearer error=\"insufficient_user_authentication\", "
                    + "error_description=\"A recent authentication is required\", max_age=" + max.toSeconds());
        }
        throw new ApiException(401, "step_up_required", !strong
                ? "This action needs a stronger authentication; sign in again with a second factor"
                : "This action needs a recent authentication (within " + max.toSeconds() + " s); sign in again and retry");
    }

    /** Seconds since the user's last authentication (recorded on approvals), or null. */
    public Integer authAgeSeconds() {
        return BusinessContext.current().flatMap(u -> u.authAge(clock.instant()))
                .map(d -> (int) Math.max(0, Math.min(Integer.MAX_VALUE, d.toSeconds()))).orElse(null);
    }
}
