package io.meridian.cardvault.application;

import io.meridian.cardvault.domain.CardData;
import io.meridian.cardvault.domain.PinBlock;
import io.meridian.cardvault.domain.PinRules;
import io.meridian.cardvault.infrastructure.VaultStore;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The PCI vault: tokenisation, detokenisation (reveal, lookup, verify, reissue), PIN management and key rotation.
 * Card data exists in clear only inside these methods; it is never logged, never put in events and never stored
 * outside encrypted columns. Every detokenisation writes an access-log row (failures in their own transaction).
 *
 * <p>GAP-03: CVV2 is never stored - it is derived on demand under the card's CVK ({@code cvk_key_id}) for reveal and
 * verification. The PIN is never stored either: only the ISO 9564 PIN block encrypted under the PIN encryption key
 * ({@code pin_key_id}), produced by {@link Hsm#translatePinBlock} (inside the HSM in PKCS#11 mode) and compared as
 * ciphertext; weak PINs are refused by comparing with the encrypted weak-PIN reference blocks.
 */
@Service
public class VaultService {
    private static final int ROTATION_BATCH = 500;
    private static final int SYNC_BATCHES = 20;
    /** SEC-24: consecutive wrong CVV2 values after which CVV2 checks of the card are blocked. */
    private final int cvvTryLimit;

    public record Tokenize(String pan, Integer expiryMonth, Integer expiryYear, UUID cardId) {}

    public record Verify(String panToken, String cvv, Integer expiryMonth, Integer expiryYear, String pinBlock,
                         String pinKeyId) {}

    private record StoredPin(byte[] block, UUID keyId) {}

    private final VaultStore store;
    private final KeyService keys;
    private final Hsm hsm;
    private final AccessLog access;
    private final Events events;
    private final JsonMapper json;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();

    public VaultService(VaultStore store, KeyService keys, Hsm hsm, AccessLog access, Events events, JsonMapper json,
                        JdbcClient jdbc, PlatformTransactionManager tm,
                        @org.springframework.beans.factory.annotation.Value("${meridian.cards.vault.cvv-try-limit:5}")
                        int cvvTryLimit) {
        this.cvvTryLimit = cvvTryLimit;
        this.store = store;
        this.keys = keys;
        this.hsm = hsm;
        this.access = access;
        this.events = events;
        this.json = json;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
    }

    /** Keyed fingerprint of a sensitive value, for idempotency fingerprints that must not contain card data. */
    public String fingerprint(String value) {
        return value == null ? null : hsm.fingerprint(keys.active("FINGERPRINT").ref(), value);
    }

    // ------------------------------------------------------------------ tokenize

    public JsonNode tokenize(Tokenize t) {
        if (!CardData.panValid(t.pan())) {
            throw ApiException.unprocessable("invalid_pan", "pan must be 13-19 digits and pass the Luhn check");
        }
        if (!CardData.expiryValid(t.expiryMonth(), t.expiryYear())) {
            throw ApiException.unprocessable("invalid_expiry", "expiry_month must be 1-12 and expiry_year 4 digits");
        }
        String fp = fingerprint(t.pan());
        return tx.execute(s -> {
            jdbc.sql("select pg_advisory_xact_lock(hashtext(?))").param("card_data_vault:" + fp).query().singleRow();
            for (VaultStore.Record r : store.activeByFingerprint(fp)) {
                if (java.util.Objects.equals(r.cardId(), t.cardId())) {
                    return json.readTree(store.row(r.id()));
                }
                throw ApiException.conflict("pan_already_vaulted", "the PAN is already vaulted for another card");
            }
            KeyService.Key dek = keys.active("PAN_DEK");
            KeyService.Key cvk = keys.active("CVK");
            String token = newToken();
            UUID id = store.insert(new VaultStore.NewRecord(token, fp, enc(dek, token, "pan", t.pan()),
                    enc(dek, token, "exp", CardData.expiry(t.expiryMonth(), t.expiryYear())), null, dek.id(), cvk.id(),
                    null, t.cardId(), CardData.bin(t.pan()), CardData.last4(t.pan()), false, 0));
            return json.readTree(store.row(id));
        });
    }

    // ------------------------------------------------------------------ reveal / lookup

    /**
     * PAN, CVV2 (derived under the card's CVK) and expiry of the card's active record; with a public key they are
     * RSA-OAEP encrypted.
     */
    public JsonNode reveal(UUID cardId, String purpose, String publicKey, AccessLog.Caller caller) {
        VaultStore.Record r = store.activeByCard(cardId).orElseGet(() -> {
            access.failure(caller, purpose, null, cardId, "no active vault record for the card");
            throw ApiException.notFound("card vault record for card", cardId);
        });
        PublicKey pk = null;
        if (publicKey != null && !publicKey.isBlank()) {
            try {
                pk = parsePublicKey(publicKey);
            } catch (RuntimeException e) {
                access.failure(caller, purpose, r.panToken(), cardId, "invalid public key");
                throw ApiException.unprocessable("invalid_public_key",
                        "public_key must be an RSA X.509 SubjectPublicKeyInfo (PEM or base64 DER)");
            }
        }
        KeyService.Key dek = keys.byId(r.dekKeyId());
        String pan = dec(dek, r.panToken(), "pan", r.pan());
        String exp = dec(dek, r.panToken(), "exp", r.expiry());
        String cvv = hsm.deriveCvv(cvk(r).ref(), pan, CardData.expiryMonth(exp), CardData.expiryYear(exp));
        PublicKey key = pk;
        return tx.execute(s -> {
            store.touchDetokenized(r.id());
            JsonNode log = access.success(caller, purpose, r.panToken(), cardId);
            events.publish("cards.card_vault.revealed", "card_vault_access_log", log);
            ObjectNode out = (ObjectNode) json.readTree(store.row(r.id()));
            if (key != null) {
                ObjectNode secret = json.createObjectNode();
                secret.put("pan", pan);
                secret.put("cvv", cvv);
                secret.put("expiry_month", CardData.expiryMonth(exp));
                secret.put("expiry_year", CardData.expiryYear(exp));
                out.put("encrypted_payload", rsaEncrypt(key, json.writeValueAsBytes(secret)));
            } else {
                out.put("pan", pan);
                out.put("cvv", cvv);
                out.put("expiry_month", CardData.expiryMonth(exp));
                out.put("expiry_year", CardData.expiryYear(exp));
            }
            return out;
        });
    }

    public JsonNode lookup(String pan, String purpose, AccessLog.Caller caller) {
        if (!CardData.panValid(pan)) {
            throw ApiException.unprocessable("invalid_pan", "pan must be 13-19 digits and pass the Luhn check");
        }
        List<VaultStore.Record> found = store.activeByFingerprint(fingerprint(pan));
        if (found.isEmpty()) {
            access.failure(caller, purpose, null, null, "no active vault record for the PAN");
            throw ApiException.notFound("card vault record", "for the PAN");
        }
        VaultStore.Record r = found.getFirst();
        return tx.execute(s -> {
            store.touchDetokenized(r.id());
            access.success(caller, purpose, r.panToken(), r.cardId());
            return json.readTree(store.row(r.id()));
        });
    }

    // ------------------------------------------------------------------ verify (authorisation path)

    public Map<String, Object> verify(Verify v, AccessLog.Caller caller) {
        return tx.execute(s -> {
            VaultStore.Record r = store.byTokenForUpdate(v.panToken()).orElseGet(() -> {
                access.failure(caller, "AUTHORIZATION", v.panToken(), null, "unknown token");
                throw ApiException.notFound("card vault record", v.panToken());
            });
            KeyService.Key dek = keys.byId(r.dekKeyId());
            Map<String, Object> out = new LinkedHashMap<>();
            String pan = dec(dek, r.panToken(), "pan", r.pan());
            String stored = dec(dek, r.panToken(), "exp", r.expiry());
            String cvvResult = "P";
            boolean cvvBlocked = false;
            if (v.cvv() != null) {
                // SEC-24: consecutive CVV2 failures per card block further CVV2 checks (distributed card testing)
                if (store.cvvBlocked(r.id())) {
                    cvvResult = "N";
                    cvvBlocked = true;
                    access.failure(caller, "AUTHORIZATION", r.panToken(), r.cardId(), "cvv2 checks blocked");
                } else {
                    // GAP-03: the CVV2 is recomputed under the card's CVK, never read from storage
                    String expected = hsm.deriveCvv(cvk(r).ref(), pan, CardData.expiryMonth(stored),
                            CardData.expiryYear(stored));
                    boolean match = constantEquals(expected, v.cvv());
                    cvvResult = match ? "M" : "N";
                    int fails = store.cvvOutcome(r.id(), match, cvvTryLimit);
                    cvvBlocked = fails >= cvvTryLimit;
                }
            }
            String expResult = "P";
            if (v.expiryMonth() != null && v.expiryYear() != null) {
                expResult = stored.equals(CardData.expiry(v.expiryMonth(), v.expiryYear())) ? "M" : "N";
            }
            String pinResult = "NOT_PROCESSED";
            int tries = r.pinTryCount();
            if (v.pinBlock() != null) {
                if (!r.pinSet() || r.pin() == null) {
                    pinResult = "NOT_SET";
                } else if (tries >= PinRules.TRY_LIMIT) {
                    pinResult = "TRIES_EXCEEDED";
                } else {
                    StoredPin pin = currentPin(r, dek, pan);
                    KeyService.Key zpk = keys.resolveZoneKey(v.pinKeyId());
                    byte[] entered;
                    try {
                        entered = hsm.translatePinBlock(zpk.ref(), v.pinBlock(), keys.byId(pin.keyId()).ref(), pan);
                    } catch (IllegalArgumentException e) {
                        entered = null; // an undecodable PIN block counts as a wrong PIN
                    }
                    if (entered != null && MessageDigest.isEqual(entered, pin.block())) {
                        pinResult = "VERIFIED";
                        tries = 0;
                    } else {
                        pinResult = "INVALID";
                        tries++;
                    }
                    store.setPinTries(r.id(), tries);
                }
            }
            store.touchDetokenized(r.id());
            access.success(caller, "AUTHORIZATION", r.panToken(), r.cardId());
            out.put("cvv_result", cvvResult);
            out.put("cvv_blocked", cvvBlocked);
            out.put("expiry_result", expResult);
            out.put("pin_result", pinResult);
            out.put("pin_try_count", tries);
            return out;
        });
    }

    // ------------------------------------------------------------------ PIN management

    public Map<String, Object> setPin(String panToken, String pinBlock, String pinKeyId, AccessLog.Caller caller) {
        VaultStore.Record r = store.byToken(panToken).orElseGet(() -> {
            access.failure(caller, "PIN_MANAGEMENT", panToken, null, "unknown token");
            throw ApiException.notFound("card vault record", panToken);
        });
        KeyService.Key dek = keys.byId(r.dekKeyId());
        String pan = dec(dek, r.panToken(), "pan", r.pan());
        KeyService.Key pek = keys.active("PIN_KEY");
        byte[] translated;
        try {
            KeyService.Key zpk = keys.resolveZoneKey(pinKeyId);
            translated = hsm.translatePinBlock(zpk.ref(), pinBlock, pek.ref(), pan);
        } catch (IllegalArgumentException e) {
            access.failure(caller, "PIN_MANAGEMENT", panToken, r.cardId(), "invalid PIN block");
            throw ApiException.unprocessable("invalid_pin_block",
                    "encrypted_pin_block is not an ISO 9564 format 0 block wrapped under the zone key");
        } catch (ApiException e) {
            access.failure(caller, "PIN_MANAGEMENT", panToken, r.cardId(), e.code());
            throw e;
        }
        if (weak(pek, pan, translated)) {
            access.failure(caller, "PIN_MANAGEMENT", panToken, r.cardId(), "weak PIN rejected");
            throw ApiException.unprocessable("weak_pin", "the PIN is too easy to guess (repeated or sequential digits)");
        }
        return tx.execute(s -> {
            store.setPin(r.id(), translated, pek.id());
            access.success(caller, "PIN_MANAGEMENT", r.panToken(), r.cardId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pan_token", r.panToken());
            out.put("pin_set", true);
            out.put("event_type", r.pinSet() ? "CHANGE" : "SET");
            return out;
        });
    }

    public Map<String, Object> unblockPin(String panToken, AccessLog.Caller caller) {
        return tx.execute(s -> {
            VaultStore.Record r = store.byTokenForUpdate(panToken).orElseGet(() -> {
                access.failure(caller, "PIN_MANAGEMENT", panToken, null, "unknown token");
                throw ApiException.notFound("card vault record", panToken);
            });
            store.setPinTries(r.id(), 0);
            access.success(caller, "PIN_MANAGEMENT", r.panToken(), r.cardId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pan_token", r.panToken());
            out.put("pin_try_count", 0);
            return out;
        });
    }

    // ------------------------------------------------------------------ reissue

    /**
     * Same PAN under a new token with a new expiry (the CVV2 follows from the active CVK); the stored PIN block
     * carries over (same PAN, same PIN key); the source becomes inactive.
     */
    public JsonNode reissue(String panToken, UUID cardId, Integer month, Integer year, AccessLog.Caller caller) {
        if (!CardData.expiryValid(month, year)) {
            throw ApiException.unprocessable("invalid_expiry", "expiry_month must be 1-12 and expiry_year 4 digits");
        }
        return tx.execute(s -> {
            VaultStore.Record src = store.byTokenForUpdate(panToken).orElseGet(() -> {
                access.failure(caller, "REISSUE", panToken, cardId, "unknown token");
                throw ApiException.notFound("card vault record", panToken);
            });
            KeyService.Key old = keys.byId(src.dekKeyId());
            String pan = dec(old, src.panToken(), "pan", src.pan());
            StoredPin pin = src.pin() == null ? null : currentPin(src, old, pan);
            KeyService.Key dek = keys.active("PAN_DEK");
            KeyService.Key cvk = keys.active("CVK");
            String token = newToken();
            UUID id = store.insert(new VaultStore.NewRecord(token, src.fingerprint(), enc(dek, token, "pan", pan),
                    enc(dek, token, "exp", CardData.expiry(month, year)), pin == null ? null : pin.block(), dek.id(),
                    cvk.id(), pin == null ? null : pin.keyId(), cardId, CardData.bin(pan), CardData.last4(pan),
                    src.pinSet(), src.pinTryCount()));
            store.deactivate(src.id());
            access.success(caller, "REISSUE", src.panToken(), cardId);
            return json.readTree(store.row(id));
        });
    }

    // ------------------------------------------------------------------ key rotation

    /**
     * Rotates a key. SEC-40: PAN DEK re-encryption (and PIN key re-translation) runs in batches of
     * {@value #ROTATION_BATCH} records, each in its own short transaction (no tenant-wide lock for the whole run);
     * after {@value #SYNC_BATCHES} batches the rest continues on a background thread for the same tenant and the
     * response says {@code "status": "IN_PROGRESS"}. Records stay readable meanwhile (each names its key). A rotated
     * CVK applies to cards (re)issued from now on; existing cards keep verifying under the CVK they were issued with.
     */
    public Map<String, Object> rotate(String keyType, AccessLog.Caller caller) {
        KeyService.Key next = tx.execute(s -> keys.rotate(keyType));
        Map<UUID, KeyService.Key> cache = new java.util.concurrent.ConcurrentHashMap<>();
        IntSupplier batch = switch (keyType) {
            case "PAN_DEK" -> () -> reEncryptBatch(next, cache);
            case "PIN_KEY" -> () -> reTranslateBatch(next, cache);
            default -> null;
        };
        int count = 0;
        boolean more = false;
        if (batch != null) {
            int batches = 0;
            int n;
            do {
                n = batch.getAsInt();
                count += n;
                batches++;
            } while (n == ROTATION_BATCH && batches < SYNC_BATCHES);
            more = n == ROTATION_BATCH;
            if (more) {
                UUID tenant = TenantContext.require();
                String principal = TenantContext.principal();
                Thread.ofVirtual().name("vault-rotation-" + next.id()).start(() -> {
                    try {
                        TenantContext.callAs(tenant, principal, () -> {
                            int k;
                            do {
                                k = batch.getAsInt();
                            } while (k == ROTATION_BATCH);
                            return null;
                        });
                    } catch (Exception e) {
                        org.slf4j.LoggerFactory.getLogger(VaultService.class)
                                .error("background rotation under key {} stopped: {}", next.id(), e.toString());
                    }
                });
            }
        }
        int reEncrypted = count;
        boolean inProgress = more;
        return tx.execute(s -> {
            access.success(caller, "KEY_ROTATION", null, null);
            events.publish("cards.card_vault.keys_rotated", "card_vault_key", json.readTree(keys.eventRow(next.id())));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("key_type", keyType);
            out.put("new_key_id", next.id().toString());
            out.put("version", next.version());
            out.put("re_encrypted", reEncrypted);
            out.put("status", inProgress ? "IN_PROGRESS" : "COMPLETED");
            return out;
        });
    }

    /**
     * Master key rotation for the current tenant (GAP-01): re-wraps every data key under the current master key
     * version and adopts legacy derived keys; no record is re-encrypted. Audited and published (metadata only).
     */
    public Map<String, Object> rewrapMasterKey(AccessLog.Caller caller) {
        return tx.execute(s -> {
            KeyService.RewrapResult r = keys.rewrapAll();
            access.success(caller, "KEY_ROTATION", null, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("master_key_ref", r.masterRef());
            out.put("rewrapped", r.rewrapped());
            out.put("adopted", r.adopted());
            out.put("unchanged", r.unchanged());
            if (!r.keyIds().isEmpty()) {
                ObjectNode payload = json.createObjectNode();
                payload.put("id", r.keyIds().getFirst().toString());
                payload.put("master_key_ref", r.masterRef());
                payload.put("rewrapped", r.rewrapped());
                payload.put("adopted", r.adopted());
                payload.put("unchanged", r.unchanged());
                r.keyIds().forEach(id -> payload.withArray("key_ids").add(id.toString()));
                events.publish("cards.card_vault.keys_rewrapped", "card_vault_key", payload);
            }
            return out;
        });
    }

    /** One batch of PAN DEK re-encryption in its own transaction; returns how many records it re-encrypted. */
    private int reEncryptBatch(KeyService.Key next, Map<UUID, KeyService.Key> cache) {
        Integer n = tx.execute(s -> {
            List<VaultStore.Record> batch = store.encryptedUnderOtherKey(next.id(), ROTATION_BATCH);
            for (VaultStore.Record r : batch) {
                KeyService.Key old = cache.computeIfAbsent(r.dekKeyId(), keys::byId);
                String t = r.panToken();
                if (r.legacyPin()) {
                    // a legacy PIN is encrypted under the old DEK: move it under the PIN key before the DEK changes
                    currentPin(r, old, dec(old, t, "pan", r.pan()));
                }
                store.reEncrypt(r.id(), reWrap(old, next, t, "pan", r.pan()), reWrap(old, next, t, "exp", r.expiry()),
                        next.id());
            }
            return batch.size();
        });
        return n == null ? 0 : n;
    }

    /** One batch of PIN key re-translation (inside the HSM in HSM mode); returns how many PIN blocks it moved. */
    private int reTranslateBatch(KeyService.Key next, Map<UUID, KeyService.Key> cache) {
        Integer n = tx.execute(s -> {
            List<VaultStore.Record> batch = store.pinUnderOtherKey(next.id(), ROTATION_BATCH);
            for (VaultStore.Record r : batch) {
                if (r.legacyPin()) {
                    KeyService.Key dek = cache.computeIfAbsent(r.dekKeyId(), keys::byId);
                    store.storePinBlock(r.id(), protectLegacyPin(r, dek, dec(dek, r.panToken(), "pan", r.pan()), next),
                            next.id());
                } else {
                    KeyService.Key from = cache.computeIfAbsent(r.pinKeyId(), keys::byId);
                    store.storePinBlock(r.id(), hsm.retranslatePin(from.ref(), r.pin(), next.ref()), next.id());
                }
            }
            return batch.size();
        });
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ helpers

    /** The card verification key of a record (records from before GAP-03 without one: the active CVK). */
    private KeyService.Key cvk(VaultStore.Record r) {
        return r.cvkKeyId() != null ? keys.byId(r.cvkKeyId()) : keys.active("CVK");
    }

    /**
     * The record's stored PIN block and its PIN key; a legacy row (PIN encrypted under the DEK, before GAP-03) is
     * converted on the way and written back. Runs inside the caller's transaction.
     */
    private StoredPin currentPin(VaultStore.Record r, KeyService.Key dek, String pan) {
        if (!r.legacyPin()) {
            return new StoredPin(r.pin(), r.pinKeyId());
        }
        KeyService.Key pek = keys.active("PIN_KEY");
        byte[] block = protectLegacyPin(r, dek, pan, pek);
        store.storePinBlock(r.id(), block, pek.id());
        return new StoredPin(block, pek.id());
    }

    private byte[] protectLegacyPin(VaultStore.Record r, KeyService.Key dek, String pan, KeyService.Key pek) {
        byte[] pinBytes = hsm.decrypt(dek.ref(), r.pin(), aad(r.panToken(), "pin"));
        byte[] clear = PinBlock.encode(new String(pinBytes, StandardCharsets.UTF_8), pan);
        Arrays.fill(pinBytes, (byte) 0);
        try {
            return hsm.protectReferencePinBlock(pek.ref(), clear);
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    /** The translated block equals the encrypted reference block of a weak PIN (146 public values). */
    private boolean weak(KeyService.Key pek, String pan, byte[] translated) {
        boolean weak = false;
        for (String pin : PinRules.weakPins()) {
            byte[] block = PinBlock.encode(pin, pan);
            weak |= MessageDigest.isEqual(hsm.protectReferencePinBlock(pek.ref(), block), translated);
            Arrays.fill(block, (byte) 0);
        }
        return weak;
    }

    private byte[] reWrap(KeyService.Key from, KeyService.Key to, String token, String field, byte[] blob) {
        if (blob == null) {
            return null;
        }
        byte[] clear = hsm.decrypt(from.ref(), blob, aad(token, field));
        try {
            return hsm.encrypt(to.ref(), clear, aad(token, field));
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    private byte[] enc(KeyService.Key dek, String token, String field, String value) {
        return hsm.encrypt(dek.ref(), value.getBytes(StandardCharsets.UTF_8), aad(token, field));
    }

    private String dec(KeyService.Key dek, String token, String field, byte[] blob) {
        return new String(hsm.decrypt(dek.ref(), blob, aad(token, field)), StandardCharsets.UTF_8);
    }

    /** Binds each ciphertext to its tenant, record (token) and field so blobs cannot be swapped between rows. */
    private static byte[] aad(String token, String field) {
        return (TenantContext.require() + ":" + token + ":" + field).getBytes(StandardCharsets.UTF_8);
    }

    private String newToken() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return "tok_" + HexFormat.of().formatHex(b);
    }

    private static boolean constantEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static PublicKey parsePublicKey(String text) {
        try {
            String b64 = text.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid public key");
        }
    }

    private static String rsaEncrypt(PublicKey key, byte[] plain) {
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                    PSource.PSpecified.DEFAULT));
            return Base64.getEncoder().encodeToString(c.doFinal(plain));
        } catch (Exception e) {
            throw ApiException.unprocessable("invalid_public_key", "public_key cannot encrypt the payload");
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }
}
