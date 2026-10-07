package io.meridian.merchantchannel.application;

import io.meridian.merchantchannel.config.MerchantChannelProperties;
import io.meridian.common.error.ApiException;
import io.meridian.web.security.MerchantContext;
import io.meridian.web.security.MerchantContext.MerchantPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Recent-authentication check for refund approvals, payout account changes and administration. The user's last interactive
 * authentication (token {@code auth_time}; dev mode {@code X-Auth-Time}) must be no older than
 * {@code meridian.merchant.step-up-max-age} and, when {@code meridian.merchant.step-up-acr} is set, the token's
 * {@code acr} must be one of those values. Otherwise 401 {@code step_up_required} with
 * {@code WWW-Authenticate: Bearer error="insufficient_user_authentication", max_age=<seconds>} (RFC 9470): the portal
 * re-authenticates (OIDC {@code max_age}) and retries with the same Idempotency-Key. Checked after authorization (an
 * unentitled user is refused, not asked to re-authenticate) and before anything is changed.
 */
@Component
public class StepUp {
    private final MerchantChannelProperties props;
    private final ActionAudit audit;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    public StepUp(MerchantChannelProperties props, ActionAudit audit, Optional<Clock> clock,
                  io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
        this.props = props;
        this.audit = audit;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    public void require(String action, String objectType, Object objectId) {
        MerchantPrincipal user = MerchantContext.require();
        Duration max = props.stepUpMaxAge();
        Optional<Duration> age = user.authAge(clock.instant());
        boolean recent = age.isPresent() && !age.get().isNegative() && age.get().compareTo(max) <= 0;
        boolean strong = props.stepUpAcr().isEmpty() || props.stepUpAcr().contains(user.acr());
        if (recent && strong) {
            return;
        }
        // GAP-10: step-up challenges per channel and cause (SLO_SLA.md login / step-up failures)
        meters.counter("meridian.auth.step_up.challenges", "channel", "merchant", "reason",
                !strong ? "weak_auth" : "stale_auth").increment();
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
        return MerchantContext.current().flatMap(u -> u.authAge(clock.instant()))
                .map(d -> (int) Math.max(0, Math.min(Integer.MAX_VALUE, d.toSeconds()))).orElse(null);
    }
}
