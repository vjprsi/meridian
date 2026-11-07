package io.meridian.brebsim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Key normalisation / validation and name masking of the Bre-B simulator. */
class BrebSimTest {

    @Test
    void keysAreNormalisedPerTypeAndInvalidValuesRejected() {
        assertThat(BrebKeys.normalise("CELULAR", "+57 312 576 4304")).isEqualTo("3125764304");
        assertThat(BrebKeys.normalise("CELULAR", "573125764304")).isEqualTo("3125764304");
        assertThat(BrebKeys.normalise("CELULAR", "2125764304")).isNull();
        assertThat(BrebKeys.normalise("CORREO", "Vijay.P@Example.COM")).isEqualTo("vijay.p@example.com");
        assertThat(BrebKeys.normalise("CORREO", "no-at-sign.com")).isNull();
        assertThat(BrebKeys.normalise("DOCUMENTO", "1020304050")).isEqualTo("1020304050");
        assertThat(BrebKeys.normalise("DOCUMENTO", "12")).isNull();
        assertThat(BrebKeys.normalise("ALFANUMERICA", "@VijayPay")).isEqualTo("@vijaypay");
        assertThat(BrebKeys.normalise("ALFANUMERICA", "vijaypay")).isNull();
        assertThat(BrebKeys.normalise("COMERCIO", "1234567")).isEqualTo("1234567");
        assertThat(BrebKeys.normalise("PIX", "x")).isNull();
    }

    @Test
    void holderNamesAreMasked() {
        assertThat(BrebKeys.mask("Juan Carlos Pérez")).isEqualTo("Jua* Car*** Pér**");
        assertThat(BrebKeys.mask("Al")).isEqualTo("Al*");
        assertThat(BrebKeys.mask(null)).isEqualTo("***");
    }
}
