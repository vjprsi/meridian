package io.meridian.cardvault.infrastructure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * PKCS#11 HSM adapter (GAP-01) for AWS CloudHSM, Thales Luna, Utimaco or SoftHSM2, through the JDK's SunPKCS11
 * provider. Configuration-driven: {@code meridian.cards.vault.pkcs11.config} is a SunPKCS11 configuration file
 * (library path, slot, and the key attribute template - generated secret keys must be {@code CKA_TOKEN=true},
 * {@code CKA_SENSITIVE=true}, {@code CKA_EXTRACTABLE=false}; see docs/operations/RUNBOOKS.md section 22), the user
 * PIN comes from {@code MERIDIAN_CARDS_VAULT_PKCS11_PIN} (a Secret, never git).
 *
 * <p>Every key lives on the token under the alias {@code <prefix><label>:v<version>}; the vault stores only metadata
 * (no wrapped key, master reference {@code pkcs11:<token>}). A key already present under that alias (a zone or
 * card verification key imported under dual control) is used as is; otherwise {@link #createKey} generates it on the
 * token (AES-256, or HMAC-SHA256 for the fingerprint and card verification keys). All operations run inside the
 * HSM: GCM, HMAC, and PIN translation as unwrap (zone key) + wrap (PIN encryption key, AES-KWP) of a sensitive
 * session object, so the clear PIN block never reaches the JVM. Master key rotation does not apply (the HSM's own
 * key hierarchy protects the token keys); {@link #rewrap} returns the key unchanged.
 *
 * <p>Tests drive the adapter with any JCA {@link Provider} and {@link KeyStore} (SunJCE + PKCS12), the same calls
 * SunPKCS11 serves on a real token.
 */
public class Pkcs11Hsm extends JcaHsm {
    /** Key types whose keys are HMAC keys on the token (all others are AES). */
    static final Set<String> HMAC_TYPES = Set.of("FINGERPRINT", "CVK");

    private final Provider provider;
    private final KeyStore token;
    private final KeyStore.ProtectionParameter entryProtection;
    private final String prefix;
    private final String tokenName;
    private final Map<String, SecretKey> handles = new ConcurrentHashMap<>();

    /**
     * @param entryProtection protection of stored key entries: null for a PKCS#11 token (the login protects it)
     * @param pinBlockKeyAlgorithm key algorithm name of unwrapped PIN blocks (a generic secret on the token)
     */
    public Pkcs11Hsm(Provider provider, KeyStore token, KeyStore.ProtectionParameter entryProtection, String prefix,
                     String tokenName, String pinBlockKeyAlgorithm) {
        super(pinBlockKeyAlgorithm);
        this.provider = provider;
        this.token = token;
        this.entryProtection = entryProtection;
        this.prefix = prefix == null ? "" : prefix;
        this.tokenName = tokenName == null || tokenName.isBlank() ? "token" : tokenName;
    }

    /** SunPKCS11 configured from {@code configFile}, logged in with {@code pin}. */
    public static Pkcs11Hsm sunPkcs11(Path configFile, char[] pin, String prefix, String pinBlockKeyAlgorithm) {
        if (configFile == null || !Files.isReadable(configFile)) {
            throw new IllegalStateException("meridian.cards.vault.pkcs11.config must name a readable SunPKCS11 "
                    + "configuration file (got " + configFile + ")");
        }
        if (pin == null || pin.length == 0) {
            throw new IllegalStateException("The HSM user PIN is required (MERIDIAN_CARDS_VAULT_PKCS11_PIN)");
        }
        Provider base = Security.getProvider("SunPKCS11");
        if (base == null) {
            throw new IllegalStateException("The SunPKCS11 provider is not available in this JVM");
        }
        Provider p = base.configure(configFile.toString());
        Security.addProvider(p);
        try {
            KeyStore ks = KeyStore.getInstance("PKCS11", p);
            ks.load(null, pin);
            return new Pkcs11Hsm(p, ks, null, prefix, p.getName(), pinBlockKeyAlgorithm);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot log in to the HSM token (" + p.getName() + "): " + e.getMessage(), e);
        }
    }

    String alias(KeyRef ref) {
        return prefix + ref.name();
    }

    @Override
    protected SecretKey key(KeyRef ref) {
        String alias = alias(ref);
        SecretKey k = handles.get(alias);
        if (k != null) {
            return k;
        }
        try {
            Key found = token.getKey(alias, passwordOf(entryProtection));
            if (!(found instanceof SecretKey sk)) {
                throw new IllegalStateException("HSM key " + alias + " not found on " + tokenName);
            }
            handles.put(alias, sk);
            return sk;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HSM key " + alias + " cannot be loaded: " + e.getMessage(), e);
        }
    }

    @Override
    protected Provider provider() {
        return provider;
    }

    @Override
    public KeyMaterial createKey(KeyRef ref) {
        String alias = alias(ref);
        try {
            if (!token.containsAlias(alias)) {
                KeyGenerator g = KeyGenerator.getInstance(HMAC_TYPES.contains(ref.type()) ? "HmacSHA256" : "AES", provider);
                g.init(256);
                SecretKey k = g.generateKey();
                token.setEntry(alias, new KeyStore.SecretKeyEntry(k), entryProtection);
                handles.remove(alias);
            }
            return new KeyMaterial(null, currentMasterRef(), kcv(key(ref)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HSM key generation for " + alias + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public KeyMaterial rewrap(KeyRef ref) {
        return new KeyMaterial(null, currentMasterRef(), kcv(key(ref)));
    }

    @Override
    public String currentMasterRef() {
        return "pkcs11:" + tokenName;
    }

    @Override
    public boolean pinInsideHsm() {
        return true;
    }

    @Override
    public String describe() {
        return "PKCS#11 HSM " + tokenName + " (keys and PIN translation inside the HSM)";
    }

    /** PIN blocks are never extracted from the token, so their format cannot be checked here. */
    @Override
    protected void checkClearPinBlock(Key unwrappedBlock, String pan) {
        // the HSM keeps the block sensitive (getEncoded() is null); a malformed block simply never matches
    }

    private static char[] passwordOf(KeyStore.ProtectionParameter p) {
        return p instanceof KeyStore.PasswordProtection pp ? pp.getPassword() : null;
    }
}
