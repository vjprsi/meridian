package io.meridian.customer.api;

import io.meridian.customer.api.generated.CustomerCredentialsApi;
import io.meridian.customer.api.generated.model.CustomerCredential;
import io.meridian.customer.api.generated.model.CustomerCredentialsPasskeysRegistrationOptionsRequest;
import io.meridian.customer.api.generated.model.CustomerCredentialsPasskeysRequest;
import io.meridian.customer.api.generated.model.CustomerCredentialsPinRequest;
import io.meridian.customer.api.generated.model.CustomerCredentialsRevokeRequest;
import io.meridian.customer.api.generated.model.CustomerCredentialsUnlockRequest;
import io.meridian.customer.application.CredentialService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Passkeys (WebAuthn registration ceremony), PIN, unlock and revoke. */
@RestController
public class CustomerCredentialsController implements CustomerCredentialsApi {
    private final CredentialService credentials;
    private final Http http;

    public CustomerCredentialsController(CredentialService credentials, Http http) {
        this.credentials = credentials;
        this.http = http;
    }

    /** Returns the WebAuthn PublicKeyCredentialCreationOptions (challenge bound to tenant, customer and device). */
    @Override
    public ResponseEntity<Map<String, Object>> customerCredentialsPasskeysRegistrationOptions(UUID customerId,
            String idempotencyKey, CustomerCredentialsPasskeysRegistrationOptionsRequest body) {
        UUID device = body == null ? null : body.getCustomerDeviceId();
        return http.document(idempotencyKey, "credentials.registration-options", customerId, body, HttpStatus.OK,
                () -> credentials.registrationOptions(customerId, device));
    }

    @Override
    public ResponseEntity<CustomerCredential> customerCredentialsPasskeys(UUID customerId, String idempotencyKey,
                                                                          CustomerCredentialsPasskeysRequest body) {
        JsonNode req = http.tree(body);
        UUID device = body.getCustomerDeviceId();
        return http.created(idempotencyKey, "credentials.passkeys", customerId, body, CustomerCredential.class,
                () -> credentials.registerPasskey(customerId, req.get("attestation"), device));
    }

    @Override
    public ResponseEntity<CustomerCredential> customerCredentialsPin(UUID customerId, String idempotencyKey,
                                                                     CustomerCredentialsPinRequest body) {
        return http.ok(idempotencyKey, "credentials.pin", customerId, body, CustomerCredential.class,
                () -> credentials.setPin(customerId, body.getNewPinBlock(), body.getOldPinBlock()));
    }

    @Override
    public ResponseEntity<CustomerCredential> customerCredentialsUnlock(UUID customerId, UUID id, String idempotencyKey,
                                                                        CustomerCredentialsUnlockRequest body) {
        return http.ok(idempotencyKey, "credentials.unlock", id, body, CustomerCredential.class,
                () -> credentials.unlock(customerId, id, body.getReason()));
    }

    @Override
    public ResponseEntity<CustomerCredential> customerCredentialsRevoke(UUID customerId, UUID id, String idempotencyKey,
                                                                        CustomerCredentialsRevokeRequest body) {
        return http.ok(idempotencyKey, "credentials.revoke", id, body, CustomerCredential.class,
                () -> credentials.revoke(customerId, id, body.getReason()));
    }
}
