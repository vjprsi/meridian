package io.meridian.webhook.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption at rest for webhook signing secrets (HMAC needs the plaintext, so they cannot be hashed).
 * Key: {@code meridian.webhooks.key} (base64, 32 bytes). Stored format: {@code v1:base64(iv[12] || ciphertext+tag)}.
 * The ciphertext is bound to the endpoint id via AAD so a blob cannot be swapped between endpoints.
 */
@Component
public class SecretCipher {
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${meridian.webhooks.key:}") String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("meridian.webhooks.key must be base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("meridian.webhooks.key must be a base64-encoded 32-byte AES key");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext, String aad) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("secret encryption failed", e);
        }
    }

    public String decrypt(String stored, String aad) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("unsupported secret format");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("secret decryption failed (wrong key or tampered data)", e);
        }
    }
}
