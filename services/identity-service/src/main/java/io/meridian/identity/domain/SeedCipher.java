package io.meridian.identity.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * SEC-25: TOTP seeds at rest. {@code v1:<base64(iv || AES-256-GCM(seed))>} with the factor id as AAD (a seed copied
 * to another factor row does not decrypt). The key is SHA-256 of {@code meridian.identity.mfa-seed-key} (a secret
 * from the vault / KMS; a startup guard refuses committed dev values outside dev mode). Values without the prefix
 * are seeds stored before the change (plain Base32) and are re-encrypted on their next use.
 */
public final class SeedCipher {
    private static final String PREFIX = "v1:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public SeedCipher(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("meridian.identity.mfa-seed-key is not configured");
        }
        try {
            key = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String encrypt(String seed, String factorId) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            c.updateAAD(factorId.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(seed.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("seed encryption failed", e);
        }
    }

    public String decrypt(String stored, String factorId) {
        if (stored == null) {
            return null;
        }
        if (!isEncrypted(stored)) {
            return stored; // legacy plaintext seed
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            c.updateAAD(factorId.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("seed decryption failed", e);
        }
    }

    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }
}
