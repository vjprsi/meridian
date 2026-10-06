package io.meridian.cardvault.application;

import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes card_vault_access_log rows (append-only audit of every detokenisation). {@link #success} joins the
 * caller's transaction; {@link #failure} commits on its own so a rejected access is recorded even though the
 * request fails.
 */
@Component
public class AccessLog {
    /** Who is calling: workload identity, end user and network address. */
    public record Caller(String service, String actor, String ip) {}

    private final CrudEngine crud;
    private final JsonMapper json;
    private final TransactionTemplate requiresNew;

    public AccessLog(CrudEngine crud, JsonMapper json, PlatformTransactionManager tm) {
        this.crud = crud;
        this.json = json;
        this.requiresNew = new TransactionTemplate(tm);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public JsonNode success(Caller caller, String purpose, String panToken, UUID cardId) {
        return write(caller, purpose, panToken, cardId, true, null);
    }

    public void failure(Caller caller, String purpose, String panToken, UUID cardId, String reason) {
        requiresNew.executeWithoutResult(s -> write(caller, purpose, panToken, cardId, false, reason));
    }

    private JsonNode write(Caller caller, String purpose, String panToken, UUID cardId, boolean ok, String reason) {
        ObjectNode v = json.createObjectNode();
        v.put("purpose", purpose);
        v.put("caller_service", trim(caller.service(), 64));
        v.put("actor_id", trim(caller.actor(), 64));
        v.put("success", ok);
        if (panToken != null) {
            v.put("pan_token", panToken);
        }
        if (cardId != null) {
            v.put("card_id", cardId.toString());
        }
        if (reason != null) {
            v.put("failure_reason", trim(reason, 255));
        }
        if (caller.ip() != null) {
            v.put("ip_address", caller.ip());
        }
        return json.readTree(crud.insert("card_vault_access_log", v));
    }

    private static String trim(String s, int n) {
        return s == null ? "unknown" : s.length() > n ? s.substring(0, n) : s;
    }
}
