package io.meridian.businesschannel.application;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.security.BusinessContext;
import io.meridian.web.security.BusinessContext.BusinessPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the business's portal activity (table business_action): every write, every object-level, role or entitlement
 * denial and every step-up refusal. Denials also publish {@code channels.business_action.denied} for security
 * monitoring. Recording never fails the user's request: it runs in its own (new) transaction, so a denial is kept
 * even when the surrounding transaction rolls back, and errors are logged.
 */
@Component
public class ActionAudit {
    public static final String TABLE = "business_action";
    private static final Logger log = LoggerFactory.getLogger(ActionAudit.class);

    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public ActionAudit(CrudEngine crud, OutboxWriter outbox, TransactionTemplate tx, JsonMapper json) {
        this.crud = crud;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(tx.getTransactionManager());
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.json = json;
    }

    public void succeeded(String action, String objectType, Object objectId, Object resultId) {
        record(action, objectType, objectId, "SUCCEEDED", null, resultId);
    }

    public void failed(String action, String objectType, Object objectId, String code) {
        record(action, objectType, objectId, "FAILED", code, null);
    }

    public void denied(String action, String objectType, Object objectId, String code) {
        record(action, objectType, objectId, "DENIED", code, null);
    }

    public void stepUpRequired(String action, String objectType, Object objectId) {
        record(action, objectType, objectId, "STEP_UP_REQUIRED", "step_up_required", null);
    }

    private void record(String action, String objectType, Object objectId, String outcome, String code, Object result) {
        BusinessPrincipal user = BusinessContext.current().orElse(null);
        if (user == null || TenantContext.current().isEmpty()) {
            return;
        }
        try {
            ObjectNode row = json.createObjectNode();
            row.put("business_customer_id", user.businessCustomerId().toString());
            row.put("business_user_id", user.businessUserId().toString());
            row.put("action", action);
            row.put("object_type", objectType);
            putUuid(row, "object_id", objectId);
            row.put("outcome", outcome);
            row.put("error_code", code);
            putUuid(row, "result_id", result);
            user.authAge(Instant.now()).ifPresent(age -> row.put("auth_age_seconds",
                    (int) Math.max(0, Math.min(Integer.MAX_VALUE, age.toSeconds()))));
            row.put("acr", user.acr());
            HttpServletRequest req = request();
            if (req != null) {
                row.put("ip_address", clientIp(req));
                String ua = req.getHeader("User-Agent");
                row.put("user_agent", ua == null ? null : ua.substring(0, Math.min(255, ua.length())));
            }
            tx.executeWithoutResult(s -> {
                String inserted = crud.insert(TABLE, row);
                if ("DENIED".equals(outcome)) {
                    JsonNode saved = json.readTree(inserted);
                    outbox.publish("channels.business_action.denied", TABLE,
                            UUID.fromString(saved.get("id").asString()), saved);
                }
            });
        } catch (RuntimeException e) {
            log.warn("could not record business action {} {}: {}", action, outcome, e.getMessage());
        }
    }

    private static void putUuid(ObjectNode row, String field, Object value) {
        if (value == null) {
            return;
        }
        try {
            row.put(field, UUID.fromString(String.valueOf(value)).toString());
        } catch (IllegalArgumentException e) {
            // not a uuid (e.g. a channel name): not recorded as an object id
        }
    }

    private static HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest() : null;
    }

    private static String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        // SEC-16: the edge gateway overwrites X-Forwarded-For; take the right-most hop (left entries are client-chosen)
        String[] hops = fwd == null ? new String[0] : fwd.split(",");
        String ip = hops.length > 0 && !fwd.isBlank() ? hops[hops.length - 1].trim() : req.getRemoteAddr();
        return ip != null && ip.matches("[0-9a-fA-F:.]{2,45}") ? ip : null;
    }
}
