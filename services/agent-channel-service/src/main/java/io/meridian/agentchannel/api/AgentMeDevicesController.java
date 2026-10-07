package io.meridian.agentchannel.api;

import io.meridian.agentchannel.api.generated.AgentMeDevicesApi;
import io.meridian.agentchannel.api.generated.model.AgentDevice;
import io.meridian.agentchannel.api.generated.model.AgentDeviceKey;
import io.meridian.agentchannel.api.generated.model.AgentDeviceKeyPage;
import io.meridian.agentchannel.api.generated.model.AgentDevicePage;
import io.meridian.agentchannel.api.generated.model.AgentMeDevicesCreateRequest;
import io.meridian.agentchannel.api.generated.model.AgentMeDevicesHeartbeatRequest;
import io.meridian.agentchannel.api.generated.model.AgentMeDevicesRevokeRequest;
import io.meridian.agentchannel.application.DeviceService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The agent's devices: register with a signing key (device binding), heartbeat, keys, revoke. */
@RestController
public class AgentMeDevicesController implements AgentMeDevicesApi {
    private final DeviceService devices;
    private final Http http;

    public AgentMeDevicesController(DeviceService devices, Http http) {
        this.devices = devices;
        this.http = http;
    }

    @Override
    public ResponseEntity<AgentDevicePage> agentMeDevicesList(Integer limit, String cursor) {
        return http.ok(devices.devices(limit, cursor), AgentDevicePage.class);
    }

    @Override
    public ResponseEntity<AgentDeviceKey> agentMeDevicesCreate(String idempotencyKey, AgentMeDevicesCreateRequest body) {
        return http.ok(devices.register(http.body(body), idempotencyKey), AgentDeviceKey.class);
    }

    @Override
    public ResponseEntity<AgentDevice> agentMeDevicesGet(UUID deviceId) {
        return http.ok(devices.device(deviceId), AgentDevice.class);
    }

    @Override
    public ResponseEntity<AgentDeviceKeyPage> agentMeDevicesKeys(UUID deviceId, Integer limit, String cursor) {
        return devices.keys(deviceId, limit, cursor, AgentDeviceKeyPage.class);
    }

    @Override
    public ResponseEntity<AgentDevice> agentMeDevicesHeartbeat(UUID deviceId, String idempotencyKey,
                                                               AgentMeDevicesHeartbeatRequest body) {
        return http.ok(devices.heartbeat(deviceId, http.body(body), idempotencyKey), AgentDevice.class);
    }

    @Override
    public ResponseEntity<AgentDevice> agentMeDevicesRevoke(UUID deviceId, String idempotencyKey,
                                                            AgentMeDevicesRevokeRequest body) {
        return http.ok(devices.revoke(deviceId, http.body(body), idempotencyKey), AgentDevice.class);
    }
}
