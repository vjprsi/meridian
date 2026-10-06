package io.meridian.cardvault.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.cardvault.application.Hsm;
import io.meridian.cardvault.domain.PinBlock;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class SoftwareHsmTest {
    static final String MASTER = "bWVyaWRpYW4tZGV2LXZhdWx0LW1hc3Rlci1rZXktMDE=";
    final SoftwareHsm hsm = new SoftwareHsm(MASTER);
    final Hsm.KeyRef dek = new Hsm.KeyRef("pan-dek", 1);

    @Property
    void gcmRoundTrip(@ForAll String plain, @ForAll String aad) {
        byte[] blob = hsm.encrypt(dek, plain.getBytes(StandardCharsets.UTF_8), aad.getBytes(StandardCharsets.UTF_8));
        assertThat(new String(hsm.decrypt(dek, blob, aad.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))
                .isEqualTo(plain);
    }

    @Test
    void tamperingWrongAadOrWrongKeyIsDetected() {
        byte[] aad = "t:tok_1:pan".getBytes(StandardCharsets.UTF_8);
        byte[] blob = hsm.encrypt(dek, "4111111111111111".getBytes(StandardCharsets.UTF_8), aad);
        byte[] tampered = blob.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> hsm.decrypt(dek, tampered, aad)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> hsm.decrypt(dek, blob, "t:tok_2:pan".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> hsm.decrypt(new Hsm.KeyRef("pan-dek", 2), blob, aad))
                .isInstanceOf(IllegalStateException.class);
        // fresh IV per encryption: same plaintext, different ciphertext
        assertThat(hsm.encrypt(dek, "x".getBytes(), aad)).isNotEqualTo(hsm.encrypt(dek, "x".getBytes(), aad));
    }

    @Test
    void kcvFingerprintAndCvvAreDeterministic() {
        assertThat(hsm.kcv(dek)).hasSize(6).isEqualTo(new SoftwareHsm(MASTER).kcv(dek))
                .isNotEqualTo(hsm.kcv(new Hsm.KeyRef("pan-dek", 2)));
        assertThat(hsm.fingerprint("4111111111111111")).hasSize(64)
                .isEqualTo(new SoftwareHsm(MASTER).fingerprint("4111111111111111"))
                .isNotEqualTo(hsm.fingerprint("4111111111111129"));
        Hsm.KeyRef cvk = new Hsm.KeyRef("cvk", 1);
        String cvv = hsm.deriveCvv(cvk, "4111111111111111", 3, 2031);
        assertThat(cvv).matches("\\d{3}").isEqualTo(hsm.deriveCvv(cvk, "4111111111111111", 3, 2031));
        // a different expiry gives (almost always) another CVV; check across a few expiries
        long distinct = java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(m -> hsm.deriveCvv(cvk, "4111111111111111", m, 2031)).distinct().count();
        assertThat(distinct).isGreaterThan(6);
    }

    @Test
    void unwrapsPinBlockWrappedWithTheSharedKdf() throws Exception {
        byte[] clear = PinBlock.encode("2580", "4111111111111111");
        String wrapped = wrap(SoftwareHsm.deriveKey(Base64.getDecoder().decode(MASTER), "zpk-default", 1), clear);
        byte[] out = hsm.unwrapPinBlock(new Hsm.KeyRef("zpk-default", 1), wrapped);
        assertThat(PinBlock.decode(out, "4111111111111111")).isEqualTo("2580");
        assertThatThrownBy(() -> hsm.unwrapPinBlock(new Hsm.KeyRef("zpk-other", 1), wrapped))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hsm.unwrapPinBlock(new Hsm.KeyRef("zpk-default", 1), "%%%"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static String wrap(byte[] key, byte[] clear) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(clear);
        byte[] all = new byte[12 + ct.length];
        System.arraycopy(iv, 0, all, 0, 12);
        System.arraycopy(ct, 0, all, 12, ct.length);
        return Base64.getEncoder().encodeToString(all);
    }
}
