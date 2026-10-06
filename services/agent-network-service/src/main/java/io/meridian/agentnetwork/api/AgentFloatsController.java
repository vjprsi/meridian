package io.meridian.agentnetwork.api;

import io.meridian.agentnetwork.api.generated.AgentFloatsApi;
import io.meridian.agentnetwork.api.generated.model.AgentFloat;
import io.meridian.agentnetwork.api.generated.model.AgentFloatCreate;
import io.meridian.agentnetwork.api.generated.model.AgentFloatUpdate;
import io.meridian.agentnetwork.application.FloatService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Float positions with thresholds, low-float alerting and automatic rebalancing. */
@RestController
public class AgentFloatsController implements AgentFloatsApi {
    private static final Set<String> PROTECTED = Set.of("agent_id", "agent_outlet_id", "wallet_id", "currency");

    private final FloatService floats;
    private final Actions actions;
    private final CrudEngine crud;

    public AgentFloatsController(FloatService floats, Actions actions, CrudEngine crud) {
        this.floats = floats;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<AgentFloat> agentFloatsCreate(String idempotencyKey, AgentFloatCreate body) {
        return actions.create(idempotencyKey, body, AgentFloat.class, () -> floats.createFloat(actions.tree(body)));
    }

    @Override
    public ResponseEntity<AgentFloat> agentFloatsUpdate(UUID id, String ifMatch, AgentFloatUpdate body) {
        TenantContext.require();
        ObjectNode merged = (ObjectNode) actions.tree(crud.get("agent_float", AgentFloat.class, Crud.params().with("id", id)).getBody());
        actions.tree(body).properties().forEach(e -> {
            if (!e.getValue().isNull()) {
                merged.set(e.getKey(), e.getValue());
            }
        });
        FloatService.validateThresholds(merged);
        return actions.guardedUpdate(crud, "agent_float", AgentFloat.class, id, ifMatch, body, PROTECTED,
                "the float wallet and its owner are fixed");
    }

    @Override
    public ResponseEntity<Map<String, Object>> agentFloatsCheck(UUID id, String idempotencyKey) {
        return actions.objectAction(idempotencyKey, "check:" + id, null, () -> floats.check(id));
    }
}
