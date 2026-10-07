package io.meridian.customerchannel.application;

import io.meridian.common.error.ApiException;
import io.meridian.customerchannel.config.ChannelProperties;
import io.meridian.web.security.CustomerContext;
import io.meridian.web.security.CustomerContext.CustomerPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Recent-authentication check for money movement and security changes. The customer's last interactive
 * authentication (token {@code auth_time}; dev mode {@code X-Auth-Time}) must be no older than
 * {@code meridian.channels.step-up-max-age} and, when {@code meridian.channels.step-up-acr} is set, the token's
 * {@code acr} must be one of those values. Otherwise 401 {@code step_up_required} with
 * {@code WWW-Authenticate: Bearer error="insufficient_user_authentication", max_age=<seconds>} (RFC 9470): the app
 * re-authenticates (OIDC {@code max_age}) and retries with the same Idempotency-Key.
 */
@Component
public class StepUp {
    private final ChannelProperties props;
    private final ActionAudit audit;
    private final Clock clock;

    public StepUp(ChannelProperties props, ActionAudit audit, Optional<Clock> clock) {
        this.props = props;
        this.audit = audit;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    public void require(String action, String objectType, Object objectId) {
        CustomerPrincipal customer = CustomerContext.require();
        Duration max = props.stepUpMaxAge();
        Optional<Duration> age = customer.authAge(clock.instant());
        boolean recent = age.isPresent() && !age.get().isNegative() && age.get().compareTo(max) <= 0;
        boolean strong = props.stepUpAcr().isEmpty() || props.stepUpAcr().contains(customer.acr());
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
}
