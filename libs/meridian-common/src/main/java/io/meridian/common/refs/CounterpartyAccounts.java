package io.meridian.common.refs;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Tenant-keyed pseudonyms of counterparty account identifiers (IBAN, account number, wallet id) carried as payment
 * references on ledger postings. The ledger never stores a counterparty account in clear: it stores
 * {@code h1:<HMAC-SHA-256>} under a key derived per tenant from the shared secret {@code meridian.refs.counterparty-key}
 * plus the last four characters for display. Reconciliation computes the same pseudonym from the account stated on a
 * bank statement, so the two sides can be compared for exact equality without the ledger holding the account.
 *
 * <p>Normalisation: whitespace, dashes, dots and slashes removed, upper-cased ({@code "DE89 3704-0044"} and
 * {@code "de8937040044"} give the same pseudonym). Two tenants never share a pseudonym; without the secret a pseudonym
 * cannot be reversed by enumerating account numbers.
 */
public final class CounterpartyAccounts {
    /** Version prefix of the pseudonyms. */
    public static final String PREFIX = "h1:";
    /** Development default of {@code meridian.refs.counterparty-key}; refused outside security-mode=dev. */
    public static final String DEV_KEY = "dev-only-refs-counterparty-key-not-for-production";

    private final byte[] secret;

    public CounterpartyAccounts(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("meridian.refs.counterparty-key is not set");
        }
        this.secret = secret.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** Canonical form of an account identifier, or null when nothing is left. */
    public static String normalize(String account) {
        if (account == null) {
            return null;
        }
        String n = account.replaceAll("[\\s\\-./]", "").toUpperCase(Locale.ROOT);
        return n.isEmpty() ? null : n;
    }

    /** Last four characters of the normalised account (display only), or null. */
    public static String last4(String account) {
        String n = normalize(account);
        return n == null ? null : n.length() <= 4 ? n : n.substring(n.length() - 4);
    }

    /** True when the value already is a pseudonym (so callers do not hash twice). */
    public static boolean isPseudonym(String value) {
        return value != null && value.startsWith(PREFIX) && value.length() == PREFIX.length() + 64;
    }

    /** Pseudonym of the account for the tenant ({@code h1:} + 256-bit HMAC hex), or null without an account. */
    public String pseudonym(UUID tenant, String account) {
        String n = normalize(account);
        if (n == null) {
            return null;
        }
        if (tenant == null) {
            throw new IllegalStateException("A tenant is required to pseudonymise a counterparty account");
        }
        byte[] tenantKey = hmac(secret, "meridian-refs-counterparty/v1|" + tenant);
        return PREFIX + HexFormat.of().formatHex(hmac(tenantKey, "acct|" + n));
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
