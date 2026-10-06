package io.meridian.agentnetwork.api;

import io.meridian.agentnetwork.api.generated.AgentTillsApi;
import io.meridian.agentnetwork.api.generated.model.AgentTill;
import io.meridian.agentnetwork.api.generated.model.AgentTillCreate;
import io.meridian.agentnetwork.api.generated.model.AgentTillsCloseRequest;
import io.meridian.agentnetwork.api.generated.model.AgentTillsOpenRequest;
import io.meridian.agentnetwork.application.OutletService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Cash tills opened and closed per shift with cash counts and variance. */
@RestController
public class AgentTillsController implements AgentTillsApi {
    private final OutletService outlets;
    private final Actions actions;

    public AgentTillsController(OutletService outlets, Actions actions) {
        this.outlets = outlets;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<AgentTill> agentTillsCreate(String idempotencyKey, AgentTillCreate body) {
        return actions.create(idempotencyKey, body, AgentTill.class, () -> outlets.createTill(actions.tree(body)));
    }

    @Override
    public ResponseEntity<AgentTill> agentTillsOpen(UUID id, String idempotencyKey, AgentTillsOpenRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "open", body, AgentTill.class, () -> outlets.openTill(id,
                Http.uuid(req, "operator_user_id"), Http.decimal(req, "opening_cash"), req.get("denominations")));
    }

    @Override
    public ResponseEntity<AgentTill> agentTillsClose(UUID id, String idempotencyKey, AgentTillsCloseRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "close", body, AgentTill.class,
                () -> outlets.closeTill(id, Http.decimal(req, "declared_cash"), req.get("denominations")));
    }
}
