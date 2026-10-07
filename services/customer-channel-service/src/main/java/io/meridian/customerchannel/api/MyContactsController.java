package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyContactsApi;
import io.meridian.customerchannel.api.generated.model.ContactVerification;
import io.meridian.customerchannel.api.generated.model.CustomerContact;
import io.meridian.customerchannel.api.generated.model.CustomerContactPage;
import io.meridian.customerchannel.api.generated.model.MyContactsCreateRequest;
import io.meridian.customerchannel.api.generated.model.MyContactsVerifyRequest;
import io.meridian.customerchannel.application.ProfileService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The customer's contact points with OTP verification. */
@RestController
public class MyContactsController implements MyContactsApi {
    private final ProfileService profile;
    private final Http http;

    public MyContactsController(ProfileService profile, Http http) {
        this.profile = profile;
        this.http = http;
    }

    @Override
    public ResponseEntity<CustomerContactPage> myContactsList(Integer limit, String cursor) {
        return http.ok(profile.contacts(limit, cursor), CustomerContactPage.class);
    }

    @Override
    public ResponseEntity<CustomerContact> myContactsCreate(String idempotencyKey, MyContactsCreateRequest body) {
        return http.ok(profile.addContact(http.body(body), idempotencyKey), CustomerContact.class);
    }

    @Override
    public ResponseEntity<ContactVerification> myContactsSendVerification(UUID contactId, String idempotencyKey) {
        return http.ok(profile.sendVerification(contactId, idempotencyKey), ContactVerification.class);
    }

    @Override
    public ResponseEntity<CustomerContact> myContactsVerify(UUID contactId, String idempotencyKey,
                                                            MyContactsVerifyRequest body) {
        return http.ok(profile.verifyContact(contactId, http.body(body), idempotencyKey), CustomerContact.class);
    }
}
