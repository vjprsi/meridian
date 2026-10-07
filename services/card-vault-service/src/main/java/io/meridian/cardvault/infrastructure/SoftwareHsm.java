package io.meridian.cardvault.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The original derived-key scheme, kept as the key derivation function of the development key management and for
 * keys created before envelope encryption (GAP-01): {@code key = HMAC-SHA256(master, "key:" + label + ":v" + version)}.
 * Every key is computed from one master key, so there is no per-key material to store, and rotating the master key
 * would change every key; {@link EnvelopeHsm} adopts such keys (wraps the derived material once) so the master key
 * can then be rotated by re-wrapping. Not used as the vault's {@code Hsm} bean any more.
 */
public class SoftwareHsm extends JcaHsm {
    /** Reference used for derived (not wrapped) keys. */
    public static final String DERIVED = "derived";

    private final byte[] master;
    private final Map<String, SecretKeySpec> keys = new ConcurrentHashMap<>();

    public SoftwareHsm(String masterKeyBase64) {
        this(decode(masterKeyBase64));
    }

    /** @param masterKey 32-byte master key (copied; the caller may zeroise its array) */
    public SoftwareHsm(byte[] masterKey) {
        super("GenericSecret");
        if (masterKey == null || masterKey.length != 32) {
            throw new IllegalStateException("The card-vault master key must be 32 bytes");
        }
        this.master = masterKey.clone();
    }

    private static byte[] decode(String masterKeyBase64) {
        if (masterKeyBase64 == null || masterKeyBase64.isBlank()) {
            throw new IllegalStateException("meridian.cards.vault.master-key is not configured");
        }
        return Base64.getDecoder().decode(masterKeyBase64.trim());
    }

    /** Key derivation shared with tests and clients that wrap PIN blocks in the sandbox. */
    public static byte[] deriveKey(byte[] master, String label, int version) {
        return hmac(master, ("key:" + label + ":v" + version).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected SecretKey key(KeyRef ref) {
        return keys.computeIfAbsent(ref.name(), n -> new SecretKeySpec(deriveKey(master, ref.label(), ref.version()), "AES"));
    }

    @Override
    protected Provider provider() {
        return null;
    }

    @Override
    public KeyMaterial createKey(KeyRef key) {
        return new KeyMaterial(null, DERIVED, kcv(key));
    }

    @Override
    public KeyMaterial rewrap(KeyRef key) {
        return new KeyMaterial(null, DERIVED, kcv(key));
    }

    @Override
    public String currentMasterRef() {
        return DERIVED;
    }

    @Override
    public boolean pinInsideHsm() {
        return false;
    }

    @Override
    public String describe() {
        return "derived keys from one software master key (legacy)";
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
