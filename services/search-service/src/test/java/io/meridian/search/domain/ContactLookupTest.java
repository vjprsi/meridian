package io.meridian.search.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Same vectors as customer-service's ContactLookupTest: the two implementations must hash identically. */
class ContactLookupTest {
    static final ContactLookup LOOKUP = new ContactLookup("test-contact-lookup-key-0123456789");

    @Test
    void hashesLikeCustomerService() {
        assertThat(LOOKUP.hash("+254 712 345 678")).isEqualTo("6376a913caa0cb1e19483ec430b1a474dc67a65a5556a556e1eeff7d0a93af07");
        assertThat(LOOKUP.hash("00254712345678")).isEqualTo("6376a913caa0cb1e19483ec430b1a474dc67a65a5556a556e1eeff7d0a93af07");
        assertThat(LOOKUP.hash(" Amina.Odhiambo@Example.COM"))
                .isEqualTo("0770f7b9dfe6a8fb4683deac301825418e615d486de412fbb85a3729de785c8f");
        assertThat(new ContactLookup(null).hash("+254712345678")).isNull();
    }

    @Test
    void onlyWholeContactsExtendTheQuery() {
        assertThat(ContactLookup.looksLikeContact("amina@example.com")).isTrue();
        assertThat(ContactLookup.looksLikeContact("+44 (7700) 900-123")).isTrue();
        assertThat(ContactLookup.looksLikeContact("amina odhiambo")).isFalse();
        assertThat(ContactLookup.looksLikeContact("1234")).isFalse();
        assertThat(ContactLookup.looksLikeContact("INV-7781")).isFalse();
        assertThat(LOOKUP.extend("amina & odhiambo:*", "amina odhiambo")).isEqualTo("amina & odhiambo:*");
        assertThat(LOOKUP.extend("a & example & com:*", "a@example.com"))
                .isEqualTo("(a & example & com:*) | " + LOOKUP.hash("A@Example.com"));
        assertThat(new ContactLookup("").extend("x", "a@example.com")).isEqualTo("x");
    }
}
