package io.meridian.customer.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.customer.api.generated.CustomerDevicesApi;
import io.meridian.customer.api.generated.model.CustomerDevice;
import io.meridian.customer.api.generated.model.CustomerDeviceCreate;
import io.meridian.customer.api.generated.model.CustomerDeviceUpdate;
import io.meridian.customer.api.generated.model.CustomerDevicesRevokeRequest;
import io.meridian.customer.api.generated.model.CustomerDevicesTrustRequest;
import io.meridian.customer.application.DeviceService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Device registration, binding by proof of key possession, and revocation. */
@RestController
public class CustomerDevicesController implements CustomerDevicesApi {
    private final DeviceService devices;
    private final Http http;

    public CustomerDevicesController(DeviceService devices, Http http) {
        this.devices = devices;
        this.http = http;
    }

    @Override
    public ResponseEntity<CustomerDevice> customerDevicesCreate(UUID customerId, String idempotencyKey, CustomerDeviceCreate body) {
        return http.created(idempotencyKey, "devices.create", customerId, body, CustomerDevice.class,
                () -> devices.create(customerId, body));
    }

    @Override
    public ResponseEntity<CustomerDevice> customerDevicesUpdate(UUID customerId, UUID id, String ifMatch, CustomerDeviceUpdate body) {
        TenantContext.require();
        ObjectNode row = devices.update(customerId, id, ifMatch, body);
        return ResponseEntity.ok().eTag("\"" + row.get("row_version").asString() + "\"").body(http.read(row, CustomerDevice.class));
    }

    @Override
    public ResponseEntity<CustomerDevice> customerDevicesTrust(UUID customerId, UUID id, String idempotencyKey,
                                                               CustomerDevicesTrustRequest body) {
        String response = http.tree(body).get("challenge_response").asString();
        return http.ok(idempotencyKey, "devices.trust", id, body, CustomerDevice.class,
                () -> devices.trust(customerId, id, response));
    }

    @Override
    public ResponseEntity<CustomerDevice> customerDevicesRevoke(UUID customerId, UUID id, String idempotencyKey,
                                                                CustomerDevicesRevokeRequest body) {
        String reason = http.tree(body).get("reason").asString();
        return http.ok(idempotencyKey, "devices.revoke", id, body, CustomerDevice.class,
                () -> devices.revoke(customerId, id, reason));
    }
}
