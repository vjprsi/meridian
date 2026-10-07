package io.meridian.cardvault.infrastructure;

import io.meridian.cardvault.application.MasterKeyProvider;
import io.meridian.web.security.RequiredSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.core.env.Environment;

/**
 * Environment / file / dev-only {@link MasterKeyProvider}s and the startup rules for the card-vault master key.
 *
 * <ul>
 *   <li>{@code meridian.cards.vault.master-key-file}: path of a file holding the base64 key (or the 32 raw bytes)</li>
 *   <li>{@code meridian.cards.vault.master-key}: base64 key, injected from a Secret as
 *       {@code MERIDIAN_CARDS_VAULT_MASTER_KEY}; never committed</li>
 *   <li>neither, and {@code meridian.security-mode=dev}: the {@link #devOnly() local development key}</li>
 * </ul>
 * Outside dev mode the service refuses to start when no key is supplied, when it is not 32 bytes, or when it is the
 * development key (or looks like a committed development value).
 */
public final class MasterKeyProviders {
    public static final String KEY_PROPERTY = "meridian.cards.vault.master-key";
    public static final String FILE_PROPERTY = "meridian.cards.vault.master-key-file";
    /** The local development key: these 32 ASCII bytes are public (in git) and protect nothing. */
    static final String DEV_ONLY_KEY_LABEL = "meridian-dev-vault-master-key-01";

    private MasterKeyProviders() {}

    /** Picks the provider for this environment and validates the key it yields (fail fast at startup). */
    public static MasterKeyProvider fromEnvironment(Environment env) {
        boolean dev = RequiredSecrets.devMode(env);
        String file = env.getProperty(FILE_PROPERTY, "").trim();
        String value = env.getProperty(KEY_PROPERTY, "").trim();
        MasterKeyProvider p;
        if (!file.isEmpty()) {
            p = fromFile(Path.of(file));
        } else if (!value.isEmpty()) {
            p = fromBase64(value, "environment (" + KEY_PROPERTY + ")");
        } else if (dev) {
            p = devOnly();
        } else {
            throw new IllegalStateException("No card-vault master key: set MERIDIAN_CARDS_VAULT_MASTER_KEY from the secret "
                    + "store or " + FILE_PROPERTY + " (or provide a KMS/HSM MasterKeyProvider bean). A local development "
                    + "key is only used with meridian.security-mode=dev.");
        }
        validate(p, dev);
        return p;
    }

    /** Previous master keys (comma separated base64), accepted for unwrapping until the re-wrap job has run. */
    public static final String PREVIOUS_PROPERTY = "meridian.cards.vault.master-key-previous";

    /**
     * GAP-01 software master key rotation: the previous master keys still accepted for unwrapping data keys
     * ({@value #PREVIOUS_PROPERTY}, e.g. {@code MERIDIAN_CARDS_VAULT_MASTER_KEY_PREVIOUS}); each validated like the
     * current key.
     */
    public static java.util.List<MasterKeyProvider> previous(Environment env, boolean devMode) {
        java.util.List<MasterKeyProvider> out = new java.util.ArrayList<>();
        String v = env.getProperty(PREVIOUS_PROPERTY, "").trim();
        int i = 0;
        for (String part : v.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            MasterKeyProvider p = fromBase64(part, "environment (" + PREVIOUS_PROPERTY + "[" + i++ + "])");
            validate(p, devMode);
            out.add(p);
        }
        return out;
    }

    /** The configured key or file, if any (KMS / HSM modes: the legacy software key, only to adopt old keys). */
    public static MasterKeyProvider optional(Environment env) {
        String file = env.getProperty(FILE_PROPERTY, "").trim();
        String value = env.getProperty(KEY_PROPERTY, "").trim();
        if (!file.isEmpty()) {
            return fromFile(Path.of(file));
        }
        return value.isEmpty() ? null : fromBase64(value, "environment (" + KEY_PROPERTY + ")");
    }

    /** Checks length and, outside dev mode, that the key is not the development key. */
    public static void validate(MasterKeyProvider p, boolean devMode) {
        byte[] key = p.masterKey();
        try {
            if (key.length != 32) {
                throw new IllegalStateException("The card-vault master key must be 32 bytes (base64); got " + key.length
                        + " from " + p.source());
            }
            if (!devMode && (MessageDigest.isEqual(key, DEV_ONLY_KEY_LABEL.getBytes(StandardCharsets.US_ASCII))
                    || RequiredSecrets.isDevValue(new String(key, StandardCharsets.ISO_8859_1))
                    || RequiredSecrets.isDevValue(Base64.getEncoder().encodeToString(key)))) {
                throw new IllegalStateException("Refusing to start outside meridian.security-mode=dev with the "
                        + "development card-vault master key (" + p.source() + "); inject a random key from the KMS / "
                        + "secret store and re-encrypt the vault");
            }
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public static MasterKeyProvider fromBase64(String base64, String source) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("The card-vault master key from " + source + " is not valid base64");
        }
        try {
            return fixed(key, source);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public static MasterKeyProvider fromFile(Path path) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the card-vault master key file " + path, e);
        }
        String source = "file " + path;
        try {
            if (raw.length == 32) {
                return fixed(raw, source);
            }
            return fromBase64(new String(raw, StandardCharsets.US_ASCII).trim(), source);
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    /** The clearly labelled local development key (dev mode only). */
    public static MasterKeyProvider devOnly() {
        return fixed(DEV_ONLY_KEY_LABEL.getBytes(StandardCharsets.US_ASCII),
                "LOCAL DEVELOPMENT KEY (meridian.security-mode=dev; never use with real card data)");
    }

    private static MasterKeyProvider fixed(byte[] key, String source) {
        byte[] copy = key.clone();
        return new MasterKeyProvider() {
            @Override
            public byte[] masterKey() {
                return copy.clone();
            }

            @Override
            public String source() {
                return source;
            }
        };
    }
}
