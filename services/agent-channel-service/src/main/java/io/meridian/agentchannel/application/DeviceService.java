package io.meridian.agentchannel.application;

import static io.meridian.agentchannel.application.Ownership.AGENTS;
import static io.meridian.agentchannel.application.Ownership.me;
import static io.meridian.agentchannel.application.Ownership.mine;

import io.meridian.agentchannel.application.Downstream.Query;
import io.meridian.common.error.ApiException;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.security.AgentContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The agent's devices: registration with a public signing key (device binding), heartbeat, revocation. The device
 * record lives in agent-network-service; its keys live here (agent_device_key).
 */
@Service
public class DeviceService {
    private static final Set<String> DEVICE_TYPES = Set.of("POS", "SMARTPHONE", "TABLET", "FEATURE_PHONE");

    private final Downstream down;
    private final Ownership own;
    private final StepUp stepUp;
    private final ActionAudit audit;
    private final DeviceKeys keys;
    private final CrudEngine crud;

    public DeviceService(Downstream down, Ownership own, StepUp stepUp, ActionAudit audit, DeviceKeys keys,
                         CrudEngine crud) {
        this.down = down;
        this.own = own;
        this.stepUp = stepUp;
        this.audit = audit;
        this.keys = keys;
        this.crud = crud;
    }

    public JsonNode devices(Integer limit, String cursor) {
        return down.page(down.get(AGENTS, "/v1/agent-devices", Query.of("agent_id", me()).paging(limit, cursor)),
                r -> mine(r, "agent_id"));
    }

    public JsonNode device(UUID id) {
        return own.device(id, "agentMeDevicesGet");
    }

    public <T> org.springframework.http.ResponseEntity<T> keys(UUID id, Integer limit, String cursor, Class<T> page) {
        own.device(id, "agentMeDevicesKeys");
        return crud.list(DeviceKeys.TABLE, page, Crud.params().with("agent_device_id", id).with("agent_id", me())
                .with("limit", limit).with("cursor", cursor));
    }

    /**
     * Registers this device (found by serial number among the agent's devices, else created at the given outlet) and
     * binds its public key. Needs a recent login; the agent and outlet must be ACTIVE; a serial number registered to
     * another agent or a lost / retired device cannot be (re)bound.
     */
    public JsonNode register(JsonNode req, String key) {
        String action = "agentMeDevicesCreate";
        own.activeAgent();
        UUID outletId = Req.uuid(req, "agent_outlet_id", true);
        own.activeOutlet(outletId, action);
        String algorithm = Req.required(req, "key_algorithm");
        String publicKey = Req.required(req, "public_key");
        DeviceKeys.parse(algorithm, publicKey);
        String fingerprint = DeviceKeys.fingerprint(publicKey);
        String serial = Req.required(req, "serial_number");
        String type = Req.required(req, "device_type");
        if (!DEVICE_TYPES.contains(type)) {
            throw ApiException.unprocessable("invalid_device_type", "device_type must be one of " + DEVICE_TYPES);
        }
        stepUp.requireLogin(action, "DEVICE", null);

        JsonNode device = null;
        for (JsonNode d : down.get(AGENTS, "/v1/agent-devices", Query.of("serial_number", serial).with("limit", 5))
                .path("data")) {
            if (serial.equals(d.path("serial_number").asString(null))) {
                device = d;
            }
        }
        if (device != null && !mine(device, "agent_id")) {
            audit.denied(action, "DEVICE", device.path("id").asString(null), "device_serial_unavailable");
            throw ApiException.unprocessable("device_serial_unavailable",
                    "This serial number is registered to another agent; ask your network manager");
        }
        if (device != null && !"ACTIVE".equals(device.path("status").asString(""))) {
            throw ApiException.unprocessable("device_not_active", "The device is " + device.path("status").asString()
                    + "; a lost or retired device cannot be registered again");
        }
        if (device == null) {
            ObjectNode body = down.object()
                    .put("agent_id", me().toString())
                    .put("agent_outlet_id", outletId.toString())
                    .put("device_type", type)
                    .put("serial_number", serial)
                    .put("model", Req.text(req, "model"))
                    .put("os_version", Req.text(req, "os_version"))
                    .put("app_version", Req.text(req, "app_version"))
                    .put("assigned_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
            device = down.post(AGENTS, "/v1/agent-devices", body, key, "device-register");
        }
        UUID deviceId = UUID.fromString(device.path("id").asString());

        Optional<ObjectNode> existing = keys.byFingerprint(fingerprint);
        if (existing.isPresent()) {
            JsonNode k = existing.get();
            boolean same = deviceId.toString().equals(k.path("agent_device_id").asString(null))
                    && "ACTIVE".equals(k.path("status").asString(""));
            if (same) {
                return k;                                  // replay of the same registration
            }
            throw ApiException.unprocessable("public_key_in_use", "This key is already registered; generate a new key");
        }
        ObjectNode saved = keys.register(me(), deviceId, outletId, algorithm, publicKey, fingerprint,
                AgentContext.require().subject());
        audit.succeeded(action, "DEVICE", deviceId, saved.path("id").asString(null));
        return saved;
    }

    public JsonNode heartbeat(UUID id, JsonNode req, String key) {
        own.device(id, "agentMeDevicesHeartbeat");
        ObjectNode body = down.object();
        Req.copy(body, req, "latitude");
        Req.copy(body, req, "longitude");
        Req.copy(body, req, "app_version");
        return down.post(AGENTS, "/v1/agent-devices/" + id + "/heartbeat", body, key, "device-heartbeat:" + id);
    }

    /**
     * Revokes the device's signing keys at once (offline batches and proofs from it are refused from now on) and
     * reports an ACTIVE device lost downstream (blocks it). No step-up: revoking only takes rights away.
     */
    public JsonNode revoke(UUID id, JsonNode req, String key) {
        String action = "agentMeDevicesRevoke";
        JsonNode device = own.device(id, action);
        String reason = Req.text(req, "reason");
        keys.revokeAll(id, reason == null || reason.isBlank() ? "REVOKED_BY_AGENT" : reason);
        JsonNode result = device;
        if ("ACTIVE".equals(device.path("status").asString(""))) {
            ObjectNode body = down.object().put("reason", reason == null || reason.isBlank()
                    ? "Revoked by the agent" : reason);
            result = down.post(AGENTS, "/v1/agent-devices/" + id + "/report-lost", body, key, "device-revoke:" + id);
        }
        audit.succeeded(action, "DEVICE", id, null);
        return result;
    }
}
