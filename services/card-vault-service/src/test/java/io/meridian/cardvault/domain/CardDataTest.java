package io.meridian.cardvault.domain;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.NumericChars;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

class CardDataTest {

    @Test
    void luhnKnownNumbers() {
        assertThat(CardData.luhnValid("4111111111111111")).isTrue();
        assertThat(CardData.luhnValid("5555555555554444")).isTrue();
        assertThat(CardData.luhnValid("378282246310005")).isTrue();
        assertThat(CardData.luhnValid("4111111111111112")).isFalse();
        assertThat(CardData.luhnValid("41111a1111111111")).isFalse();
        assertThat(CardData.luhnValid("")).isFalse();
    }

    @Test
    void panRules() {
        assertThat(CardData.panValid("4111111111111111")).isTrue();
        assertThat(CardData.panValid("411111111111")).isFalse(); // 12 digits
        assertThat(CardData.panValid("41111111111111111111")).isFalse(); // 20 digits
        assertThat(CardData.bin("4111111111111111")).isEqualTo("411111");
        assertThat(CardData.last4("4111111111111111")).isEqualTo("1111");
    }

    @Test
    void expiryAndCvvRules() {
        assertThat(CardData.expiryValid(12, 2030)).isTrue();
        assertThat(CardData.expiryValid(13, 2030)).isFalse();
        assertThat(CardData.expiryValid(0, 2030)).isFalse();
        assertThat(CardData.expiryValid(1, 30)).isFalse();
        assertThat(CardData.cvvValid("123")).isTrue();
        assertThat(CardData.cvvValid("1234")).isTrue();
        assertThat(CardData.cvvValid("12")).isFalse();
        assertThat(CardData.cvvValid("12a")).isFalse();
        String stored = CardData.expiry(3, 2031);
        assertThat(stored).isEqualTo("03/2031");
        assertThat(CardData.expiryMonth(stored)).isEqualTo(3);
        assertThat(CardData.expiryYear(stored)).isEqualTo(2031);
    }

    @Property
    void appendingTheCheckDigitAlwaysYieldsALuhnValidNumber(
            @ForAll @NumericChars @StringLength(min = 12, max = 18) String payload) {
        String pan = payload + CardData.luhnCheckDigit(payload);
        assertThat(CardData.luhnValid(pan)).isTrue();
    }

    @Property
    void changingOneDigitBreaksLuhn(@ForAll @NumericChars @StringLength(min = 12, max = 18) String payload,
                                    @ForAll @IntRange(min = 0, max = 30) int pos, @ForAll @IntRange(min = 1, max = 9) int delta) {
        String pan = payload + CardData.luhnCheckDigit(payload);
        int i = pos % pan.length();
        char changed = (char) ('0' + (pan.charAt(i) - '0' + delta) % 10);
        String tampered = pan.substring(0, i) + changed + pan.substring(i + 1);
        assertThat(CardData.luhnValid(tampered)).isFalse();
    }
}
