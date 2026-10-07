package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyDevicesApi;
import io.meridian.customerchannel.api.generated.model.CustomerDevice;
import io.meridian.customerchannel.api.generated.model.CustomerDevicePage;
import io.meridian.customerchannel.api.generated.model.MyDevicesRevokeRequest;
import io.meridian.customerchannel.application.ProfileService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Devices bound to the customer's digital banking. */
@RestController
public class MyDevicesController implements MyDevicesApi {
    private final ProfileService profile;
    private final Http http;

    public MyDevicesController(ProfileService profile, Http http) {
        this.profile = profile;
        this.http = http;
    }

    @Override
    public ResponseEntity<CustomerDevicePage> myDevicesList(Integer limit, String cursor) {
        return http.ok(profile.devices(limit, cursor), CustomerDevicePage.class);
    }

    @Override
    public ResponseEntity<CustomerDevice> myDevicesRevoke(UUID deviceId, String idempotencyKey,
                                                          MyDevicesRevokeRequest body) {
        return http.ok(profile.revokeDevice(deviceId, http.body(body), idempotencyKey), CustomerDevice.class);
    }
}
