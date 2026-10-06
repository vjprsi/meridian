package io.meridian.agentnetwork.api;

import io.meridian.agentnetwork.api.generated.AgentDevicesApi;
import io.meridian.agentnetwork.api.generated.model.AgentDevice;
import io.meridian.agentnetwork.api.generated.model.AgentDeviceCreate;
import io.meridian.agentnetwork.api.generated.model.AgentDeviceUpdate;
import io.meridian.agentnetwork.api.generated.model.AgentDevicesHeartbeatRequest;
import io.meridian.agentnetwork.api.generated.model.AgentDevicesReportLostRequest;
import io.meridian.agentnetwork.api.generated.model.AgentDevicesRetireRequest;
import io.meridian.agentnetwork.application.OutletService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Agent POS terminals and phones: heartbeats, loss reports and retirement. */
@RestController
public class AgentDevicesController implements AgentDevicesApi {
    private static final Set<String> PROTECTED = Set.of("agent_id", "serial_number");

    private final OutletService outlets;
    private final Actions actions;
    private final CrudEngine crud;

    public AgentDevicesController(OutletService outlets, Actions actions, CrudEngine crud) {
        this.outlets = outlets;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<AgentDevice> agentDevicesCreate(String idempotencyKey, AgentDeviceCreate body) {
        return actions.create(idempotencyKey, body, AgentDevice.class, () -> outlets.createDevice(actions.tree(body)));
    }

    @Override
    public ResponseEntity<AgentDevice> agentDevicesUpdate(UUID id, String ifMatch, AgentDeviceUpdate body) {
        TenantContext.require();
        JsonNode req = actions.tree(body);
        if (req.hasNonNull("agent_outlet_id")) {
            AgentDevice d = crud.get("agent_device", AgentDevice.class, Crud.params().with("id", id)).getBody();
            outlets.checkOutlet(Http.uuid(req, "agent_outlet_id"), d.getAgentId());
        }
        return actions.guardedUpdate(crud, "agent_device", AgentDevice.class, id, ifMatch, body, PROTECTED,
                "register a new device instead");
    }

    @Override
    public ResponseEntity<AgentDevice> agentDevicesHeartbeat(UUID id, String idempotencyKey, AgentDevicesHeartbeatRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "heartbeat", body, AgentDevice.class, () -> outlets.heartbeat(id,
                Http.decimal(req, "latitude"), Http.decimal(req, "longitude"), Http.text(req, "app_version")));
    }

    @Override
    public ResponseEntity<AgentDevice> agentDevicesReportLost(UUID id, String idempotencyKey, AgentDevicesReportLostRequest body) {
        return actions.action(idempotencyKey, id, "report-lost", body, AgentDevice.class,
                () -> outlets.deviceStatus(id, "LOST", Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<AgentDevice> agentDevicesRetire(UUID id, String idempotencyKey, AgentDevicesRetireRequest body) {
        return actions.action(idempotencyKey, id, "retire", body, AgentDevice.class,
                () -> outlets.deviceStatus(id, "RETIRED", Http.text(actions.tree(body), "reason")));
    }
}
