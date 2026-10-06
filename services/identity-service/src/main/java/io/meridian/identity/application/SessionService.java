package io.meridian.identity.application;

import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.identity.infrastructure.IdentityStore;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Staff session revocation (single, all of a user, and as a side effect of lock / deactivate / MFA reset). */
@Service
public class SessionService {
    private final IdentityStore store;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public SessionService(IdentityStore store, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public String revoke(UUID sessionId, String reason) {
        requireReason(reason);
        JsonNode s = store.lock("user_session", sessionId);
        if (s.hasNonNull("revoked_at")) {
            throw ApiException.conflict("session_already_revoked", "Session " + sessionId + " is already revoked");
        }
        String row = store.updateReturning("""
                update user_session t set revoked_at = now(), revoke_reason = ? where t.id = ?
                returning row_to_json(t)::text""", reason, sessionId);
        outbox.publish("platform.user_session.revoked", "user_session", sessionId, store.publicView(row));
        return row;
    }

    @Transactional
    public JsonNode revokeAll(UUID userId, String reason) {
        requireReason(reason);
        store.require("app_user", userId);
        int n = revokeActive(userId, reason);
        ObjectNode out = json.createObjectNode();
        out.put("user_id", userId.toString());
        out.put("revoked_count", n);
        out.put("reason", reason);
        return out;
    }

    /** Revokes every non-revoked, non-expired session of the user inside the caller's transaction. */
    @Transactional
    public int revokeActive(UUID userId, String reason) {
        List<String> rows = store.rows("""
                update user_session t set revoked_at = now(), revoke_reason = ?
                where t.user_id = ? and t.revoked_at is null and t.expires_at > now()
                returning row_to_json(t)::text""", reason, userId);
        for (String row : rows) {
            JsonNode n = store.publicView(row);
            outbox.publish("platform.user_session.revoked", "user_session", UUID.fromString(n.get("id").asString()), n);
        }
        return rows.size();
    }

    static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "A reason is required");
        }
    }
}
