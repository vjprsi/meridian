package io.meridian.cardvault.infrastructure;

import io.meridian.cardvault.application.KeyManagement;
import io.meridian.cardvault.application.MasterKeyProvider;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Development {@link KeyManagement}: master keys supplied by {@link MasterKeyProvider}s (the current one first, then
 * previous ones still accepted for unwrapping during a rotation). Data keys are wrapped with AES-256-GCM under the
 * master key, the canonical encryption context as AAD; the master key reference is {@code software:<kcv>}.
 *
 * <p>Data keys are <em>derived</em> ({@code HMAC-SHA256(master, "key:<label>:v<version>")}, the original software
 * HSM scheme) rather than random, so data vaulted before envelope encryption and sandbox tooling that wraps PIN
 * blocks with the documented KDF keep working in development. AWS KMS generates random data keys.
 */
public class SoftwareKeyManagement implements KeyManagement {
    private static final int IV_LEN = 12;

    private final Map<String, byte[]> masters = new LinkedHashMap<>();
    private final String current;
    private final SecureRandom random = new SecureRandom();

    /** @param keys current master key first, then previous ones (unwrap only) */
    public SoftwareKeyManagement(List<MasterKeyProvider> keys) {
        if (keys.isEmpty()) {
            throw new IllegalStateException("software key management needs a master key");
        }
        String first = null;
        for (MasterKeyProvider p : keys) {
            byte[] k = p.masterKey();
            if (k.length != 32) {
                throw new IllegalStateException("card-vault master keys must be 32 bytes (" + p.source() + ")");
            }
            String ref = ref(k);
            masters.putIfAbsent(ref, k);
            if (first == null) {
                first = ref;
            }
        }
        this.current = first;
    }

    /** {@code software:<kcv>} of a master key (6 hex chars of AES-ECB(key, zeros); identifies, never reveals it). */
    static String ref(byte[] master) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(master, "AES"));
            return "software:" + HexFormat.of().withUpperCase().formatHex(c.doFinal(new byte[16])).substring(0, 6);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public DataKey generateDataKey(Map<String, String> context) {
        String key = context.get("key");
        if (key == null || !key.matches(".+:v\\d+")) {
            throw new IllegalArgumentException("encryption context needs key=<label>:v<version>");
        }
        int at = key.lastIndexOf(":v");
        byte[] plain = SoftwareHsm.deriveKey(masters.get(current), key.substring(0, at),
                Integer.parseInt(key.substring(at + 2)));
        return new DataKey(plain, wrap(plain, context));
    }

    @Override
    public byte[] decrypt(WrappedKey wrapped, Map<String, String> context) {
        byte[] master = masters.get(wrapped.masterRef());
        if (master == null) {
            throw new IllegalStateException("data key wrapped under unknown master key " + wrapped.masterRef()
                    + " (configure it as a previous master key until the re-wrap has run)");
        }
        byte[] blob = wrapped.ciphertext();
        if (blob == null || blob.length <= IV_LEN) {
            throw new IllegalStateException("wrapped data key too short");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(master, "AES"), new GCMParameterSpec(128, blob, 0, IV_LEN));
            c.updateAAD(aad(context));
            return c.doFinal(blob, IV_LEN, blob.length - IV_LEN);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("data key cannot be unwrapped (wrong master key or encryption context)");
        }
    }

    @Override
    public WrappedKey wrap(byte[] plaintext, Map<String, String> context) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masters.get(current), "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(aad(context));
            byte[] ct = c.doFinal(plaintext);
            return new WrappedKey(current, ByteBuffer.allocate(IV_LEN + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("data key wrap failed", e);
        }
    }

    @Override
    public WrappedKey reWrap(WrappedKey wrapped, Map<String, String> context) {
        byte[] plain = decrypt(wrapped, context);
        try {
            return wrap(plain, context);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    @Override
    public String currentMasterRef() {
        return current;
    }

    @Override
    public String describe() {
        List<String> refs = new ArrayList<>(masters.keySet());
        return "software master key " + current + (refs.size() > 1 ? " (also accepts " + refs.subList(1, refs.size()) + ")" : "");
    }

    /** Canonical encryption context (sorted key=value lines) bound as AAD. */
    static byte[] aad(Map<String, String> context) {
        StringBuilder b = new StringBuilder();
        new TreeMap<>(context).forEach((k, v) -> b.append(k).append('=').append(v).append('\n'));
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }
}
