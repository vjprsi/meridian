package io.meridian.agentchannel.application;

import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Device binding: the public signing keys of agent devices (table agent_device_key) and signature verification.
 *
 * <p>A device generates its key pair itself (the agent app: WebCrypto, non-extractable private key kept in
 * IndexedDB) and registers only the public key, as SubjectPublicKeyInfo DER in base64, with its algorithm:
 * <ul>
 *   <li>{@code ES256}: ECDSA on P-256 with SHA-256; signatures in IEEE P1363 form (r||s, 64 bytes, what WebCrypto
 *       produces) or DER.</li>
 *   <li>{@code ED25519}: Ed25519 (64-byte signatures).</li>
 * </ul>
 * One device has at most one ACTIVE key: registering a new key revokes the previous one. Signatures are base64 or
 * base64url over the UTF-8 bytes of a canonical text ({@link #batchPayload}, {@link #proofPayload}).
 */
@Component
public class DeviceKeys {
    public static final String TABLE = "agent_device_key";

    private final CrudEngine crud;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final OutboxWriter outbox;

    public DeviceKeys(CrudEngine crud, JdbcClient jdbc, JsonMapper json, TransactionTemplate tx, OutboxWriter outbox) {
        this.crud = crud;
        this.jdbc = jdbc;
        this.json = json;
        this.tx = tx;
        this.outbox = outbox;
    }

    // ------------------------------------------------------------------ canonical payloads

    /**
     * Text an offline batch is signed over: every field the channel forwards plus tenant, agent, device and the batch's
     * Idempotency-Key (so a signature cannot be replayed under another key, agent or tenant). Times are epoch
     * milliseconds; the payload itself is bound by the SHA-256 (hex) of the payload_uri string.
     */
    public static String batchPayload(UUID tenant, UUID agent, UUID device, String idempotencyKey, Instant capturedFrom,
                                      Instant capturedTo, int applicationCount, String payloadUri) {
        return "MERIDIAN-AGENT-OFFLINE-BATCH-V1\n"
                + "tenant=" + tenant + "\n"
                + "agent=" + agent + "\n"
                + "device=" + device + "\n"
                + "idempotency_key=" + idempotencyKey + "\n"
                + "captured_from=" + capturedFrom.toEpochMilli() + "\n"
                + "captured_to=" + capturedTo.toEpochMilli() + "\n"
                + "application_count=" + applicationCount + "\n"
                + "payload_sha256=" + sha256Hex(payloadUri.getBytes(StandardCharsets.UTF_8));
    }

    /** Text a device proof ({@code X-Agent-Device-Proof}) is signed over: one request, at one time. */
    public static String proofPayload(UUID tenant, UUID agent, UUID device, String method, String path,
                                      String idempotencyKey, long epochSeconds) {
        return "MERIDIAN-AGENT-DEVICE-PROOF-V1\n"
                + "tenant=" + tenant + "\n"
                + "agent=" + agent + "\n"
                + "device=" + device + "\n"
                + "method=" + method + "\n"
                + "path=" + path + "\n"
                + "idempotency_key=" + (idempotencyKey == null ? "" : idempotencyKey) + "\n"
                + "timestamp=" + epochSeconds;
    }

    // ------------------------------------------------------------------ keys

    /** Parses and checks a registered public key (422 invalid_public_key when malformed or of the wrong kind). */
    public static PublicKey parse(String algorithm, String publicKeyB64) {
        byte[] der;
        try {
            der = decode(publicKeyB64);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_public_key", "public_key must be base64 SubjectPublicKeyInfo");
        }
        try {
            if ("ES256".equals(algorithm)) {
                PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
                if (!(key instanceof ECPublicKey ec) || ec.getParams().getCurve().getField().getFieldSize() != 256) {
                    throw ApiException.unprocessable("invalid_public_key", "ES256 needs a P-256 public key");
                }
                return key;
            }
            if ("ED25519".equals(algorithm)) {
                return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
            }
        } catch (GeneralSecurityException e) {
            throw ApiException.unprocessable("invalid_public_key", "public_key is not a valid " + algorithm + " key");
        }
        throw ApiException.unprocessable("invalid_key_algorithm", "key_algorithm must be ES256 or ED25519");
    }

    /** Whether {@code signatureB64} is a valid signature of {@code payload} by the key row. */
    public static boolean verify(JsonNode keyRow, String payload, String signatureB64) {
        String algorithm = keyRow.path("key_algorithm").asString("");
        byte[] sig;
        try {
            sig = decode(signatureB64);
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            PublicKey key = parse(algorithm, keyRow.path("public_key").asString(""));
            Signature verifier;
            if ("ES256".equals(algorithm)) {
                verifier = Signature.getInstance(sig.length == 64 ? "SHA256withECDSAinP1363Format" : "SHA256withECDSA");
            } else {
                verifier = Signature.getInstance("Ed25519");
            }
            verifier.initVerify(key);
            verifier.update(payload.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(sig);
        } catch (GeneralSecurityException | ApiException e) {
            return false;
        }
    }

    public static String fingerprint(String publicKeyB64) {
        return sha256Hex(decode(publicKeyB64));
    }

    /** The ACTIVE key of a device, if any. */
    public Optional<ObjectNode> activeKey(UUID deviceId) {
        return jdbc.sql("select row_to_json(t)::text from agent_device_key t where agent_device_id = ? and status = 'ACTIVE' "
                        + "order by created_at desc limit 1")
                .param(deviceId).query(String.class).optional().map(s -> (ObjectNode) json.readTree(s));
    }

    /** Whether the device ever had a key (to tell "revoked" from "never registered"). */
    public boolean anyKey(UUID deviceId) {
        return jdbc.sql("select count(*) from agent_device_key where agent_device_id = ?").param(deviceId)
                .query(Long.class).single() > 0;
    }

    public Optional<ObjectNode> byFingerprint(String fingerprint) {
        return crud.findOne(TABLE, Map.of("key_fingerprint", fingerprint)).map(s -> (ObjectNode) json.readTree(s));
    }

    /**
     * Registers the device's key: revokes any previous ACTIVE key of the device (reason REPLACED) and stores the new
     * one, publishing {@code channels.agent_device_key.revoked} / {@code .registered}.
     */
    public ObjectNode register(UUID agentId, UUID deviceId, UUID outletId, String algorithm, String publicKeyB64,
                               String fingerprint, String subject) {
        return tx.execute(s -> {
            revokeAll(deviceId, "REPLACED");
            ObjectNode row = json.createObjectNode();
            row.put("agent_id", agentId.toString());
            row.put("agent_device_id", deviceId.toString());
            row.put("agent_outlet_id", outletId == null ? null : outletId.toString());
            row.put("key_algorithm", algorithm);
            row.put("public_key", publicKeyB64.trim());
            row.put("key_fingerprint", fingerprint);
            row.put("status", "ACTIVE");
            row.put("registered_by", subject == null ? null : subject.substring(0, Math.min(64, subject.length())));
            ObjectNode saved = (ObjectNode) json.readTree(crud.insert(TABLE, row));
            outbox.publish("channels.agent_device_key.registered", TABLE, UUID.fromString(saved.get("id").asString()),
                    saved);
            return saved;
        });
    }

    /** Revokes every ACTIVE key of the device; returns how many. */
    public int revokeAll(UUID deviceId, String reason) {
        Integer n = tx.execute(s -> {
            List<String> revoked = jdbc.sql("""
                    update agent_device_key set status = 'REVOKED', revoked_at = now(), revoked_reason = ?
                    where agent_device_id = ? and status = 'ACTIVE' returning row_to_json(agent_device_key)::text""")
                    .params(reason == null ? "REVOKED" : reason.substring(0, Math.min(255, reason.length())), deviceId)
                    .query(String.class).list();
            for (String r : revoked) {
                JsonNode row = json.readTree(r);
                outbox.publish("channels.agent_device_key.revoked", TABLE, UUID.fromString(row.get("id").asString()),
                        row);
            }
            return revoked.size();
        });
        return n == null ? 0 : n;
    }

    /** Records a successful verification (best effort). */
    public void used(JsonNode keyRow) {
        try {
            jdbc.sql("update agent_device_key set last_used_at = now() where id = ?")
                    .param(UUID.fromString(keyRow.get("id").asString())).update();
        } catch (RuntimeException e) {
            // last_used_at is informational
        }
    }

    // ------------------------------------------------------------------ helpers

    static byte[] decode(String b64) {
        String s = b64 == null ? "" : b64.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("empty");
        }
        return s.contains("-") || s.contains("_") ? Base64.getUrlDecoder().decode(s.replace("=", ""))
                : Base64.getDecoder().decode(s);
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
