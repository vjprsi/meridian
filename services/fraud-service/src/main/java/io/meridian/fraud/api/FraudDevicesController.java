package io.meridian.fraud.api;

import io.meridian.fraud.api.generated.FraudDevicesApi;
import io.meridian.fraud.api.generated.model.BehaviouralSignalPage;
import io.meridian.fraud.api.generated.model.DeviceFingerprint;
import io.meridian.fraud.api.generated.model.FraudDevicesBlockRequest;
import io.meridian.fraud.api.generated.model.FraudDevicesTrustRequest;
import io.meridian.fraud.api.generated.model.FraudDevicesUnblockRequest;
import io.meridian.fraud.application.DeviceService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Device trust / block / unblock and behavioural signals per device. */
@RestController
public class FraudDevicesController implements FraudDevicesApi {
    private final DeviceService devices;
    private final CrudEngine crud;
    private final Http http;

    public FraudDevicesController(DeviceService devices, CrudEngine crud, Http http) {
        this.devices = devices;
        this.crud = crud;
        this.http = http;
    }

    @Override
    public ResponseEntity<DeviceFingerprint> fraudDevicesTrust(UUID id, String idempotencyKey, FraudDevicesTrustRequest body) {
        String reason = Http.text(http.tree(body), "reason");
        return http.once(idempotencyKey, body, DeviceFingerprint.class,
                () -> http.ok(devices.trust(id, reason), DeviceFingerprint.class));
    }

    @Override
    public ResponseEntity<DeviceFingerprint> fraudDevicesBlock(UUID id, String idempotencyKey, FraudDevicesBlockRequest body) {
        String reason = Http.text(http.tree(body), "reason");
        return http.once(idempotencyKey, body, DeviceFingerprint.class,
                () -> http.ok(devices.block(id, reason), DeviceFingerprint.class));
    }

    @Override
    public ResponseEntity<DeviceFingerprint> fraudDevicesUnblock(UUID id, String idempotencyKey, FraudDevicesUnblockRequest body) {
        String reason = Http.text(http.tree(body), "reason");
        return http.once(idempotencyKey, body, DeviceFingerprint.class,
                () -> http.ok(devices.unblock(id, reason), DeviceFingerprint.class));
    }

    @Override
    public ResponseEntity<BehaviouralSignalPage> fraudDevicesSignals(UUID id, Integer limit, String cursor) {
        crud.get("device_fingerprint", Object.class, Crud.params().with("id", id));
        return crud.list("behavioural_signal", BehaviouralSignalPage.class,
                Crud.params().with("device_fingerprint_id", id).with("limit", limit).with("cursor", cursor));
    }
}
