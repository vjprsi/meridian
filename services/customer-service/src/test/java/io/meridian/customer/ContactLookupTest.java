package io.meridian.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.customer.infrastructure.ContactLookup;
import org.junit.jupiter.api.Test;

/**
 * The contact lookup hash must be byte-identical to search-service's ContactLookup (same vectors in its
 * ContactLookupTest), or search by e-mail / phone finds nothing.
 */
class ContactLookupTest {
    static final String KEY = "test-contact-lookup-key-0123456789";

    @Test
    void normalisesAndHashesLikeSearchService() {
        ContactLookup lookup = new ContactLookup(KEY);
        assertThat(ContactLookup.normalise("+254 712 345 678")).isEqualTo("254712345678");
        assertThat(ContactLookup.normalise("00254712345678")).isEqualTo("254712345678");
        assertThat(ContactLookup.normalise("  Amina.Odhiambo@Example.COM ")).isEqualTo("amina.odhiambo@example.com");
        assertThat(ContactLookup.normalise("12-34")).isNull();
        assertThat(lookup.hash("+254712345678")).isEqualTo("6376a913caa0cb1e19483ec430b1a474dc67a65a5556a556e1eeff7d0a93af07");
        assertThat(lookup.hash("Amina.Odhiambo@example.com"))
                .isEqualTo("0770f7b9dfe6a8fb4683deac301825418e615d486de412fbb85a3729de785c8f");
        assertThat(new ContactLookup("another-key-0123456789").hash("+254712345678"))
                .isNotEqualTo(lookup.hash("+254712345678"));
        assertThat(new ContactLookup("").hash("+254712345678")).isNull(); // no key: no hash, masked value only
        assertThatThrownBy(() -> new ContactLookup("short")).hasMessageContaining("at least 16");
    }
}
