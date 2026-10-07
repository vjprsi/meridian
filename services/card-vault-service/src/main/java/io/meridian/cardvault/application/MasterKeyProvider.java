package io.meridian.cardvault.application;

/**
 * Port supplying the software HSM's 32-byte master key (SEC-01). Key material never comes from committed
 * configuration outside {@code meridian.security-mode=dev}.
 *
 * <p>{@code infrastructure.MasterKeyProviders} reads it from the environment (a Kubernetes Secret injected as
 * {@code MERIDIAN_CARDS_VAULT_MASTER_KEY}) or from a mounted file ({@code meridian.cards.vault.master-key-file}, e.g.
 * a CSI secret-store / KMS-decrypted volume), and only in dev mode falls back to a clearly labelled local key.
 * A KMS or HSM adapter (unwrapping a stored data key with a KMS call, or a PKCS#11 session) is a bean implementing
 * this interface; a network HSM replaces {@link Hsm} altogether and needs no master key at all.
 */
public interface MasterKeyProvider {

    /** A fresh copy of the 32-byte master key (callers zeroise it after use). */
    byte[] masterKey();

    /** Where the key came from (for the startup log; never the key itself). */
    String source();
}
