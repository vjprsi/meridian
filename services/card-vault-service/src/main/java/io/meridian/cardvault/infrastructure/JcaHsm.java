package io.meridian.cardvault.infrastructure;

import io.meridian.cardvault.application.Hsm;
import io.meridian.cardvault.domain.PinBlock;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.Provider;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The vault's cryptographic operations over the Java Cryptography Architecture, for any provider and key source:
 * {@link EnvelopeHsm} (KMS / software data keys, default providers) and {@link Pkcs11Hsm} (SunPKCS11: every
 * operation, including PIN translation, runs inside the HSM with non-extractable keys).
 *
 * <p>PIN translation never decrypts into a byte array: the zone-key ciphertext is <em>unwrapped</em> into a secret
 * key object ({@code C_UnwrapKey} on a PKCS#11 token: a sensitive session object) and that object is <em>wrapped</em>
 * under the PIN encryption key with AES-KWP ({@code C_WrapKey}), which is deterministic, so two blocks of the same
 * PIN and PAN compare equal as ciphertexts.
 */
public abstract class JcaHsm implements Hsm {
    static final int IV_LEN = 12;
    static final int TAG_BITS = 128;
    static final String GCM = "AES/GCM/NoPadding";
    static final String KWP = "AES/KWP/NoPadding";

    private final SecureRandom random = new SecureRandom();
    /** Algorithm name the unwrapped 8-byte PIN block object gets (PKCS#11: a generic secret). */
    private final String pinBlockKeyAlgorithm;

    protected JcaHsm(String pinBlockKeyAlgorithm) {
        this.pinBlockKeyAlgorithm = pinBlockKeyAlgorithm == null || pinBlockKeyAlgorithm.isBlank()
                ? "GenericSecret" : pinBlockKeyAlgorithm;
    }

    /** The key object for a reference (from the HSM token, or an unwrapped data key). */
    protected abstract SecretKey key(KeyRef ref);

    /** The provider every operation uses; null = the JVM's default providers. */
    protected abstract Provider provider();

    /**
     * Hook for adapters that may look at a clear PIN block (software / KMS modes): validates the ISO 9564 format 0
     * structure. An HSM adapter cannot (the block never leaves the HSM); a malformed block simply never matches.
     */
    protected void checkClearPinBlock(Key unwrappedBlock, String pan) {
        byte[] clear = unwrappedBlock.getEncoded();
        if (clear == null) {
            return;
        }
        try {
            String pin = PinBlock.decode(clear, pan);
            if (pin.isEmpty()) {
                throw new IllegalArgumentException("empty PIN");
            }
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    @Override
    public byte[] encrypt(KeyRef key, byte[] plain, byte[] aad) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = cipher(GCM);
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
            Cipher c = cipher(GCM);
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
    public String kcv(KeyRef ref) {
        return kcv(key(ref));
    }

    String kcv(SecretKey key) {
        try {
            byte[] out;
            if ("AES".equalsIgnoreCase(key.getAlgorithm())) {
                Cipher c = cipher("AES/ECB/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, key);
                out = c.doFinal(new byte[16]);
            } else {
                out = mac(key).doFinal(new byte[16]);
            }
            return HexFormat.of().withUpperCase().formatHex(out).substring(0, 6);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("kcv failed", e);
        }
    }

    @Override
    public String fingerprint(KeyRef key, String value) {
        try {
            return HexFormat.of().formatHex(mac(key(key)).doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("fingerprint failed", e);
        }
    }

    @Override
    public String deriveCvv(KeyRef cvk, String pan, int month, int year) {
        try {
            String yymm = String.format("%02d%02d", year % 100, month);
            byte[] m = mac(key(cvk)).doFinal((pan + "|" + yymm + "|cvv2").getBytes(StandardCharsets.UTF_8));
            int v = ByteBuffer.wrap(m, 0, 4).getInt() & 0x7fffffff;
            return String.format("%03d", v % 1000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cvv derivation failed", e);
        }
    }

    @Override
    public byte[] translatePinBlock(KeyRef zpk, String wrappedBase64, KeyRef pek, String pan) {
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(wrappedBase64 == null ? "" : wrappedBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("PIN block is not base64");
        }
        if (blob.length <= IV_LEN) {
            throw new IllegalArgumentException("PIN block too short");
        }
        Key block;
        try {
            Cipher c = cipher(GCM);
            c.init(Cipher.UNWRAP_MODE, key(zpk), new GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN));
            block = c.unwrap(Arrays.copyOfRange(blob, IV_LEN, blob.length), pinBlockKeyAlgorithm, Cipher.SECRET_KEY);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalArgumentException("PIN block cannot be unwrapped under the zone key");
        }
        byte[] encoded = block.getEncoded();
        if (encoded != null) {
            int len = encoded.length;
            Arrays.fill(encoded, (byte) 0);
            if (len != 8) {
                throw new IllegalArgumentException("PIN block must be 8 bytes");
            }
        }
        checkClearPinBlock(block, pan);
        try {
            Cipher w = cipher(KWP);
            w.init(Cipher.WRAP_MODE, key(pek));
            return w.wrap(block);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PIN translation failed", e);
        }
    }

    @Override
    public byte[] protectReferencePinBlock(KeyRef pek, byte[] clearBlock) {
        try {
            Cipher c = cipher(KWP);
            c.init(Cipher.ENCRYPT_MODE, key(pek));
            return c.doFinal(clearBlock);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("reference PIN block encryption failed", e);
        }
    }

    @Override
    public byte[] retranslatePin(KeyRef fromPek, byte[] stored, KeyRef toPek) {
        try {
            Cipher u = cipher(KWP);
            u.init(Cipher.UNWRAP_MODE, key(fromPek));
            Key block = u.unwrap(stored, pinBlockKeyAlgorithm, Cipher.SECRET_KEY);
            Cipher w = cipher(KWP);
            w.init(Cipher.WRAP_MODE, key(toPek));
            return w.wrap(block);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PIN re-translation failed (wrong PIN key or tampered block)");
        }
    }

    private Cipher cipher(String transformation) throws GeneralSecurityException {
        Provider p = provider();
        return p == null ? Cipher.getInstance(transformation) : Cipher.getInstance(transformation, p);
    }

    private Mac mac(SecretKey key) throws GeneralSecurityException {
        Provider p = provider();
        Mac m = p == null ? Mac.getInstance("HmacSHA256") : Mac.getInstance("HmacSHA256", p);
        m.init(key);
        return m;
    }
}
