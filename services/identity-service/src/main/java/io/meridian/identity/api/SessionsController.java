package io.meridian.identity.api;

import io.meridian.identity.api.generated.SessionsApi;
import io.meridian.identity.api.generated.model.SessionsRevokeAllRequest;
import io.meridian.identity.api.generated.model.SessionsRevokeRequest;
import io.meridian.identity.api.generated.model.UserSession;
import io.meridian.identity.application.SessionService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Staff sessions: revoke one or all sessions of a user. */
@RestController
public class SessionsController implements SessionsApi {
    private final SessionService sessions;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public SessionsController(SessionService sessions, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.sessions = sessions;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<UserSession> sessionsRevoke(UUID id, String idempotencyKey, SessionsRevokeRequest body) {
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, Map.of("op", "revoke", "id", id, "body", req), UserSession.class,
                () -> ResponseEntity.ok(crud.read(sessions.revoke(id, req.path("reason").asString(null)), UserSession.class)));
    }

    @Override
    public ResponseEntity<Void> sessionsRevokeAll(String idempotencyKey, SessionsRevokeAllRequest body) {
        JsonNode req = json.valueToTree(body);
        return Responses.body(idempotency.execute(idempotencyKey, body, JsonNode.class,
                () -> ResponseEntity.ok(sessions.revokeAll(UUID.fromString(req.get("user_id").asString()),
                        req.path("reason").asString(null)))));
    }
}
