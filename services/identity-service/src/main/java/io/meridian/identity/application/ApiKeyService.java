package io.meridian.identity.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.events.OutboxWriter;
import io.meridian.identity.domain.Secrets;
import io.meridian.identity.infrastructure.IdentityStore;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * API keys: {@code <prefix>.<secret>}; only the prefix and the SHA-256 of the full key are stored, the plaintext is
 * returned once. Rotation issues a replacement and leaves the old key valid (ROTATING) for a grace period.
 */
@Service
public class ApiKeyService {
    public static final int DEFAULT_GRACE_HOURS = 24;
    public static final int MAX_GRACE_HOURS = 168;

    private final IdentityStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public ApiKeyService(IdentityStore store, CrudEngine crud, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
    }

    public record Issued(ObjectNode body, Map<String, String> secrets) {}

    @Transactional
    public Issued create(UUID clientId, Object body) {
        ObjectNode v = crud.toColumns("api_key", body);
        if (v.hasNonNull("api_client_id") && !clientId.toString().equals(v.get("api_client_id").asString())) {
            throw ApiException.unprocessable("client_mismatch", "api_client_id in the body must match the path");
        }
        JsonNode client = activeClient(clientId);
        List<String> allowed = JsonItems.of(client.get("allowed_scopes"));
        List<String> scopes = v.hasNonNull("scopes") ? JsonItems.of(v.get("scopes")) : allowed;
        for (String s : scopes) {
            if (!allowed.contains(s)) {
                throw ApiException.unprocessable("scope_not_allowed", "Scope " + s + " is not allowed for this client");
            }
        }
        if (v.hasNonNull("expires_at") && !OffsetDateTime.parse(v.get("expires_at").asString()).isAfter(OffsetDateTime.now())) {
            throw ApiException.unprocessable("invalid_expiry", "expires_at must be in the future");
        }
        v.set("scopes", JsonItems.canonical(json, scopes));
        v.put("api_client_id", clientId.toString());
        return issue(v, "PRODUCTION".equals(client.get("environment").asString()));
    }

    @Transactional
    public Issued rotate(UUID clientId, UUID keyId, Integer graceHours) {
        int grace = graceHours == null ? DEFAULT_GRACE_HOURS : graceHours;
        if (grace < 0 || grace > MAX_GRACE_HOURS) {
            throw ApiException.unprocessable("invalid_grace_period", "grace_period_hours must be 0.." + MAX_GRACE_HOURS);
        }
        JsonNode old = lockKey(clientId, keyId);
        if (!"ACTIVE".equals(old.get("status").asString())) {
            throw ApiException.conflict("invalid_transition", "Only ACTIVE keys can be rotated; key is "
                    + old.get("status").asString());
        }
        JsonNode client = activeClient(clientId);
        OffsetDateTime graceEnd = OffsetDateTime.now(ZoneOffset.UTC).plusHours(grace);
        String oldRow;
        if (grace == 0) {
            oldRow = store.updateReturning("""
                    update api_key t set status = 'REVOKED', revoked_at = now() where t.id = ?
                    returning row_to_json(t)::text""", keyId);
            outbox.publish("platform.api_key.revoked", "api_key", keyId, store.publicView(oldRow));
        } else {
            oldRow = store.updateReturning("""
                    update api_key t set status = 'ROTATING',
                           expires_at = case when t.expires_at is null or t.expires_at > ? then ? else t.expires_at end
                    where t.id = ? returning row_to_json(t)::text""", graceEnd, graceEnd, keyId);
            outbox.publish("platform.api_key.rotated", "api_key", keyId, store.publicView(oldRow));
        }
        ObjectNode v = json.createObjectNode();
        v.put("api_client_id", clientId.toString());
        v.put("name", old.get("name").asString());
        if (old.hasNonNull("scopes")) {
            v.set("scopes", old.get("scopes"));
        }
        if (old.hasNonNull("expires_at") && OffsetDateTime.parse(old.get("expires_at").asString()).isAfter(graceEnd)) {
            v.set("expires_at", old.get("expires_at"));
        }
        v.put("rotated_from_id", keyId.toString());
        return issue(v, "PRODUCTION".equals(client.get("environment").asString()));
    }

    @Transactional
    public String revoke(UUID clientId, UUID keyId, String reason) {
        SessionService.requireReason(reason);
        JsonNode k = lockKey(clientId, keyId);
        String s = k.get("status").asString();
        if (!"ACTIVE".equals(s) && !"ROTATING".equals(s)) {
            throw ApiException.conflict("invalid_transition", "Key is already " + s);
        }
        String row = store.updateReturning("""
                update api_key t set status = 'REVOKED', revoked_at = now() where t.id = ? returning row_to_json(t)::text""",
                keyId);
        outbox.publish("platform.api_key.revoked", "api_key", keyId, store.publicView(row));
        return row;
    }

    private Issued issue(ObjectNode v, boolean production) {
        String prefix = Secrets.keyPrefix(production);
        String key = Secrets.apiKey(prefix);
        UUID id = Ids.newId();
        v.put("id", id.toString());
        v.put("key_prefix", prefix);
        v.put("key_hash", Secrets.sha256Hex(key));
        v.put("status", "ACTIVE");
        String row = crud.insert("api_key", v);
        ObjectNode pub = store.publicView(row);
        outbox.publish("platform.api_key.created", "api_key", id, pub);
        return new Issued(pub, Map.of("api_key", key));
    }

    private JsonNode activeClient(UUID clientId) {
        JsonNode c = store.require("api_client", clientId);
        if (!"ACTIVE".equals(c.get("status").asString())) {
            throw ApiException.conflict("client_not_active", "API client is " + c.get("status").asString());
        }
        return c;
    }

    private JsonNode lockKey(UUID clientId, UUID keyId) {
        JsonNode k = store.lock("api_key", keyId);
        if (!clientId.toString().equals(k.get("api_client_id").asString())) {
            throw ApiException.notFound("api key", keyId);
        }
        return k;
    }
}
