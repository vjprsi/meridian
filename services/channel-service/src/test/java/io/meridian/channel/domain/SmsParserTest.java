package io.meridian.channel.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SmsParserTest {
    static final String SYNTAX = "SEND amount msisdn pin";

    @Test
    void parsesKeywordAndNamedArguments() {
        SmsParser.Parsed p = SmsParser.parse("  send 500  0712345678 4826 ");
        assertThat(p.keyword()).isEqualTo("SEND");
        assertThat(p.args()).containsExactly("500", "0712345678", "4826");
        assertThat(SmsParser.bind(p, SYNTAX)).hasSize(3).containsAllEntriesOf(
                Map.of("amount", "500", "msisdn", "0712345678", "pin", "4826"));
        assertThat(SmsParser.bind(SmsParser.parse("SEND 500"), SYNTAX)).containsOnlyKeys("amount");
        assertThat(SmsParser.argNames("BAL <pin>")).isEqualTo(List.of("pin"));
        assertThat(SmsParser.parse("").keyword()).isEmpty();
    }

    @Test
    void masksPins() {
        assertThat(SmsParser.mask("send 500 0712345678 4826", SYNTAX, true)).isEqualTo("send 500 0712345678 ****");
        assertThat(SmsParser.mask("BAL 4826", null, true)).isEqualTo("BAL ****");
        assertThat(SmsParser.mask("BAL", "BAL", false)).isEqualTo("BAL");
        assertThat(SmsParser.mask("HELP 1234", "HELP", false)).isEqualTo("HELP 1234");
    }

    @Test
    void signaturesAreCanonicalAndConstantTime() {
        Map<String, Object> a = Map.of("b", "2", "a", Map.of("z", "x", "y", List.of("1", "2")));
        assertThat(GatewaySignature.canonical(a)).isEqualTo("{\"a\":{\"y\":[\"1\",\"2\"],\"z\":\"x\"},\"b\":\"2\"}");
        java.util.Map<String, Object> withNull = new java.util.HashMap<>(Map.of("b", "2"));
        withNull.put("n", null);
        assertThat(GatewaySignature.canonical(withNull)).isEqualTo("{\"b\":\"2\"}");
        String sig = GatewaySignature.sign("s3cret", a);
        assertThat(sig).matches("[0-9a-f]{64}");
        assertThat(GatewaySignature.verify("s3cret", a, sig.toUpperCase())).isTrue();
        assertThat(GatewaySignature.verify("other", a, sig)).isFalse();
        assertThat(GatewaySignature.verify(null, a, sig)).isFalse();
        assertThat(GatewaySignature.verify("s3cret", a, null)).isFalse();
        assertThat(GatewaySignature.canonical("q\"\n")).isEqualTo("\"q\\\"\\n\"");
    }
}
