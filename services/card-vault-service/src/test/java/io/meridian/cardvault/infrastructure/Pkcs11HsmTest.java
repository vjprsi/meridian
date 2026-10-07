package io.meridian.cardvault.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.cardvault.application.Hsm;
import io.meridian.cardvault.domain.PinBlock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GAP-01: the PKCS#11 adapter driven through a stand-in token (SunJCE provider + an in-memory key store; the adapter
 * makes exactly the JCA calls SunPKCS11 maps to C_GenerateKey / C_Encrypt / C_Sign / C_UnwrapKey / C_WrapKey). Keys
 * are generated on the token and only metadata comes back; PIN blocks are translated by unwrap + wrap of a key
 * object, without the adapter ever asking for the clear block (a real token returns no encoding for it).
 */
class Pkcs11HsmTest {
    static final String PAN = "4111111111111111";
    final Provider sunJce = Security.getProvider("SunJCE");
    KeyStore token;
    Pkcs11Hsm hsm;
    final AtomicInteger clearBlockLookups = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        token = KeyStore.getInstance("PKCS12");
        token.load(null, null);
        hsm = new Pkcs11Hsm(sunJce, token, new KeyStore.PasswordProtection("token-pin".toCharArray()), "meridian/",
                "test-token", "GenericSecret") {
            @Override
            protected void checkClearPinBlock(Key unwrappedBlock, String pan) {
                clearBlockLookups.incrementAndGet();
                super.checkClearPinBlock(unwrappedBlock, pan);
            }
        };
    }

    @Test
    void keysAreGeneratedOnTheTokenAndNeverReturned() throws Exception {
        Hsm.KeyRef dek = new Hsm.KeyRef("PAN_DEK", "pan-dek@t1", 1, null, null);
        Hsm.KeyMaterial m = hsm.createKey(dek);
        assertThat(m.wrapped()).isNull();
        assertThat(m.masterRef()).isEqualTo("pkcs11:test-token").isEqualTo(hsm.currentMasterRef());
        assertThat(m.kcv()).hasSize(6);
        assertThat(token.containsAlias("meridian/pan-dek@t1:v1")).isTrue();
        // idempotent: an existing alias (imported or created before) is used as is
        assertThat(hsm.createKey(dek).kcv()).isEqualTo(m.kcv());
        // master key rotation does not apply to token keys
        assertThat(hsm.rewrap(dek)).isEqualTo(new Hsm.KeyMaterial(null, "pkcs11:test-token", m.kcv()));

        byte[] aad = "t:tok:pan".getBytes(StandardCharsets.UTF_8);
        byte[] blob = hsm.encrypt(dek, PAN.getBytes(), aad);
        assertThat(new String(hsm.decrypt(dek, blob, aad))).isEqualTo(PAN);
        assertThatThrownBy(() -> hsm.decrypt(dek, blob, "t:tok:exp".getBytes())).isInstanceOf(IllegalStateException.class);

        // fingerprint and card verification keys are HMAC keys on the token
        Hsm.KeyRef fp = new Hsm.KeyRef("FINGERPRINT", "fingerprint", 1, null, null);
        Hsm.KeyRef cvk = new Hsm.KeyRef("CVK", "cvk", 1, null, null);
        hsm.createKey(fp);
        hsm.createKey(cvk);
        assertThat(token.getKey("meridian/cvk:v1", "token-pin".toCharArray()).getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(hsm.fingerprint(fp, PAN)).hasSize(64).isEqualTo(hsm.fingerprint(fp, PAN));
        assertThat(hsm.deriveCvv(cvk, PAN, 3, 2031)).matches("\\d{3}");
        assertThat(hsm.pinInsideHsm()).isTrue();
        assertThatThrownBy(() -> hsm.kcv(new Hsm.KeyRef("ZPK", "missing", 1, null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not found");
    }

    @Test
    void pinBlocksAreTranslatedInsideTheHsmFromAnImportedZoneKey() throws Exception {
        // the zone key shared with the acquirer is imported under dual control (key ceremony), not generated
        byte[] zpkBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(zpkBytes);
        token.setEntry("meridian/zpk-default:v1", new KeyStore.SecretKeyEntry(new SecretKeySpec(zpkBytes, "AES")),
                new KeyStore.PasswordProtection("token-pin".toCharArray()));
        Hsm.KeyRef zpk = new Hsm.KeyRef("ZPK", "zpk-default", 1, null, null);
        assertThat(hsm.createKey(zpk).kcv()).isEqualTo(new SoftwareKeyManagementKcv(zpkBytes).kcv());
        Hsm.KeyRef pek = new Hsm.KeyRef("PIN_KEY", "pin-key@t1", 1, null, null);
        hsm.createKey(pek);

        String wrapped = SoftwareHsmTest.wrap(zpkBytes, PinBlock.encode("2580", PAN));
        byte[] stored = hsm.translatePinBlock(zpk, wrapped, pek, PAN);
        assertThat(stored).hasSize(16)
                .isEqualTo(hsm.translatePinBlock(zpk, SoftwareHsmTest.wrap(zpkBytes, PinBlock.encode("2580", PAN)), pek, PAN))
                .isEqualTo(hsm.protectReferencePinBlock(pek, PinBlock.encode("2580", PAN)))
                .isNotEqualTo(hsm.translatePinBlock(zpk, SoftwareHsmTest.wrap(zpkBytes, PinBlock.encode("9137", PAN)), pek, PAN));

        // HSM mode never validates (never sees) the clear block: a malformed block translates and simply never matches
        int before = clearBlockLookups.get();
        byte[] junk = hsm.translatePinBlock(zpk, SoftwareHsmTest.wrap(zpkBytes, new byte[8]), pek, PAN);
        assertThat(junk).isNotEqualTo(stored);
        assertThat(clearBlockLookups.get()).isGreaterThan(before); // hook called, but the HSM adapter's hook is a no-op

        // PIN key rotation re-translates inside the HSM
        Hsm.KeyRef pek2 = new Hsm.KeyRef("PIN_KEY", "pin-key@t1", 2, null, null);
        hsm.createKey(pek2);
        assertThat(hsm.retranslatePin(pek, stored, pek2)).isEqualTo(hsm.protectReferencePinBlock(pek2,
                PinBlock.encode("2580", PAN)));
        assertThatThrownBy(() -> hsm.translatePinBlock(pek, wrapped, pek, PAN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sunPkcs11ConfigurationIsValidated() {
        assertThatThrownBy(() -> Pkcs11Hsm.sunPkcs11(Path.of("does-not-exist.cfg"), "1234".toCharArray(), "m/", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SunPKCS11 configuration");
        assertThat(hsm.alias(new Hsm.KeyRef("ZPK", "zpk-default", 3, null, null))).isEqualTo("meridian/zpk-default:v3");
    }

    /** KCV of raw AES key bytes (AES-ECB of a zero block), to compare with what the token reports. */
    record SoftwareKeyManagementKcv(byte[] key) {
        String kcv() throws Exception {
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return java.util.HexFormat.of().withUpperCase().formatHex(c.doFinal(new byte[16])).substring(0, 6);
        }
    }
}
