package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyAddressesApi;
import io.meridian.customerchannel.api.generated.model.CustomerAddressPage;
import io.meridian.customerchannel.application.ProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The customer's postal addresses (read-only). */
@RestController
public class MyAddressesController implements MyAddressesApi {
    private final ProfileService profile;
    private final Http http;

    public MyAddressesController(ProfileService profile, Http http) {
        this.profile = profile;
        this.http = http;
    }

    @Override
    public ResponseEntity<CustomerAddressPage> myAddressesList(Integer limit, String cursor) {
        return http.ok(profile.addresses(limit, cursor), CustomerAddressPage.class);
    }
}
