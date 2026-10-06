package io.meridian.channel.api;

import io.meridian.channel.api.generated.UssdSessionsApi;
import io.meridian.channel.api.generated.model.UssdSession;
import io.meridian.channel.api.generated.model.UssdSessionStepPage;
import io.meridian.channel.api.generated.model.UssdSessionsCallbackRequest;
import io.meridian.channel.application.SessionService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** USSD sessions: the signed MNO gateway callback, session history and idle-session expiry. */
@RestController
public class UssdSessionsController implements UssdSessionsApi {
    private final SessionService sessions;
    private final GatewayCallbacks callbacks;
    private final Actions actions;
    private final CrudEngine crud;

    public UssdSessionsController(SessionService sessions, GatewayCallbacks callbacks, Actions actions, CrudEngine crud) {
        this.sessions = sessions;
        this.callbacks = callbacks;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<String> ussdSessionsCallback(UUID tenantId, UssdSessionsCallbackRequest body) {
        JsonNode req = actions.tree(body);
        String screen = callbacks.run(tenantId, body, "ussd-gateway", () -> sessions.handle(Http.text(req, "session_id"),
                Http.text(req, "msisdn"), Http.text(req, "short_code"), Http.text(req, "input"), Http.text(req, "mno")));
        return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8)).body(screen);
    }

    @Override
    public ResponseEntity<Map<String, Object>> ussdSessionsExpire(String idempotencyKey) {
        return actions.objectAction(idempotencyKey, "ussd-expire", null, sessions::expireIdle);
    }

    @Override
    public ResponseEntity<UssdSessionStepPage> ussdSessionsSteps(UUID id, Integer limit, String cursor) {
        crud.get("ussd_session", UssdSession.class, Crud.params().with("id", id));
        return crud.list("ussd_session_step", UssdSessionStepPage.class,
                Crud.params().with("ussd_session_id", id).with("limit", limit).with("cursor", cursor));
    }
}
