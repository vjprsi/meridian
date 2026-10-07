package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessProvisioningApi;
import io.meridian.businesschannel.api.generated.model.BusinessProvisioningAdminsRequest;
import io.meridian.businesschannel.api.generated.model.BusinessUser;
import io.meridian.businesschannel.api.generated.model.BusinessUserPage;
import io.meridian.businesschannel.application.ProvisioningService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Bank staff provisioning of business portal users (scope channels:admin; never business tokens). */
@RestController
public class BusinessProvisioningController implements BusinessProvisioningApi {
    private final ProvisioningService provisioning;
    private final Http http;

    public BusinessProvisioningController(ProvisioningService provisioning, Http http) {
        this.provisioning = provisioning;
        this.http = http;
    }

    @Override
    public ResponseEntity<BusinessUser> businessProvisioningAdmins(String idempotencyKey,
                                                                   BusinessProvisioningAdminsRequest body) {
        return http.once(idempotencyKey, body, BusinessUser.class, () -> provisioning.provisionAdmin(http.body(body)));
    }

    @Override
    public ResponseEntity<BusinessUserPage> businessProvisioningUsers(UUID businessCustomerId, Integer limit,
                                                                      String cursor) {
        return http.ok(provisioning.users(businessCustomerId, limit, cursor), BusinessUserPage.class);
    }
}
