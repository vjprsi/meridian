package io.meridian.agentchannel.application;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.security.AgentContext;
import io.meridian.web.security.AgentContext.AgentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the agent's channel activity (table agent_action): every write, every object-level denial, every step-up
 * refusal and every invalid device signature. Denials and signature refusals also publish
 * {@code channels.agent_action.denied} for security monitoring. Recording never fails the agent's request (own
 * transaction, errors logged).
 */
@Component
public class ActionAudit {
    public static final String TABLE = "agent_action";
    static final String AUTH_ATTRIBUTE = ActionAudit.class.getName() + ".auth";
    private static final Logger log = LoggerFactory.getLogger(ActionAudit.class);

    /** How money movement was authorised on this request (set by {@link StepUp}). */
    record Auth(String method, UUID deviceId) {}

    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public ActionAudit(CrudEngine crud, OutboxWriter outbox, TransactionTemplate tx, JsonMapper json) {
        this.crud = crud;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
    }

    public void succeeded(String action, String objectType, Object objectId, Object resultId) {
        record(action, objectType, objectId, "SUCCEEDED", null, resultId, null);
    }

    public void denied(String action, String objectType, Object objectId, String code) {
        record(action, objectType, objectId, "DENIED", code, null, null);
    }

    public void stepUpRequired(String action, String objectType, Object objectId) {
        record(action, objectType, objectId, "STEP_UP_REQUIRED", "step_up_required", null, null);
    }

    public void signatureInvalid(String action, String objectType, Object objectId, String code, UUID deviceId) {
        record(action, objectType, objectId, "SIGNATURE_INVALID", code, null, deviceId);
    }

    public void rateLimited(String action, String objectType) {
        record(action, objectType, null, "RATE_LIMITED", "rate_limited", null, null);
    }

    /** Remembers how this request's money movement was authorised (recorded with its outcome). */
    static void noteAuth(String method, UUID deviceId) {
        RequestAttributes a = RequestContextHolder.getRequestAttributes();
        if (a != null) {
            a.setAttribute(AUTH_ATTRIBUTE, new Auth(method, deviceId), RequestAttributes.SCOPE_REQUEST);
        }
    }

    private void record(String action, String objectType, Object objectId, String outcome, String code, Object result,
                        UUID signingDevice) {
        AgentPrincipal agent = AgentContext.current().orElse(null);
        if (agent == null || TenantContext.current().isEmpty()) {
            return;
        }
        try {
            ObjectNode row = json.createObjectNode();
            row.put("agent_id", agent.agentId().toString());
            row.put("user_subject", agent.subject() == null ? null
                    : agent.subject().substring(0, Math.min(64, agent.subject().length())));
            row.put("action", action);
            row.put("object_type", objectType);
            putUuid(row, "object_id", objectId);
            row.put("outcome", outcome);
            row.put("error_code", code);
            putUuid(row, "result_id", result);
            Auth auth = RequestContextHolder.getRequestAttributes() == null ? null
                    : (Auth) RequestContextHolder.getRequestAttributes().getAttribute(AUTH_ATTRIBUTE,
                    RequestAttributes.SCOPE_REQUEST);
            if (auth != null) {
                row.put("auth_method", auth.method());
            }
            UUID device = signingDevice != null ? signingDevice : auth == null ? null : auth.deviceId();
            putUuid(row, "agent_device_id", device);
            agent.authAge(Instant.now()).ifPresent(age -> row.put("auth_age_seconds",
                    (int) Math.max(0, Math.min(Integer.MAX_VALUE, age.toSeconds()))));
            row.put("acr", agent.acr());
            HttpServletRequest req = request();
            if (req != null) {
                row.put("ip_address", clientIp(req));
                String ua = req.getHeader("User-Agent");
                row.put("user_agent", ua == null ? null : ua.substring(0, Math.min(255, ua.length())));
            }
            tx.executeWithoutResult(s -> {
                String inserted = crud.insert(TABLE, row);
                if ("DENIED".equals(outcome) || "SIGNATURE_INVALID".equals(outcome)) {
                    JsonNode saved = json.readTree(inserted);
                    outbox.publish("channels.agent_action.denied", TABLE,
                            UUID.fromString(saved.get("id").asString()), saved);
                }
            });
        } catch (RuntimeException e) {
            log.warn("could not record agent action {} {}: {}", action, outcome, e.getMessage());
        }
    }

    private static void putUuid(ObjectNode row, String field, Object value) {
        if (value == null) {
            return;
        }
        try {
            row.put(field, UUID.fromString(String.valueOf(value)).toString());
        } catch (IllegalArgumentException e) {
            // not a uuid: not recorded as an object id
        }
    }

    private static HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest() : null;
    }

    private static String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        String ip = fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : req.getRemoteAddr();
        return ip != null && ip.matches("[0-9a-fA-F:.]{2,45}") ? ip : null;
    }
}
