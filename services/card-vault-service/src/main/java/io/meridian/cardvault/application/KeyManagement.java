package io.meridian.cardvault.application;

import java.util.Map;

/**
 * Envelope-encryption port (GAP-01): a master key that never leaves the key management service generates and wraps
 * the vault's data keys. One data key per tenant and purpose (key type) and key version; the encryption context
 * {@code {tenant, purpose, key}} is bound to every wrap, so a wrapped key copied to another tenant's or purpose's
 * row cannot be unwrapped. Adapters: AWS KMS ({@code AwsKmsKeyManagement}, GenerateDataKey / Decrypt / Encrypt /
 * ReEncrypt) and a software master key for development ({@code SoftwareKeyManagement}).
 */
public interface KeyManagement {

    /** A data key encrypted under a master key; {@code masterRef} names that master key (KMS key ARN, software id). */
    record WrappedKey(String masterRef, byte[] ciphertext) {}

    /** A new data key: the clear key (callers zeroise it) and its wrapped form for storage. */
    record DataKey(byte[] plaintext, WrappedKey wrapped) {}

    /** Generates a new 256-bit data key under the current master key. */
    DataKey generateDataKey(Map<String, String> context);

    /** Unwraps a stored data key; fails when the context differs from the one it was wrapped with. */
    byte[] decrypt(WrappedKey wrapped, Map<String, String> context);

    /** Wraps existing key material (adoption of a legacy derived key) under the current master key. */
    WrappedKey wrap(byte[] plaintext, Map<String, String> context);

    /** Master key rotation: the same data key wrapped under the current master key (KMS: ReEncrypt, server side). */
    WrappedKey reWrap(WrappedKey wrapped, Map<String, String> context);

    /** The master key new data keys are wrapped under. */
    String currentMasterRef();

    /** For logs (never key material). */
    String describe();
}
