package io.meridian.web.callback;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class CallbackVerifierTest {
    static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    static final String TENANT = "0199a000-0000-7000-8000-00000000000a";
    static final byte[] BODY = "{\"status\":\"SETTLED\"}".getBytes(StandardCharsets.UTF_8);
    static final Function<byte[], String> SIGN = b -> hmac("provider-secret", b);

    final Set<String> seen = new HashSet<>();
    final NonceStore memory = (p, n, at, r) -> seen.add(p + "|" + n);

    CallbackVerifier verifier(boolean legacy) {
        return new CallbackVerifier(memory, Duration.ofMinutes(5), legacy, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static String hmac(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String v2(String ts, String nonce, String tenant) {
        return SIGN.apply(CallbackVerifier.v2Payload(ts, nonce, tenant, BODY));
    }

    static void code(Runnable r, String code) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class,
                e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void v2SignatureIsAcceptedOnceAndBindsTimestampNonceAndTenant() {
        CallbackVerifier v = verifier(false);
        String ts = String.valueOf(NOW.getEpochSecond() - 30);
        String sig = v2(ts, "evt-00000001", TENANT);
        assertThatCode(() -> v.verify("rail:1", TENANT, BODY, sig, ts, "evt-00000001", SIGN)).doesNotThrowAnyException();
        code(() -> v.verify("rail:1", TENANT, BODY, sig, ts, "evt-00000001", SIGN), "callback_replayed");
        // the same signature presented for another tenant, nonce or timestamp does not verify
        code(() -> v.verify("rail:1", "0199a000-0000-7000-8000-00000000000b", BODY, sig, ts, "evt-00000001", SIGN),
                "invalid_signature");
        code(() -> v.verify("rail:1", TENANT, BODY, sig, ts, "evt-00000002", SIGN), "invalid_signature");
        code(() -> v.verify("rail:1", TENANT, BODY, sig, String.valueOf(NOW.getEpochSecond()), "evt-00000001", SIGN),
                "invalid_signature");
        code(() -> v.verify("rail:1", TENANT, "{}".getBytes(StandardCharsets.UTF_8), sig, ts, "evt-00000001", SIGN),
                "invalid_signature");
    }

    @Test
    void oldTimestampsAreRefused() {
        CallbackVerifier v = verifier(true);
        String old = String.valueOf(NOW.getEpochSecond() - 600);
        code(() -> v.verify("p", null, BODY, v2(old, "evt-00000003", null), old, "evt-00000003", SIGN), "stale_callback");
        String future = String.valueOf(NOW.getEpochSecond() + 600);
        code(() -> v.verify("p", null, BODY, v2(future, "evt-00000004", null), future, "evt-00000004", SIGN),
                "stale_callback");
    }

    @Test
    void legacyBodyOnlySignaturesOnlyWhileTheFlagIsOn() {
        String legacy = SIGN.apply(BODY);
        assertThatCode(() -> verifier(true).verify("p", TENANT, BODY, legacy, null, null, SIGN)).doesNotThrowAnyException();
        assertThatCode(() -> verifier(true).verify("p", TENANT, BODY, legacy.toUpperCase(), null, null, SIGN))
                .doesNotThrowAnyException();
        code(() -> verifier(false).verify("p", TENANT, BODY, legacy, null, null, SIGN), "invalid_signature");
        code(() -> verifier(true).verify("p", TENANT, BODY, "00" + legacy.substring(2), null, null, SIGN), "invalid_signature");
        code(() -> verifier(true).verify("p", TENANT, BODY, null, null, null, SIGN), "invalid_signature");
        // half a v2 header set is not a legacy request
        code(() -> verifier(true).verify("p", TENANT, BODY, legacy, String.valueOf(NOW.getEpochSecond()), null, SIGN),
                "invalid_signature");
    }
}
