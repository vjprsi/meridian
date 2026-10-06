package io.meridian.cardvault.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.NumericChars;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

class PinTest {

    @Test
    void iso9564Format0KnownVector() {
        // PIN 1234, PAN 4111111111111111: PIN field 041234FFFFFFFFFF, PAN field 0000111111111111
        byte[] block = PinBlock.encode("1234", "4111111111111111");
        assertThat(HexFormat.of().withUpperCase().formatHex(block)).isEqualTo("041225EEEEEEEEEE");
        assertThat(PinBlock.decode(block, "4111111111111111")).isEqualTo("1234");
    }

    @Property
    void format0RoundTrip(@ForAll @NumericChars @StringLength(min = 4, max = 12) String pin,
                          @ForAll @NumericChars @StringLength(min = 13, max = 19) String pan) {
        assertThat(PinBlock.decode(PinBlock.encode(pin, pan), pan)).isEqualTo(pin);
    }

    @Property
    void decodingWithAnotherPanNeverYieldsTheSamePinSilently(
            @ForAll @NumericChars @StringLength(min = 4, max = 12) String pin,
            @ForAll @NumericChars @StringLength(min = 16, max = 16) String pan) {
        String other = pan.substring(0, 3) + (char) ('0' + (pan.charAt(3) - '0' + 1) % 10) + pan.substring(4);
        byte[] block = PinBlock.encode(pin, pan);
        try {
            assertThat(PinBlock.decode(block, other)).isNotEqualTo(pin);
        } catch (IllegalArgumentException expected) {
            // malformed under the wrong PAN: rejected
        }
    }

    @Test
    void malformedBlocksAreRejected() {
        assertThatThrownBy(() -> PinBlock.decode(new byte[7], "4111111111111111"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PinBlock.decode(HexFormat.of().parseHex("FFFFFFFFFFFFFFFF"), "4111111111111111"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PinBlock.encode("12", "4111111111111111")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void weakPins() {
        assertThat(PinRules.weak("1111")).isTrue();
        assertThat(PinRules.weak("1234")).isTrue();
        assertThat(PinRules.weak("4321")).isTrue();
        assertThat(PinRules.weak("456789")).isTrue();
        assertThat(PinRules.weak("12a4")).isTrue();
        assertThat(PinRules.weak("2580")).isFalse();
        assertThat(PinRules.weak("7391")).isFalse();
        assertThat(PinRules.weak("1123")).isFalse();
    }
}
