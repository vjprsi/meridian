package io.meridian.interop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class InteropCryptoTest {
    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void fulfilmentIs32RandomBytesAndConditionIsItsSha256() throws Exception {
        String f = InteropCrypto.newFulfilment();
        byte[] raw = Base64.getUrlDecoder().decode(f);
        assertThat(raw).hasSize(32);
        assertThat(f).doesNotContain("=").doesNotContain("+").doesNotContain("/");
        String expected = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(raw));
        assertThat(InteropCrypto.condition(f)).isEqualTo(expected);
        assertThat(InteropCrypto.newFulfilment()).isNotEqualTo(f);
    }

    @Test
    void knownVector() {
        // 32 zero bytes: SHA-256 = 66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925
        String zeros = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String condition = InteropCrypto.condition(zeros);
        assertThat(HexFormat.of().formatHex(Base64.getUrlDecoder().decode(condition)))
                .isEqualTo("66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925");
    }

    @Test
    void verifyAcceptsOnlyTheMatchingFulfilment() {
        String f = InteropCrypto.newFulfilment();
        String c = InteropCrypto.condition(f);
        assertThat(InteropCrypto.verify(f, c)).isTrue();
        assertThat(InteropCrypto.verify(InteropCrypto.newFulfilment(), c)).isFalse();
        assertThat(InteropCrypto.verify("not-base64!!", c)).isFalse();
        assertThat(InteropCrypto.verify("AAAA", c)).isFalse();
        assertThat(InteropCrypto.verify(null, c)).isFalse();
        assertThat(InteropCrypto.verify(f, null)).isFalse();
        assertThatThrownBy(() -> InteropCrypto.condition("AAAA")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deterministicFulfilmentForTheSimulator() {
        assertThat(InteropCrypto.fulfilmentFrom("k", "q1")).isEqualTo(InteropCrypto.fulfilmentFrom("k", "q1"));
        assertThat(InteropCrypto.fulfilmentFrom("k", "q1")).isNotEqualTo(InteropCrypto.fulfilmentFrom("k", "q2"));
        assertThat(Base64.getUrlDecoder().decode(InteropCrypto.fulfilmentFrom("k", "q1"))).hasSize(32);
    }

    @Test
    void canonicalJsonSortsKeysRecursivelyWithoutWhitespace() {
        JsonNode a = json.readTree("{ \"b\": 1, \"a\": { \"z\": [ {\"y\":2, \"x\":1} ], \"c\": \"v\" } }");
        assertThat(InteropCrypto.canonicalJson(a)).isEqualTo("{\"a\":{\"c\":\"v\",\"z\":[{\"x\":1,\"y\":2}]},\"b\":1}");
        JsonNode b = json.readTree("{\"a\":{\"z\":[{\"x\":1,\"y\":2}],\"c\":\"v\"},\"b\":1}");
        assertThat(InteropCrypto.canonicalJson(b)).isEqualTo(InteropCrypto.canonicalJson(a));
    }

    @Test
    void signatureIsHmacOfTheCanonicalBody() throws Exception {
        JsonNode body = json.readTree("{\"switch_code\":\"MOJA\",\"payload\":{\"type\":\"X\"}}");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("s".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(
                "{\"payload\":{\"type\":\"X\"},\"switch_code\":\"MOJA\"}".getBytes(StandardCharsets.UTF_8)));
        assertThat(InteropCrypto.sign("s", body)).isEqualTo(expected);
        assertThat(InteropCrypto.verifySignature("s", body, expected.toUpperCase())).isTrue();
        assertThat(InteropCrypto.verifySignature("s", body, expected.substring(2) + "00")).isFalse();
        assertThat(InteropCrypto.verifySignature("other", body, expected)).isFalse();
        assertThat(InteropCrypto.verifySignature(null, body, expected)).isFalse();
        assertThat(InteropCrypto.verifySignature("s", body, null)).isFalse();
        assertThat(InteropCrypto.verifySignature("s", body, "zz-not-hex")).isFalse();
        JsonNode tampered = json.readTree("{\"switch_code\":\"MOJA\",\"payload\":{\"type\":\"Y\"}}");
        assertThat(InteropCrypto.verifySignature("s", tampered, expected)).isFalse();
    }

    @Test
    void feesAndNetDebitCap() {
        assertThat(InteropRules.fee(new BigDecimal("1000"), new BigDecimal("10"), new BigDecimal("0.01")))
                .isEqualByComparingTo("20.00");
        assertThat(InteropRules.fee(new BigDecimal("33.33"), null, new BigDecimal("0.015"))).isEqualByComparingTo("0.50");
        assertThat(InteropRules.fee(new BigDecimal("100"), null, null)).isEqualByComparingTo("0");
        assertThat(InteropRules.withinCap(new BigDecimal("10000"), new BigDecimal("1020"), BigDecimal.ZERO, new BigDecimal("8980"))).isTrue();
        assertThat(InteropRules.withinCap(new BigDecimal("10000"), new BigDecimal("1020"), new BigDecimal("1"), new BigDecimal("8980"))).isFalse();
        assertThat(InteropRules.withinCap(null, new BigDecimal("1"), BigDecimal.ONE, new BigDecimal("1e9"))).isTrue();
        assertThat(InteropRules.isFinal("COMMITTED")).isTrue();
        assertThat(InteropRules.isFinal("RESERVED")).isFalse();
        assertThatThrownBy(() -> InteropRules.requireTransition("COMMITTED", "ABORTED"))
                .hasMessageContaining("cannot move");
    }
}
