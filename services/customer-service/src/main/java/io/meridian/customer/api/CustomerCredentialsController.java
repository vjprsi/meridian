package io.meridian.customer.api;

import io.meridian.customer.api.generated.CustomerCredentialsApi;
import io.meridian.customer.api.generated.model.CustomerCredential;
import io.meridian.customer.api.generated.model.CustomerCredentialsPasskeysRegistrationOptionsRequest;
import io.meridian.customer.api.generated.model.CustomerCredentialsPasskeysRequest;
import io.meridian.customer.api.generated.model.CustomerCredentialsPinRequest;
import io.meridian.customer.api.generated.model.CustomerCredentialsRevokeRequest;
import io.meridian.customer.api.generated.model.CustomerCredentialsUnlockRequest;
import io.meridian.customer.application.CredentialService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Passkeys, PIN, unlock and revoke. Contract gap: {@code /passkeys/registration-options} is declared 204 without
 * a body; it returns 200 with the WebAuthn PublicKeyCredentialCreationOptions.
 */
@RestController
public class CustomerCredentialsController implements CustomerCredentialsApi {
    private final CredentialService credentials;
    private final Http http;

    public CustomerCredentialsController(CredentialService credentials, Http http) {
        this.credentials = credentials;
        this.http = http;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ResponseEntity customerCredentialsPasskeysRegistrationOptions(UUID customerId, String idempotencyKey,
            CustomerCredentialsPasskeysRegistrationOptionsRequest body) {
        JsonNode req = http.tree(body);
        UUID device = req.hasNonNull("customer_device_id") ? UUID.fromString(req.get("customer_device_id").asString()) : null;
        return http.document(idempotencyKey, "credentials.registration-options", customerId, body, HttpStatus.OK,
                () -> credentials.registrationOptions(customerId, device));
    }

    @Override
    public ResponseEntity<CustomerCredential> customerCredentialsPasskeys(UUID customerId, String idempotencyKey,
                                                                          CustomerCredentialsPasskeysRequest body) {
        JsonNode req = http.tree(body);
        UUID device = req.hasNonNull("customer_device_id") ? UUID.fromString(req.get("customer_device_id").asString()) : null;
        return http.created(idempotencyKey, "credentials.passkeys", customerId, body, CustomerCredential.class,
                () -> credentials.registerPasskey(customerId, req.get("attestation"), device));
    }

    @Override
    public ResponseEntity<Void> customerCredentialsPin(UUID customerId, String idempotencyKey, CustomerCredentialsPinRequest body) {
        JsonNode req = http.tree(body);
        String newPin = req.get("new_pin_block").asString();
        String oldPin = req.hasNonNull("old_pin_block") ? req.get("old_pin_block").asString() : null;
        return http.noContent(idempotencyKey, "credentials.pin", customerId, body,
                () -> credentials.setPin(customerId, newPin, oldPin));
    }

    @Override
    public ResponseEntity<CustomerCredential> customerCredentialsUnlock(UUID customerId, UUID id, String idempotencyKey,
                                                                        CustomerCredentialsUnlockRequest body) {
        String reason = http.tree(body).get("reason").asString();
        return http.ok(idempotencyKey, "credentials.unlock", id, body, CustomerCredential.class,
                () -> credentials.unlock(customerId, id, reason));
    }

    @Override
    public ResponseEntity<CustomerCredential> customerCredentialsRevoke(UUID customerId, UUID id, String idempotencyKey,
                                                                        CustomerCredentialsRevokeRequest body) {
        String reason = http.tree(body).get("reason").asString();
        return http.ok(idempotencyKey, "credentials.revoke", id, body, CustomerCredential.class,
                () -> credentials.revoke(customerId, id, reason));
    }
}
