package io.meridian.customer.application;

import static io.meridian.customer.application.Json.now;
import static io.meridian.customer.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.customer.api.generated.model.CustomerCredential;
import io.meridian.customer.domain.Lifecycles;
import io.meridian.customer.domain.PinPolicy;
import io.meridian.customer.infrastructure.Crypto;
import io.meridian.customer.infrastructure.Rows;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Authentication credentials. Passkeys follow the WebAuthn registration ceremony (server-issued, MAC-protected
 * challenge bound to tenant, customer and device; client data type, challenge and origin verified). PINs are
 * policy-checked and stored as PBKDF2 hashes; three wrong old PINs lock the credential until a staff unlock.
 */
@Service
public class CredentialService {
    static final String T = "customer_credential";
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    private static final Duration LOCKOUT = Duration.ofMinutes(30);

    private final Rows rows;
    private final Guards guards;
    private final Crypto crypto;
    private final Events events;
    private final JsonMapper json;
    private final String rpId;
    private final String rpName;
    private final List<String> origins;

    public CredentialService(Rows rows, Guards guards, Crypto crypto, Events events, JsonMapper json,
                             @Value("${meridian.customer.webauthn.rp-id:meridian.example}") String rpId,
                             @Value("${meridian.customer.webauthn.rp-name:Meridian Bank}") String rpName,
                             @Value("${meridian.customer.webauthn.origins:https://app.meridian.example}") List<String> origins) {
        this.rows = rows;
        this.guards = guards;
        this.crypto = crypto;
        this.events = events;
        this.json = json;
        this.rpId = rpId;
        this.rpName = rpName;
        this.origins = origins;
    }

    // ------------------------------------------------------------------ passkeys

    @Transactional(readOnly = true)
    public JsonNode registrationOptions(UUID customerId, UUID deviceId) {
        ObjectNode customer = guards.activeCustomer(customerId);
        if (deviceId != null) {
            requireTrustedDevice(customerId, deviceId);
        }
        long expires = now().plus(CHALLENGE_TTL).toEpochSecond();
        ObjectNode o = json.createObjectNode();
        o.put("challenge", crypto.challenge(context(customerId, deviceId), expires));
        ObjectNode rp = o.putObject("rp");
        rp.put("id", rpId);
        rp.put("name", rpName);
        ObjectNode user = o.putObject("user");
        user.put("id", Base64.getUrlEncoder().withoutPadding().encodeToString(customerId.toString().getBytes(StandardCharsets.UTF_8)));
        user.put("name", text(customer, "customer_number"));
        user.put("displayName", text(customer, "display_name"));
        ArrayNode params = o.putArray("pubKeyCredParams");
        for (int alg : new int[] {-7, -8, -257}) {
            params.addObject().put("type", "public-key").put("alg", alg);
        }
        o.put("timeout", CHALLENGE_TTL.toMillis());
        o.put("attestation", "none");
        ObjectNode sel = o.putObject("authenticatorSelection");
        sel.put("residentKey", "required");
        sel.put("userVerification", "required");
        ArrayNode exclude = o.putArray("excludeCredentials");
        rows.jdbc().sql("""
                select webauthn_credential_id from customer_credential where customer_id = ? and credential_type = 'PASSKEY'
                and status <> 'REVOKED'""").param(customerId).query(String.class).list()
                .forEach(id -> exclude.addObject().put("type", "public-key").put("id", id));
        o.put("expires_at", OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(expires), java.time.ZoneOffset.UTC).toString());
        return o;
    }

    /**
     * Completes passkey registration. {@code attestation}: {"id": base64url credential id, "public_key": base64
     * COSE/SPKI key, "client_data_json": base64url clientDataJSON, "sign_count": n}.
     */
    @Transactional
    public ObjectNode registerPasskey(UUID customerId, JsonNode attestation, UUID deviceId) {
        guards.activeCustomer(customerId);
        if (deviceId != null) {
            requireTrustedDevice(customerId, deviceId);
        }
        String credentialId = text(attestation, "id");
        String publicKey = text(attestation, "public_key");
        String clientDataB64 = text(attestation, "client_data_json");
        if (credentialId == null || credentialId.isBlank() || publicKey == null || clientDataB64 == null) {
            throw ApiException.unprocessable("invalid_attestation", "attestation needs id, public_key and client_data_json");
        }
        JsonNode clientData;
        try {
            clientData = json.readTree(new String(Base64.getUrlDecoder().decode(clientDataB64.replace("=", "")), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw ApiException.unprocessable("invalid_attestation", "client_data_json is not valid base64url JSON");
        }
        if (!"webauthn.create".equals(text(clientData, "type"))) {
            throw ApiException.unprocessable("invalid_attestation", "client data type must be webauthn.create");
        }
        String challenge = text(clientData, "challenge");
        if (challenge == null || !crypto.verifyChallenge(challenge, context(customerId, deviceId), now().toEpochSecond())) {
            throw ApiException.unprocessable("invalid_challenge", "The registration challenge is invalid or expired");
        }
        if (!origins.contains(text(clientData, "origin"))) {
            throw ApiException.unprocessable("invalid_origin", "The registration origin is not allowed");
        }
        int existing = rows.jdbc().sql("select count(*) from customer_credential where webauthn_credential_id = ? and credential_type = 'PASSKEY'")
                .param(credentialId).query(Integer.class).single();
        if (existing > 0) {
            throw ApiException.conflict("credential_exists", "This passkey is already registered");
        }
        ObjectNode v = rows.object();
        v.put("customer_id", customerId.toString());
        v.put("credential_type", "PASSKEY");
        v.put("status", "ACTIVE");
        v.put("webauthn_credential_id", credentialId);
        v.put("public_key", publicKey);
        v.put("sign_count", attestation.path("sign_count").asLong(0));
        if (deviceId != null) {
            v.put("customer_device_id", deviceId.toString());
        }
        return rows.insert(T, v);
    }

    private String context(UUID customerId, UUID deviceId) {
        return "passkey:" + TenantContext.require() + ":" + customerId + ":" + (deviceId == null ? "-" : deviceId);
    }

    private void requireTrustedDevice(UUID customerId, UUID deviceId) {
        ObjectNode device = guards.child("customer_device", customerId, deviceId, false);
        if (!"TRUSTED".equals(text(device, "status"))) {
            throw ApiException.conflict("device_not_trusted", "Credentials can only be bound to a trusted device");
        }
    }

    // ------------------------------------------------------------------ PIN

    /**
     * Sets the first PIN, or changes it after verifying the old one; wrong old PINs count towards a lockout. Returns
     * the PIN credential (the hash itself is never exposed).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public ObjectNode setPin(UUID customerId, String newPin, String oldPin) {
        ObjectNode customer = guards.activeCustomer(customerId);
        PinPolicy.requireStrong(newPin, Json.date(customer, "date_of_birth"));
        Optional<ObjectNode> existing = rows.query("""
                select row_to_json(t)::text from customer_credential t where t.customer_id = ? and t.credential_type = 'PIN'
                and t.status <> 'REVOKED' order by t.id desc limit 1 for update""", customerId).stream().findFirst();
        if (existing.isEmpty()) {
            ObjectNode v = rows.object();
            v.put("customer_id", customerId.toString());
            v.put("credential_type", "PIN");
            v.put("status", "ACTIVE");
            v.put("secret_hash", crypto.hashSecret(newPin));
            return rows.insert(T, v);
        }
        ObjectNode pin = existing.get();
        UUID pinId = UUID.fromString(text(pin, "id"));
        String status = text(pin, "status");
        if ("LOCKED".equals(status)) {
            throw ApiException.conflict("credential_locked", "The PIN is locked; it must be unlocked by the bank");
        }
        if (oldPin == null || oldPin.isBlank()) {
            throw ApiException.unprocessable("old_pin_required", "old_pin_block is required to change the PIN");
        }
        if (!crypto.verifySecret(oldPin, text(pin, "secret_hash"))) {
            int attempts = pin.path("failed_attempts").asInt() + 1;
            ObjectNode fail = rows.object();
            fail.put("failed_attempts", attempts);
            if (attempts >= PinPolicy.MAX_FAILED_ATTEMPTS) {
                fail.put("status", "LOCKED");
                fail.put("locked_until", now().plus(LOCKOUT).toString());
                ObjectNode locked = rows.update(T, pinId, fail);
                events.publish("party.customer_credential.locked", T, locked, CustomerCredential.class);
                throw ApiException.conflict("credential_locked", "Too many wrong PINs; the PIN is now locked");
            }
            rows.update(T, pinId, fail);
            throw ApiException.unprocessable("invalid_pin", "The current PIN is not correct; "
                    + (PinPolicy.MAX_FAILED_ATTEMPTS - attempts) + " attempts left");
        }
        if (newPin.equals(oldPin)) {
            throw ApiException.unprocessable("pin_reuse", "The new PIN must differ from the current PIN");
        }
        ObjectNode v = rows.object();
        v.put("secret_hash", crypto.hashSecret(newPin));
        v.put("failed_attempts", 0);
        v.put("status", "ACTIVE");
        v.putNull("locked_until");
        v.put("last_used_at", now().toString());
        return rows.update(T, pinId, v);
    }

    // ------------------------------------------------------------------ unlock / revoke

    @Transactional
    public ObjectNode unlock(UUID customerId, UUID id, String reason) {
        guards.openCustomer(customerId);
        ObjectNode cred = guards.child(T, customerId, id, true);
        String to = Lifecycles.CREDENTIAL.apply("unlock", text(cred, "status"));
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("failed_attempts", 0);
        v.putNull("locked_until");
        return rows.update(T, id, v);
    }

    @Transactional
    public ObjectNode revoke(UUID customerId, UUID id, String reason) {
        guards.existingCustomer(customerId);
        ObjectNode cred = guards.child(T, customerId, id, true);
        String to = Lifecycles.CREDENTIAL.apply("revoke", text(cred, "status"));
        ObjectNode v = rows.object();
        v.put("status", to);
        v.putNull("secret_hash");
        return rows.update(T, id, v);
    }
}
