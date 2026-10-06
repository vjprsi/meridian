package io.meridian.agentnetwork.api;

import io.meridian.agentnetwork.api.generated.AgentTerritoriesApi;
import io.meridian.agentnetwork.api.generated.model.AgentTerritory;
import io.meridian.agentnetwork.api.generated.model.AgentTerritoryCreate;
import io.meridian.agentnetwork.api.generated.model.AgentTerritoryUpdate;
import io.meridian.agentnetwork.application.AgentService;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Territories organising the agent network (no cycles in the parent chain). */
@RestController
public class AgentTerritoriesController implements AgentTerritoriesApi {
    private final AgentService agents;
    private final Actions actions;
    private final CrudEngine crud;

    public AgentTerritoriesController(AgentService agents, Actions actions, CrudEngine crud) {
        this.agents = agents;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<AgentTerritory> agentTerritoriesCreate(String idempotencyKey, AgentTerritoryCreate body) {
        return actions.create(idempotencyKey, body, AgentTerritory.class, () -> agents.createTerritory(actions.tree(body)));
    }

    @Override
    public ResponseEntity<AgentTerritory> agentTerritoriesUpdate(UUID id, String ifMatch, AgentTerritoryUpdate body) {
        JsonNode req = actions.tree(body);
        agents.checkTerritoryParent(id, Http.uuid(req, "parent_id"));
        return actions.guardedUpdate(crud, "agent_territory", AgentTerritory.class, id, ifMatch, body, Set.of("code"),
                "territory codes are permanent");
    }
}
