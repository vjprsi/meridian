package io.meridian.customer.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.customer.api.generated.CustomerContactsApi;
import io.meridian.customer.api.generated.model.ContactVerification;
import io.meridian.customer.api.generated.model.ContactVerificationPage;
import io.meridian.customer.api.generated.model.CustomerContact;
import io.meridian.customer.api.generated.model.CustomerContactCreate;
import io.meridian.customer.api.generated.model.CustomerContactUpdate;
import io.meridian.customer.api.generated.model.CustomerContactsVerifyRequest;
import io.meridian.customer.application.ContactService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Contact points (normalised and validated per type) and their OTP verification challenges. */
@RestController
public class CustomerContactsController implements CustomerContactsApi {
    private final ContactService contacts;
    private final Http http;

    public CustomerContactsController(ContactService contacts, Http http) {
        this.contacts = contacts;
        this.http = http;
    }

    @Override
    public ResponseEntity<CustomerContact> customerContactsCreate(UUID customerId, String idempotencyKey, CustomerContactCreate body) {
        return http.created(idempotencyKey, "contacts.create", customerId, body, CustomerContact.class,
                () -> contacts.create(customerId, body));
    }

    @Override
    public ResponseEntity<CustomerContact> customerContactsUpdate(UUID customerId, UUID id, String ifMatch, CustomerContactUpdate body) {
        TenantContext.require();
        ObjectNode row = contacts.update(customerId, id, ifMatch, body);
        return ResponseEntity.ok().eTag("\"" + row.get("row_version").asString() + "\"").body(http.read(row, CustomerContact.class));
    }

    @Override
    public ResponseEntity<Void> customerContactsDelete(UUID customerId, UUID id) {
        TenantContext.require();
        contacts.delete(customerId, id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ContactVerification> customerContactsSendVerification(UUID customerId, UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "contacts.send-verification", id, null, ContactVerification.class,
                () -> contacts.sendVerification(customerId, id));
    }

    @Override
    public ResponseEntity<CustomerContact> customerContactsVerify(UUID customerId, UUID id, String idempotencyKey,
                                                                  CustomerContactsVerifyRequest body) {
        return http.ok(idempotencyKey, "contacts.verify", id, body, CustomerContact.class,
                () -> contacts.verify(customerId, id, body.getCode()));
    }

    @Override
    public ResponseEntity<ContactVerificationPage> customerContactsVerifications(UUID customerId, UUID id, Integer limit,
                                                                                String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(http.read((ObjectNode) contacts.verifications(customerId, id, limit, cursor),
                ContactVerificationPage.class));
    }
}
