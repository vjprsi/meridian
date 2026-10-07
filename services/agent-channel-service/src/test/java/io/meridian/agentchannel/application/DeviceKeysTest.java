package io.meridian.agentchannel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Canonical signature texts (the same vectors as web/agent-app tests/device-key.test.ts) and verification of ES256
 * (P1363 and DER) and Ed25519 signatures, including keys of the wrong kind.
 */
class DeviceKeysTest {
    static final UUID TENANT = UUID.fromString("0199a000-0000-7000-8000-00000000000a");
    static final UUID AGENT = UUID.fromString("0199a000-0000-7000-8000-0000000a0001");
    static final UUID DEVICE = UUID.fromString("0199a000-0000-7000-8000-0000000d0001");
    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void canonicalTextsMatchTheAgentApp() {
        Instant at = Instant.parse("2026-10-07T09:30:15.123Z");
        assertThat(DeviceKeys.batchPayload(TENANT, AGENT, DEVICE, "agentapp-1", at, at, 1,
                "data:application/gzip;base64,AAAA")).isEqualTo(String.join("\n",
                "MERIDIAN-AGENT-OFFLINE-BATCH-V1",
                "tenant=0199a000-0000-7000-8000-00000000000a",
                "agent=0199a000-0000-7000-8000-0000000a0001",
                "device=0199a000-0000-7000-8000-0000000d0001",
                "idempotency_key=agentapp-1",
                "captured_from=1791365415123",
                "captured_to=1791365415123",
                "application_count=1",
                "payload_sha256=1bd2859158185eecd7d4bf270d3191f614e04db90383261a894328f96e5dcebe"));
        assertThat(DeviceKeys.proofPayload(TENANT, AGENT, DEVICE, "POST", "/v1/agent/cash-in", "agentapp-2", 1791365415L))
                .isEqualTo("MERIDIAN-AGENT-DEVICE-PROOF-V1\ntenant=0199a000-0000-7000-8000-00000000000a\n"
                        + "agent=0199a000-0000-7000-8000-0000000a0001\ndevice=0199a000-0000-7000-8000-0000000d0001\n"
                        + "method=POST\npath=/v1/agent/cash-in\nidempotency_key=agentapp-2\ntimestamp=1791365415");
    }

    @Test
    void verifiesEs256InP1363AndDerAndEd25519() throws Exception {
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair es = ec.generateKeyPair();
        ObjectNode esRow = row("ES256", es);
        assertThat(DeviceKeys.verify(esRow, "hello", sign(es, "SHA256withECDSAinP1363Format", "hello", true))).isTrue();
        assertThat(DeviceKeys.verify(esRow, "hello", sign(es, "SHA256withECDSA", "hello", false))).isTrue();
        assertThat(DeviceKeys.verify(esRow, "hellO", sign(es, "SHA256withECDSAinP1363Format", "hello", true))).isFalse();
        assertThat(DeviceKeys.verify(esRow, "hello", "not base64 !")).isFalse();

        KeyPair ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ObjectNode edRow = row("ED25519", ed);
        assertThat(DeviceKeys.verify(edRow, "hello", sign(ed, "Ed25519", "hello", true))).isTrue();
        // a key registered under the wrong algorithm never verifies
        assertThat(DeviceKeys.verify(row("ES256", ed), "hello", sign(ed, "Ed25519", "hello", true))).isFalse();
    }

    @Test
    void rejectsKeysOfTheWrongKind() throws Exception {
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new ECGenParameterSpec("secp384r1"));
        String p384 = Base64.getEncoder().encodeToString(ec.generateKeyPair().getPublic().getEncoded());
        assertThatThrownBy(() -> DeviceKeys.parse("ES256", p384)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("invalid_public_key"));
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        String rsaKey = Base64.getEncoder().encodeToString(rsa.generateKeyPair().getPublic().getEncoded());
        assertThatThrownBy(() -> DeviceKeys.parse("ES256", rsaKey)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DeviceKeys.parse("ED25519", rsaKey)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DeviceKeys.parse("RS256", rsaKey)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("invalid_key_algorithm"));
    }

    private ObjectNode row(String algorithm, KeyPair kp) {
        ObjectNode row = json.createObjectNode();
        row.put("key_algorithm", algorithm);
        row.put("public_key", Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
        return row;
    }

    private static String sign(KeyPair kp, String alg, String text, boolean url) throws Exception {
        Signature s = Signature.getInstance(alg);
        s.initSign(kp.getPrivate());
        s.update(text.getBytes(StandardCharsets.UTF_8));
        byte[] sig = s.sign();
        return url ? Base64.getUrlEncoder().withoutPadding().encodeToString(sig) : Base64.getEncoder().encodeToString(sig);
    }
}
