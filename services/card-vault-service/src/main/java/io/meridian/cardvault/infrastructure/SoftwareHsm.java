package io.meridian.cardvault.infrastructure;

import io.meridian.cardvault.application.Hsm;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Software HSM for development and tests: every key is derived from one master key,
 * {@code key = HMAC-SHA256(master, "key:" + label + ":v" + version)}. Derived keys live only in memory.
 */
public class SoftwareHsm implements Hsm {
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final KeyRef FINGERPRINT = new KeyRef("fingerprint", 1);

    private final byte[] master;
    private final SecureRandom random = new SecureRandom();
    private final Map<KeyRef, SecretKeySpec> keys = new ConcurrentHashMap<>();

    public SoftwareHsm(String masterKeyBase64) {
        if (masterKeyBase64 == null || masterKeyBase64.isBlank()) {
            throw new IllegalStateException("meridian.cards.vault.master-key is not configured");
        }
        this.master = Base64.getDecoder().decode(masterKeyBase64.trim());
        if (master.length != 32) {
            throw new IllegalStateException("meridian.cards.vault.master-key must be 32 bytes (base64)");
        }
    }

    /** Key derivation shared with tests and clients that wrap PIN blocks in the sandbox. */
    public static byte[] deriveKey(byte[] master, String label, int version) {
        return hmac(master, ("key:" + label + ":v" + version).getBytes(StandardCharsets.UTF_8));
    }

    private SecretKeySpec key(KeyRef ref) {
        return keys.computeIfAbsent(ref, r -> new SecretKeySpec(deriveKey(master, r.label(), r.version()), "AES"));
    }

    @Override
    public byte[] encrypt(KeyRef key, byte[] plain, byte[] aad) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(key), new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null) {
                c.updateAAD(aad);
            }
            byte[] ct = c.doFinal(plain);
            return ByteBuffer.allocate(IV_LEN + ct.length).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    @Override
    public byte[] decrypt(KeyRef key, byte[] blob, byte[] aad) {
        if (blob == null || blob.length <= IV_LEN) {
            throw new IllegalStateException("ciphertext too short");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(key), new GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN));
            if (aad != null) {
                c.updateAAD(aad);
            }
            return c.doFinal(blob, IV_LEN, blob.length - IV_LEN);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed (wrong key or tampered data)");
        }
    }

    @Override
    public String kcv(KeyRef key) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(key));
            return HexFormat.of().withUpperCase().formatHex(c.doFinal(new byte[16])).substring(0, 6);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("kcv failed", e);
        }
    }

    @Override
    public String fingerprint(String value) {
        return HexFormat.of().formatHex(hmac(key(FINGERPRINT).getEncoded(), value.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public String deriveCvv(KeyRef cvk, String pan, int month, int year) {
        String yymm = String.format("%02d%02d", year % 100, month);
        byte[] mac = hmac(key(cvk).getEncoded(), (pan + "|" + yymm + "|cvv2").getBytes(StandardCharsets.UTF_8));
        int v = ByteBuffer.wrap(mac, 0, 4).getInt() & 0x7fffffff;
        return String.format("%03d", v % 1000);
    }

    @Override
    public byte[] unwrapPinBlock(KeyRef zpk, String wrappedBase64) {
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(wrappedBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("PIN block is not base64");
        }
        try {
            byte[] clear = decrypt(zpk, blob, null);
            if (clear.length != 8) {
                Arrays.fill(clear, (byte) 0);
                throw new IllegalArgumentException("PIN block must be 8 bytes");
            }
            return clear;
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException("PIN block cannot be unwrapped under the zone key");
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
