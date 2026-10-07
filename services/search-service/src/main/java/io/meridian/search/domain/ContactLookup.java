package io.meridian.search.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Exact-match search by e-mail or phone without indexing either: customer-service publishes verified contacts with a
 * {@code lookup_hash} = HMAC-SHA256({@code "contact:" + normalised value}) under a key both services share
 * ({@code meridian.contact-lookup.key}); a query that looks like an e-mail or a phone number is hashed the same way and
 * matched against the indexed identifier. Must stay byte-identical to customer-service's {@code ContactLookup}
 * (shared test vectors in both services' {@code ContactLookupTest}).
 *
 * <p>Normalisation: an e-mail is trimmed and lower-cased; a phone keeps its digits only, without a leading
 * international {@code 00}. A national-format number (e.g. {@code 0712...}) does not match an E.164 contact.
 */
public final class ContactLookup {
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[\\d\\s().-]{7,24}$");

    private final byte[] key;

    /** {@code key} null or blank disables contact lookup (hash returns null). */
    public ContactLookup(String key) {
        if (key != null && !key.isBlank() && key.length() < 16) {
            throw new IllegalStateException("meridian.contact-lookup.key must be at least 16 characters");
        }
        this.key = key == null || key.isBlank() ? null : key.getBytes(StandardCharsets.UTF_8);
    }

    /** Normalised contact value, or null when it is neither an e-mail nor has enough digits for a phone. */
    public static String normalise(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.indexOf('@') > 0) {
            return v.toLowerCase(Locale.ROOT);
        }
        String digits = v.replaceAll("\\D", "");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        return digits.length() >= 7 ? digits : null;
    }

    /** True when a whole query is an e-mail address or a phone number (so it is worth hashing). */
    public static boolean looksLikeContact(String query) {
        if (query == null) {
            return false;
        }
        String q = query.trim();
        return EMAIL.matcher(q).matches() || (PHONE.matcher(q).matches() && q.replaceAll("\\D", "").length() >= 7);
    }

    /** Hex HMAC of the normalised value; null when lookup is disabled or the value is not a contact. */
    public String hash(String value) {
        String n = normalise(value);
        if (key == null || n == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("contact:" + n).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The tsquery extended with the contact's lookup hash as an alternative ({@code (q) | hash}) when the query text is
     * an e-mail or phone and lookup is enabled; the tsquery unchanged otherwise.
     */
    public String extend(String tsquery, String queryText) {
        String hash = looksLikeContact(queryText) ? hash(queryText) : null;
        if (hash == null) {
            return tsquery;
        }
        return tsquery == null ? hash : "(" + tsquery + ") | " + hash;
    }
}
