package io.meridian.fraud.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Tenant-keyed pseudonyms of personal identifiers (account numbers, IBANs, card numbers or fragments, phone numbers,
 * e-mail addresses, device ids, national ids). fraud-service never stores or computes features on such a value in
 * clear: it stores HMAC-SHA-256 under a key derived per tenant from the service's pseudonymisation secret
 * ({@code meridian.fraud.pseudonym-key}). The same value of the same tenant always gives the same pseudonym (feature
 * keys, list matches and rule comparisons keep working); without the secret a pseudonym cannot be reversed by
 * enumerating account or phone numbers, unlike a plain SHA-256; two tenants never share a pseudonym.
 *
 * <p>Every pseudonym is derived from the identifier's canonical digest, {@link ListValues#hash} ({@code sha256(TYPE ":"
 * normalised value)}), so a caller may send the digest instead of the value ({@code *_digest} context fields, e.g.
 * payment-service's {@code beneficiary_account_digest}) and an already stored digest (fraud list entries) can be
 * re-pseudonymised without the clear value.
 */
public final class Pseudonyms {
    /** Version marker of keyed list hashes ({@code fraud_list_entry.value_hash}). */
    public static final String LIST_PREFIX = "h1:";
    /** Prefix of pseudonymised values in {@code fraud_score.features}. */
    public static final String VALUE_PREFIX = "psn:";
    /** Beneficiary feature key of an account number (+ BIC). */
    public static final String ACCOUNT_KEY_PREFIX = "acct:h1:";
    /** Beneficiary feature key of a mobile number. */
    public static final String MSISDN_KEY_PREFIX = "msisdn:h1:";

    private final SecretKeySpec key;

    private Pseudonyms(byte[] tenantKey) {
        this.key = new SecretKeySpec(tenantKey, "HmacSHA256");
    }

    /** Pseudonyms of one tenant. */
    public static Pseudonyms of(byte[] tenantKey) {
        if (tenantKey == null || tenantKey.length < 32) {
            throw new IllegalArgumentException("tenant pseudonymisation key must have at least 32 bytes");
        }
        return new Pseudonyms(tenantKey.clone());
    }

    /** The tenant's key: HMAC-SHA-256(secret, "meridian-fraud-pseudonym/v1|" + tenant). */
    public static byte[] tenantKey(byte[] secret, UUID tenant) {
        return hmac(new SecretKeySpec(secret, "HmacSHA256"), "meridian-fraud-pseudonym/v1|" + tenant);
    }

    /** Pseudonym of a clear identifier of the given list entity type ({@code psn:} + 128 bits). */
    public String value(String entityType, String raw) {
        return raw == null || raw.isBlank() ? null : valueOfDigest(ListValues.hash(entityType, raw));
    }

    /** Pseudonym of an identifier given as its canonical digest (same result as {@link #value} of the clear value). */
    public String valueOfDigest(String digest) {
        return digest == null ? null : VALUE_PREFIX + hex("value|" + digest.toLowerCase(Locale.ROOT), 16);
    }

    /** Keyed fraud-list hash of a canonical digest ({@code h1:} + 256 bits). */
    public String listHash(String digest) {
        return digest == null ? null : LIST_PREFIX + hex("list|" + digest.toLowerCase(Locale.ROOT), 32);
    }

    /** Beneficiary feature key of an account number given as its BENEFICIARY_ACCOUNT digest, with the BIC if known. */
    public String accountKey(String accountDigest, String bic) {
        String b = bic == null ? "" : bic.trim().toUpperCase(Locale.ROOT);
        return ACCOUNT_KEY_PREFIX + hex("acct|" + accountDigest.toLowerCase(Locale.ROOT) + "|" + b, 20);
    }

    /** Beneficiary feature key of a mobile number (digits only), or null without digits. */
    public String msisdnKey(String msisdn) {
        String digits = msisdn == null ? "" : msisdn.replaceAll("\\D", "");
        return digits.isEmpty() ? null : MSISDN_KEY_PREFIX + hex("msisdn|" + digits, 20);
    }

    /** True for a value this class produced ({@code psn:}). */
    public static boolean isValuePseudonym(String v) {
        return v != null && v.startsWith(VALUE_PREFIX) && v.length() == VALUE_PREFIX.length() + 32;
    }

    /** True for a 64-character hex digest (a {@link ListValues#hash}). */
    public static boolean isDigest(String v) {
        return v != null && v.length() == 64 && v.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F'));
    }

    private String hex(String material, int bytes) {
        return HexFormat.of().formatHex(hmac(key, material), 0, bytes);
    }

    private static byte[] hmac(SecretKeySpec k, String material) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(k);
            return mac.doFinal(material.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
