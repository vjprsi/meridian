package io.meridian.agentchannel.application;

import io.meridian.agentchannel.config.AgentChannelProperties;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.security.AgentContext;
import io.meridian.web.security.AgentContext.AgentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;

/**
 * Authorisation of money movement on the agent channel. A request passes when either
 * <ol>
 *   <li>the agent's last interactive login (token {@code auth_time}; dev mode {@code X-Auth-Time}) is no older than
 *       {@code meridian.agent-channel.step-up-max-age} (RECENT_LOGIN), or</li>
 *   <li>it carries a device proof {@value #PROOF_HEADER}: {@code v1.<device id>.<epoch seconds>.<signature>}, a
 *       signature by the ACTIVE key of one of the agent's ACTIVE devices over
 *       {@link DeviceKeys#proofPayload} (tenant, agent, device, method, path, Idempotency-Key, timestamp) with the
 *       timestamp within {@code device-proof-max-age} of the server clock (DEVICE_PROOF). The app signs only while it
 *       is unlocked with the agent's app PIN, so the proof stands for "this registered device, unlocked now" and lets
 *       work captured offline sync without a fresh login.</li>
 * </ol>
 * Otherwise 401 {@code step_up_required} (or {@code device_proof_invalid}) with
 * {@code WWW-Authenticate: Bearer error="insufficient_user_authentication", max_age=<seconds>} (RFC 9470); the refusal
 * is audited. Registering a device key accepts only a recent login (a device cannot vouch for itself).
 */
@Component
public class StepUp {
    public static final String PROOF_HEADER = "X-Agent-Device-Proof";

    private final AgentChannelProperties props;
    private final ActionAudit audit;
    private final DeviceKeys keys;
    private final Ownership own;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    public StepUp(AgentChannelProperties props, ActionAudit audit, DeviceKeys keys, Ownership own,
                  Optional<Clock> clock, io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
        this.props = props;
        this.audit = audit;
        this.keys = keys;
        this.own = own;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /** Money movement: a recent login or a valid device proof. */
    public void require(String action, String objectType, Object objectId) {
        check(action, objectType, objectId, true);
    }

    /** Security changes (device registration): a recent login only. */
    public void requireLogin(String action, String objectType, Object objectId) {
        check(action, objectType, objectId, false);
    }

    private void check(String action, String objectType, Object objectId, boolean allowProof) {
        AgentPrincipal agent = AgentContext.require();
        Duration max = props.stepUpMaxAge();
        Optional<Duration> age = agent.authAge(clock.instant());
        if (age.isPresent() && !age.get().isNegative() && age.get().compareTo(max) <= 0) {
            ActionAudit.noteAuth("RECENT_LOGIN", null);
            return;
        }
        HttpServletRequest req = request();
        String proof = req == null ? null : req.getHeader(PROOF_HEADER);
        if (allowProof && proof != null && !proof.isBlank()) {
            UUID device = verifyProof(req, proof.trim());
            if (device != null) {
                ActionAudit.noteAuth("DEVICE_PROOF", device);
                return;
            }
            audit.signatureInvalid(action, objectType, objectId, "device_proof_invalid", null);
            meters.counter("meridian.auth.step_up.challenges", "channel", "agent", "reason", "device_proof_invalid")
                    .increment();
            throw refuse("device_proof_invalid", "The device proof is invalid or expired; unlock the app on a "
                    + "registered device or sign in again", max);
        }
        // GAP-10: step-up challenges per channel and cause (SLO_SLA.md login / step-up failures)
        meters.counter("meridian.auth.step_up.challenges", "channel", "agent", "reason", "stale_auth").increment();
        audit.stepUpRequired(action, objectType, objectId);
        throw refuse("step_up_required", "This action needs a recent sign-in (within " + max.toSeconds()
                + " s) or a proof from a registered device; sign in again and retry", max);
    }

    /** The proving device when the proof is valid, else null (never says why: no oracle for attackers). */
    private UUID verifyProof(HttpServletRequest req, String proof) {
        String[] parts = proof.split("\\.", 4);
        if (parts.length != 4 || !"v1".equals(parts[0])) {
            return null;
        }
        UUID device;
        long ts;
        try {
            device = UUID.fromString(parts[1]);
            ts = Long.parseLong(parts[2]);
        } catch (IllegalArgumentException e) {
            return null;
        }
        long skew = Math.abs(clock.instant().getEpochSecond() - ts);
        if (skew > props.deviceProofMaxAge().toSeconds()) {
            return null;
        }
        Optional<? extends JsonNode> key = keys.activeKey(device);
        if (key.isEmpty() || !AgentContext.agentId().toString().equals(key.get().path("agent_id").asString(null))) {
            return null;
        }
        JsonNode deviceRow;
        try {
            deviceRow = own.device(device, "deviceProof");
        } catch (ApiException e) {
            return null;
        }
        if (!"ACTIVE".equals(deviceRow.path("status").asString(""))) {
            return null;
        }
        String payload = DeviceKeys.proofPayload(TenantContext.require(), AgentContext.agentId(), device,
                req.getMethod(), req.getRequestURI(), req.getHeader("Idempotency-Key"), ts);
        if (!DeviceKeys.verify(key.get(), payload, parts[3])) {
            return null;
        }
        keys.used(key.get());
        return device;
    }

    private static ApiException refuse(String code, String detail, Duration max) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a && a.getResponse() != null) {
            a.getResponse().setHeader("WWW-Authenticate", "Bearer error=\"insufficient_user_authentication\", "
                    + "error_description=\"A recent authentication or a device proof is required\", max_age="
                    + max.toSeconds());
        }
        return new ApiException(401, code, detail);
    }

    private static HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest() : null;
    }
}
