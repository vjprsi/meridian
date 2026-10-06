package io.meridian.merchant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmvQrTest {

    @Test
    void crc16CcittFalseCheckVector() {
        assertThat(EmvQr.crc16("123456789".getBytes(StandardCharsets.US_ASCII))).isEqualTo(0x29B1);
        assertThat(EmvQr.crcHex("123456789")).isEqualTo("29B1");
    }

    @Test
    void encodesDynamicQrAsTlvWithValidCrc() {
        String p = EmvQr.encode(new EmvQr.Fields(true, "1234567", "qr-1", "5411", "KES", new BigDecimal("250.50"),
                "KE", "Mama Mboga Grocers", "Nairobi", "INV-42", "T001"));
        assertThat(p).startsWith("000201010212");
        assertThat(p).contains("5303404").contains("5406250.50").contains("5802KE").contains("6007NAIROBI");
        assertThat(p).contains("26" + "34" + "0011io.meridian" + "01071234567" + "0204qr-1");
        assertThat(p.substring(p.length() - 8, p.length() - 4)).isEqualTo("6304");
        assertThat(EmvQr.crcValid(p)).isTrue();
        EmvQr.Fields f = EmvQr.decode(p);
        assertThat(f.dynamic()).isTrue();
        assertThat(f.merchantCode()).isEqualTo("1234567");
        assertThat(f.qrId()).isEqualTo("qr-1");
        assertThat(f.currency()).isEqualTo("KES");
        assertThat(f.amount()).isEqualByComparingTo("250.50");
        assertThat(f.name()).isEqualTo("MAMA MBOGA GROCERS");
        assertThat(f.reference()).isEqualTo("INV-42");
        assertThat(f.terminalId()).isEqualTo("T001");
    }

    @Test
    void staticQrHasNoAmountAndClipsNameAndCity() {
        String p = EmvQr.encode(new EmvQr.Fields(false, "7654321", null, "5812", "UGX", null, "UG",
                "A Very Long Restaurant Name That Exceeds", "Kampala Central Division", null, null));
        Map<String, String> tags = EmvQr.parse(p);
        assertThat(tags.get("01")).isEqualTo("11");
        assertThat(tags).doesNotContainKey("54");
        assertThat(tags.get("59")).hasSize(25);
        assertThat(tags.get("60")).hasSize(15);
        assertThat(tags.get("53")).isEqualTo("800");
        assertThat(EmvQr.crcValid(p)).isTrue();
    }

    @Test
    void tamperedPayloadFailsCrc() {
        String p = EmvQr.encode(new EmvQr.Fields(true, "1234567", "q", "5411", "KES", new BigDecimal("100"),
                "KE", "Shop", "Nairobi", null, null));
        String tampered = p.replace("5403100", "5403900");
        assertThat(tampered).isNotEqualTo(p);
        assertThat(EmvQr.crcValid(tampered)).isFalse();
        assertThat(EmvQr.crcValid(p.substring(0, p.length() - 1))).isFalse();
        assertThat(EmvQr.crcValid("")).isFalse();
    }

    @Test
    void malformedPayloadsAreRejected() {
        assertThatThrownBy(() -> EmvQr.decode("0002")).isInstanceOf(EmvQr.MalformedQr.class);
        assertThatThrownBy(() -> EmvQr.decode("000201015")).isInstanceOf(EmvQr.MalformedQr.class);
        assertThatThrownBy(() -> EmvQr.decode("000202")).isInstanceOf(EmvQr.MalformedQr.class);
        assertThatThrownBy(() -> EmvQr.decode("0099ab")).isInstanceOf(EmvQr.MalformedQr.class);
        assertThatThrownBy(() -> EmvQr.decode("xx02ab")).isInstanceOf(EmvQr.MalformedQr.class);
    }

    @Test
    void numericCurrencyMapping() {
        assertThat(EmvQr.numericCurrency("KES")).isEqualTo("404");
        assertThat(EmvQr.alphaCurrency("840")).isEqualTo("USD");
        assertThatThrownBy(() -> EmvQr.numericCurrency("XYZ")).isInstanceOf(IllegalArgumentException.class);
    }
}
