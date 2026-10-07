package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyProfileApi;
import io.meridian.customerchannel.api.generated.model.Customer;
import io.meridian.customerchannel.api.generated.model.MyProfileUpdateRequest;
import io.meridian.customerchannel.application.ProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in customer's own customer record. */
@RestController
public class MyProfileController implements MyProfileApi {
    private final ProfileService profile;
    private final Http http;

    public MyProfileController(ProfileService profile, Http http) {
        this.profile = profile;
        this.http = http;
    }

    @Override
    public ResponseEntity<Customer> myProfileGet() {
        return http.ok(profile.profile(), Customer.class);
    }

    @Override
    public ResponseEntity<Customer> myProfileUpdate(String idempotencyKey, MyProfileUpdateRequest body) {
        return http.ok(profile.updateProfile(http.body(body)), Customer.class);
    }
}
