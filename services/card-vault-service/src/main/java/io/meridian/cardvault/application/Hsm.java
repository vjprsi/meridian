package io.meridian.cardvault.application;

/**
 * Port to the hardware security module. Keys are addressed by label and version; key material never leaves the
 * implementation. A network HSM (Thales / Utimaco / cloud HSM) replaces the software implementation in production.
 */
public interface Hsm {

    /** A key inside the HSM. */
    record KeyRef(String label, int version) {}

    /** AES-256-GCM: returns IV (12 bytes) || ciphertext+tag. {@code aad} binds the ciphertext to its record/field. */
    byte[] encrypt(KeyRef key, byte[] plain, byte[] aad);

    /** Inverse of {@link #encrypt}; throws {@link IllegalStateException} when the blob was tampered with. */
    byte[] decrypt(KeyRef key, byte[] blob, byte[] aad);

    /** Key check value: first 6 hex chars of AES-ECB(key, 16 zero bytes). */
    String kcv(KeyRef key);

    /** Keyed fingerprint (hex HMAC-SHA256 under the fingerprint key) of a sensitive value, e.g. the PAN. */
    String fingerprint(String value);

    /** Deterministic 3-digit CVV2 under the CVK for PAN + expiry. */
    String deriveCvv(KeyRef cvk, String pan, int month, int year);

    /**
     * Unwraps a PIN block sent as base64(IV || AES-GCM ciphertext) under the zone key; returns the 8-byte clear
     * ISO 9564 block. Throws {@link IllegalArgumentException} when it is malformed or cannot be authenticated.
     */
    byte[] unwrapPinBlock(KeyRef zpk, String wrappedBase64);
}
