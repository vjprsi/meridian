package io.meridian.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IbanAndBicTest {

    @Test
    void validIbansFromTheRegistry() {
        assertThat(Iban.isValid("GB29 NWBK 6016 1331 9268 19")).isTrue();
        assertThat(Iban.isValid("DE89370400440532013000")).isTrue();
        assertThat(Iban.isValid("fr1420041010050500013m02606")).isTrue();
        assertThat(Iban.isValid("NL91ABNA0417164300")).isTrue();
    }

    @Test
    void invalidIbansReportStableCodes() {
        assertThat(Iban.validate("GB29NWBK60161331926818")).contains("invalid_iban_checksum");
        assertThat(Iban.validate("GB29NWBK6016133192681")).contains("invalid_iban_length");
        assertThat(Iban.validate("ZZ29NWBK60161331926819")).contains("iban_country_not_supported");
        assertThat(Iban.validate("GB2!NWBK60161331926819")).contains("invalid_iban_format");
        assertThat(Iban.validate(null)).contains("invalid_iban_length");
    }

    @Test
    void checkDigitsAreComputed() {
        assertThat(Iban.of("GB", "NWBK60161331926819")).isEqualTo("GB29NWBK60161331926819");
        assertThat(Iban.of("DE", "370400440532013000")).isEqualTo("DE89370400440532013000");
        assertThat(Iban.isValid(Iban.of("GB", "MERI00000100000001"))).isTrue();
    }

    @Test
    void bankCodeAndMasking() {
        assertThat(Iban.bankCode("GB29NWBK60161331926819")).contains("NWBK601613");
        assertThat(Iban.bankCode("DE89370400440532013000")).contains("37040044");
        assertThat(Iban.country("de89370400440532013000")).isEqualTo("DE");
        assertThat(Iban.mask("GB29NWBK60161331926819")).isEqualTo("GB29**************6819");
    }

    @Test
    void bics() {
        assertThat(Bic.isValid("DEUTDEFF")).isTrue();
        assertThat(Bic.isValid("deutdeff500")).isTrue();
        assertThat(Bic.isValid("DEUTDEF")).isFalse();
        assertThat(Bic.isValid("1EUTDEFF")).isFalse();
        assertThat(Bic.toBic11("DEUTDEFF")).isEqualTo("DEUTDEFFXXX");
        assertThat(Bic.country("NWBKGB2L")).isEqualTo("GB");
    }
}
