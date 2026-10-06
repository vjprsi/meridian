package io.meridian.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EmvQrTest {

    @Test
    void crc16CcittFalseCheckValue() {
        // CRC-16/CCITT-FALSE catalogue check value
        assertThat(EmvQr.crc16("123456789".getBytes(StandardCharsets.US_ASCII))).isEqualTo(0x29B1);
        assertThat(EmvQr.crc16Hex("")).isEqualTo("FFFF");
    }

    @Test
    void knownEmvcoPayloadHasValidCrc() {
        // Static PIX-style sample payload: the trailing 4 hex digits are the CRC over everything before them.
        String body = "00020101021126330014BR.GOV.BCB.PIX0111123456789015204000053039865802BR5913FULANO DE TAL6008BRASILIA6304";
        String payload = body + EmvQr.crc16Hex(body);
        EmvQr.Decoded d = EmvQr.decode(payload);
        assertThat(d.crcValid()).isTrue();
        assertThat(d.fields().get("59")).isEqualTo("FULANO DE TAL");
        assertThat(d.templates().get("26").get("00")).isEqualTo("BR.GOV.BCB.PIX");
        assertThat(EmvQr.alphaCurrency("986")).contains("BRL");
    }

    @Test
    void encodeDecodeRoundTrip() {
        String payload = EmvQr.encode(new EmvQr.Spec(true, "IO.MERIDIAN.PAY", "abc-123", "5812", "EUR",
                new BigDecimal("12.50"), "DE", "Café Müller", "Berlin", "INV-9"));
        assertThat(payload).startsWith("000201010212");
        assertThat(payload).contains("5303978").contains("540412.5").contains("5802DE").contains("5911Cafe Muller");
        EmvQr.Decoded d = EmvQr.decode(payload);
        assertThat(d.crcValid()).isTrue();
        assertThat(d.field("54")).contains("12.5");
        assertThat(d.templates().get("26")).containsEntry("00", "IO.MERIDIAN.PAY").containsEntry("01", "abc-123");
        assertThat(d.templates().get("62")).containsEntry("05", "INV-9");
    }

    @Test
    void staticQrHasNoAmountAndPointOfInitiation11() {
        String payload = EmvQr.encode(new EmvQr.Spec(false, "G", "R", null, "GBP", null, "GB", "Shop", "London", null));
        assertThat(payload).startsWith("000201010211").doesNotContain("6200");
        assertThat(EmvQr.decode(payload).field("54")).isEmpty();
    }

    @Test
    void tamperedAndMalformedPayloadsAreDetected() {
        String payload = EmvQr.encode(new EmvQr.Spec(true, "G", "R", "0000", "GBP", new BigDecimal("5.00"), "GB", "Shop",
                "London", null));
        assertThat(EmvQr.decode(payload.replace("54015", "54016")).crcValid()).isFalse();
        assertThatThrownBy(() -> EmvQr.decode("0002019")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmvQr.decode("0002010199AB")).isInstanceOf(IllegalArgumentException.class);
    }
}
