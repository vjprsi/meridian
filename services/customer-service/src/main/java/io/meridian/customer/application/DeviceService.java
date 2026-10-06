package io.meridian.customer.application;

import static io.meridian.customer.application.Json.now;
import static io.meridian.customer.application.Json.text;
import static io.meridian.customer.application.Json.truncate;

import io.meridian.common.error.ApiException;
import io.meridian.customer.api.generated.model.CustomerDevice;
import io.meridian.customer.domain.Lifecycles;
import io.meridian.customer.infrastructure.Crypto;
import io.meridian.customer.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * Device binding. A device registers as PENDING with its public key; it becomes TRUSTED only by proving
 * possession of the private key: {@code challenge_response} is the base64 signature of
 * {@code "meridian-device-trust:<device_id>:<row_version>"} (the version makes every binding attempt unique).
 * Revocation also revokes the credentials bound to the device.
 */
@Service
public class DeviceService {
    static final String T = "customer_device";
    static final int MAX_TRUSTED_DEVICES = 5;

    private final Rows rows;
    private final CrudEngine crud;
    private final Guards guards;
    private final Crypto crypto;
    private final Events events;

    public DeviceService(Rows rows, CrudEngine crud, Guards guards, Crypto crypto, Events events) {
        this.rows = rows;
        this.crud = crud;
        this.guards = guards;
        this.crypto = crypto;
        this.events = events;
    }

    public static String trustChallenge(UUID deviceId, int rowVersion) {
        return "meridian-device-trust:" + deviceId + ":" + rowVersion;
    }

    @Transactional
    public ObjectNode create(UUID customerId, Object body) {
        guards.openCustomer(customerId);
        ObjectNode v = crud.toColumns(T, body);
        v.put("customer_id", customerId.toString());
        String status = text(v, "status");
        if (status != null && !"PENDING".equals(status)) {
            throw ApiException.unprocessable("invalid_initial_status", "Devices register as PENDING and are bound with trust");
        }
        v.put("status", "PENDING");
        if (text(v, "public_key") != null && !Crypto.isPublicKey(text(v, "public_key"))) {
            throw ApiException.unprocessable("invalid_public_key", "public_key must be a base64 X.509 EC, RSA or Ed25519 key");
        }
        return rows.insert(T, v);
    }

    @Transactional
    public ObjectNode update(UUID customerId, UUID id, String ifMatch, Object body) {
        int expected = CustomerService.parseVersion(ifMatch);
        ObjectNode current = guards.child(T, customerId, id, true);
        ObjectNode v = crud.toColumns(T, body);
        v.remove("customer_id");
        String status = text(v, "status");
        if (status != null && !status.equals(text(current, "status"))) {
            if (!"LOST".equals(status) || "REVOKED".equals(text(current, "status"))) {
                throw ApiException.unprocessable("status_change_not_allowed", "Use trust or revoke to change the device status");
            }
        }
        if (v.has("device_fingerprint") && !text(v, "device_fingerprint").equals(text(current, "device_fingerprint"))) {
            throw ApiException.unprocessable("immutable_field", "device_fingerprint cannot be changed");
        }
        if (v.has("public_key") && !text(v, "public_key").equals(text(current, "public_key"))) {
            if ("TRUSTED".equals(text(current, "status"))) {
                throw ApiException.conflict("device_bound", "Revoke the device before replacing its key");
            }
            if (!Crypto.isPublicKey(text(v, "public_key"))) {
                throw ApiException.unprocessable("invalid_public_key", "public_key must be a base64 X.509 EC, RSA or Ed25519 key");
            }
        }
        return rows.update(T, id, v, expected).orElseThrow(() -> CustomerService.versionConflict(expected));
    }

    @Transactional
    public ObjectNode trust(UUID customerId, UUID id, String challengeResponse) {
        ObjectNode customer = guards.activeCustomer(customerId);
        ObjectNode device = guards.child(T, customerId, id, true);
        String to = Lifecycles.DEVICE.apply("trust", text(device, "status"));
        if (device.path("is_rooted").asBoolean()) {
            throw ApiException.unprocessable("device_compromised", "A rooted or jailbroken device cannot be trusted");
        }
        String publicKey = text(device, "public_key");
        if (publicKey == null) {
            throw ApiException.unprocessable("public_key_required", "Register the device public key before binding");
        }
        if (!crypto.verifySignature(publicKey, trustChallenge(id, device.get("row_version").asInt()), challengeResponse)) {
            throw ApiException.unprocessable("invalid_challenge_response", "The challenge signature is not valid for this device");
        }
        int trusted = rows.jdbc().sql("select count(*) from customer_device where customer_id = ? and status = 'TRUSTED'")
                .param(customerId).query(Integer.class).single();
        if (trusted >= MAX_TRUSTED_DEVICES) {
            throw ApiException.unprocessable("device_limit_reached",
                    "At most " + MAX_TRUSTED_DEVICES + " trusted devices; revoke one first");
        }
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("bound_at", now().toString());
        v.put("last_seen_at", now().toString());
        ObjectNode row = rows.update(T, id, v);
        events.publish("party.customer_device.trusted", T, row, CustomerDevice.class);
        return row;
    }

    @Transactional
    public ObjectNode revoke(UUID customerId, UUID id, String reason) {
        guards.existingCustomer(customerId);
        ObjectNode device = guards.child(T, customerId, id, true);
        String to = Lifecycles.DEVICE.apply("revoke", text(device, "status"));
        ObjectNode v = rows.object();
        v.put("status", to);
        v.put("revoked_at", now().toString());
        v.put("revocation_reason", truncate(reason, 255));
        v.putNull("push_token");
        ObjectNode row = rows.update(T, id, v);
        rows.execute("update customer_credential set status = 'REVOKED' where customer_device_id = ? and status <> 'REVOKED'", id);
        events.publish("party.customer_device.revoked", T, row, CustomerDevice.class);
        return row;
    }
}
