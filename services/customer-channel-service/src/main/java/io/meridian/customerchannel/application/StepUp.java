package io.meridian.customerchannel.application;

import io.meridian.common.error.ApiException;
import io.meridian.customerchannel.config.ChannelProperties;
import io.meridian.customerchannel.config.ChannelProperties.Policy;
import io.meridian.web.security.CustomerContext;
import io.meridian.web.security.CustomerContext.CustomerPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Step-up for money movement and security changes, on the identity provider's Levels of Authentication.
 *
 * <p>The operation's policy ({@code meridian.channels.step-up}, per operation under {@code operations}) names a
 * minimum acr (default 2 = password plus a second factor), a maximum age of the authentication (token
 * {@code auth_time}; default 5 minutes) and optionally amr methods of which one must have been used. The token's acr
 * is mapped to a level with {@code meridian.channels.acr-levels} (the realm's {@code acr.loa.map}). Dev mode takes
 * the same values from {@code X-Auth-Time}, {@code X-Acr} and {@code X-Amr}.
 *
 * <p>Refusal: 401 {@code step_up_required} with an RFC 9470 challenge
 * {@code WWW-Authenticate: Bearer error="insufficient_user_authentication", error_description="...",
 * acr_values="2", max_age=300}. The app re-authenticates (OIDC {@code acr_values} and {@code max_age}) and retries
 * with the same Idempotency-Key.
 */
@Component
public class StepUp {
    private final ChannelProperties props;
    private final ActionAudit audit;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    public StepUp(ChannelProperties props, ActionAudit audit, Optional<Clock> clock,
                  io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
        this.props = props;
        this.audit = audit;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /** The policy that applies to an operation (operation id of the /v1/me endpoint). */
    public Policy policy(String action) {
        return props.stepUp().forOperation(action);
    }

    /** Whether the signed-in customer's authentication satisfies the operation's policy right now. */
    public boolean satisfied(String action) {
        return check(CustomerContext.require(), policy(action)) == null;
    }

    public void require(String action, String objectType, Object objectId) {
        Policy policy = policy(action);
        String failure = check(CustomerContext.require(), policy);
        if (failure == null) {
            return;
        }
        // GAP-10: step-up challenges per channel and cause (SLO_SLA.md login / step-up failures)
        meters.counter("meridian.auth.step_up.challenges", "channel", "customer", "reason",
                failure.startsWith("A recent") ? "stale_auth" : "weak_auth").increment();
        audit.stepUpRequired(action, objectType, objectId);
        long maxAge = policy.maxAge().toSeconds();
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a && a.getResponse() != null) {
            a.getResponse().setHeader("WWW-Authenticate", "Bearer error=\"insufficient_user_authentication\", "
                    + "error_description=\"" + failure + "\", acr_values=\"" + policy.acr() + "\", max_age=" + maxAge);
        }
        throw new ApiException(401, "step_up_required", failure + "; sign in again with acr_values=" + policy.acr()
                + " and max_age=" + maxAge + ", then retry with the same Idempotency-Key");
    }

    /** Null when the authentication satisfies the policy, else the reason (RFC 9470 error_description). */
    String check(CustomerPrincipal customer, Policy policy) {
        int required = props.level(policy.acr());
        if (props.level(customer.acr()) < required) {
            return "A stronger authentication (level " + policy.acr() + ", a second factor) is required";
        }
        if (!policy.amr().isEmpty() && customer.amr().stream().noneMatch(policy.amr()::contains)) {
            return "An authentication with one of " + String.join(", ", policy.amr()) + " is required";
        }
        Duration max = policy.maxAge();
        Optional<Duration> age = customer.authAge(clock.instant());
        boolean recent = age.isPresent() && !age.get().isNegative() && age.get().compareTo(max) <= 0;
        if (!recent) {
            return "A recent authentication (within " + max.toSeconds() + " s) is required";
        }
        return null;
    }
}
