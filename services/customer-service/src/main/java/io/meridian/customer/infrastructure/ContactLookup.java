package io.meridian.customer.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Exact-match lookup key of a contact point (e-mail or phone) that can travel in events and be indexed by
 * search-service without the contact itself: HMAC-SHA256 over {@code "contact:" + normalised value} with a key shared
 * with search-service ({@code meridian.contact-lookup.key}, env {@code MERIDIAN_CONTACT_LOOKUP_KEY}). Search computes the
 * same hash from a query and finds the contact; nobody without the key can test guesses against indexed hashes.
 *
 * <p>Normalisation (identical in search-service {@code ContactLookup}): an e-mail is trimmed and lower-cased; a phone
 * keeps its digits only, without a leading international {@code 00} ({@code +254 712 345 678} and {@code 00254712345678}
 * both become {@code 254712345678}). Rotating the key requires re-verifying (or re-publishing) contacts.
 */
@Component
public class ContactLookup {
    private final byte[] key;

    public ContactLookup(@Value("${meridian.contact-lookup.key:}") String key) {
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

    /** Hex HMAC of the normalised value; null when no key is configured or the value is not a contact. */
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
}
