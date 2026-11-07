package io.meridian.routing.infrastructure.rails;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Bre-B key inference for PROXY creditors and the request signature. */
class BrebTransportTest {

    @Test
    void proxyCreditorsMapToBreBKeyTypes() {
        assertThat(BrebTransport.creditorKey("PROXY", "+573125764304")).containsExactly("CELULAR", "+573125764304");
        assertThat(BrebTransport.creditorKey("PROXY", "312 576 4304")).containsExactly("CELULAR", "3125764304");
        assertThat(BrebTransport.creditorKey("PROXY", "vjprsi@gmail.com")).containsExactly("CORREO", "vjprsi@gmail.com");
        assertThat(BrebTransport.creditorKey("PROXY", "@vijaypay")).containsExactly("ALFANUMERICA", "@vijaypay");
        assertThat(BrebTransport.creditorKey("PROXY", "1020304050")).containsExactly("DOCUMENTO", "1020304050");
        assertThat(BrebTransport.creditorKey("PROXY", "COMERCIO:1234567")).containsExactly("COMERCIO", "1234567");
        // an account number is not a key
        assertThat(BrebTransport.creditorKey("ACCOUNT_NUMBER", "00150106246")).isNull();
        assertThat(BrebTransport.creditorKey(null, "3125764304")).isNull();
    }

    @Test
    void signatureIsHexHmacSha256OfTheBody() {
        // HMAC-SHA256("local-dev-breb-secret", "{}") - the simulator verifies the same value
        String sig = BrebTransport.hmac("local-dev-breb-secret", "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(sig).hasSize(64).matches("[0-9a-f]+");
        assertThat(BrebTransport.hmac("local-dev-breb-secret", "{}".getBytes(StandardCharsets.UTF_8))).isEqualTo(sig);
        assertThat(BrebTransport.hmac("other", "{}".getBytes(StandardCharsets.UTF_8))).isNotEqualTo(sig);
    }
}
