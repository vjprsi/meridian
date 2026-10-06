package io.meridian.agentnetwork.api;

import io.meridian.agentnetwork.api.generated.AgentOutletsApi;
import io.meridian.agentnetwork.api.generated.model.AgentOutlet;
import io.meridian.agentnetwork.api.generated.model.AgentOutletCreate;
import io.meridian.agentnetwork.api.generated.model.AgentOutletPage;
import io.meridian.agentnetwork.api.generated.model.AgentOutletUpdate;
import io.meridian.agentnetwork.api.generated.model.AgentOutletsCloseRequest;
import io.meridian.agentnetwork.application.OutletService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Outlets of agents, including the geo search for the nearest points of service. */
@RestController
public class AgentOutletsController implements AgentOutletsApi {
    private static final Set<String> PROTECTED = Set.of("agent_id");

    private final OutletService outlets;
    private final Actions actions;
    private final CrudEngine crud;

    public AgentOutletsController(OutletService outlets, Actions actions, CrudEngine crud) {
        this.outlets = outlets;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<AgentOutlet> agentOutletsCreate(String idempotencyKey, AgentOutletCreate body) {
        return actions.create(idempotencyKey, body, AgentOutlet.class, () -> outlets.createOutlet(actions.tree(body)));
    }

    @Override
    public ResponseEntity<AgentOutlet> agentOutletsUpdate(UUID id, String ifMatch, AgentOutletUpdate body) {
        TenantContext.require();
        outlets.validateLocationUpdate(id, actions.tree(body));
        return actions.guardedUpdate(crud, "agent_outlet", AgentOutlet.class, id, ifMatch, body, PROTECTED,
                "an outlet cannot move to another agent");
    }

    @Override
    public ResponseEntity<AgentOutletPage> agentOutletsNearby(BigDecimal lat, BigDecimal lon, Integer radiusM, String service,
                                                              Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(actions.json().readValue(outlets.nearby(lat, lon, radiusM, service, limit, cursor),
                AgentOutletPage.class));
    }

    @Override
    public ResponseEntity<AgentOutlet> agentOutletsClose(UUID id, String idempotencyKey, AgentOutletsCloseRequest body) {
        return actions.action(idempotencyKey, id, "close", body, AgentOutlet.class,
                () -> outlets.closeOutlet(id, Http.text(actions.tree(body), "reason")));
    }
}
