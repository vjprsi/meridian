package io.meridian.cardvault.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.cardvault.application.Hsm;
import io.meridian.common.tenancy.TenantContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.DescribeKeyRequest;
import software.amazon.awssdk.services.kms.model.DescribeKeyResponse;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;
import software.amazon.awssdk.services.kms.model.InvalidCiphertextException;
import software.amazon.awssdk.services.kms.model.KeyMetadata;
import software.amazon.awssdk.services.kms.model.ReEncryptRequest;
import software.amazon.awssdk.services.kms.model.ReEncryptResponse;

/**
 * GAP-01: the AWS KMS adapter against an in-memory KMS that behaves like the service: per-key AES master keys that
 * never leave it, the encryption context bound as AAD (a mismatch is InvalidCiphertextException), ciphertexts naming
 * their key, ReEncrypt server side and an alias that can be re-pointed (manual rotation).
 */
class AwsKmsKeyManagementTest {
    static final String ARN_1 = "arn:aws:kms:eu-west-1:111122223333:key/11111111-1111-1111-1111-111111111111";
    static final String ARN_2 = "arn:aws:kms:eu-west-1:111122223333:key/22222222-2222-2222-2222-222222222222";
    static final String ALIAS = "alias/meridian-euw1-c01-cde";
    static final UUID TENANT = UUID.fromString("0199a000-0000-7000-8000-0000000e0001");

    /** In-memory KMS: ciphertext = index of the key (1 byte) || IV || AES-GCM(key, plaintext, AAD = context). */
    static final class FakeKms implements KmsClient {
        final Map<String, byte[]> keys = new HashMap<>();
        final List<String> keyOrder = new ArrayList<>();
        final List<Map<String, String>> contexts = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        String aliasTarget = ARN_1;
        final SecureRandom random = new SecureRandom();

        FakeKms() {
            addKey(ARN_1);
            addKey(ARN_2);
        }

        void addKey(String arn) {
            byte[] k = new byte[32];
            random.nextBytes(k);
            keys.put(arn, k);
            keyOrder.add(arn);
        }

        String resolve(String id) {
            return ALIAS.equals(id) ? aliasTarget : id;
        }

        byte[] seal(String arn, byte[] plain, Map<String, String> ctx) {
            try {
                byte[] iv = new byte[12];
                random.nextBytes(iv);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys.get(arn), "AES"), new GCMParameterSpec(128, iv));
                c.updateAAD(new TreeMap<>(ctx).toString().getBytes(StandardCharsets.UTF_8));
                byte[] ct = c.doFinal(plain);
                return ByteBuffer.allocate(1 + 12 + ct.length).put((byte) keyOrder.indexOf(arn)).put(iv).put(ct).array();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        String keyOf(byte[] blob) {
            return keyOrder.get(blob[0]);
        }

        byte[] open(byte[] blob, Map<String, String> ctx) {
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keys.get(keyOf(blob)), "AES"),
                        new GCMParameterSpec(128, blob, 1, 12));
                c.updateAAD(new TreeMap<>(ctx).toString().getBytes(StandardCharsets.UTF_8));
                return c.doFinal(blob, 13, blob.length - 13);
            } catch (Exception e) {
                throw InvalidCiphertextException.builder().message("invalid ciphertext / context").build();
            }
        }

        @Override
        public GenerateDataKeyResponse generateDataKey(GenerateDataKeyRequest r) {
            calls.add("GenerateDataKey");
            contexts.add(r.encryptionContext());
            assertThat(r.keySpec()).isEqualTo(DataKeySpec.AES_256);
            String arn = resolve(r.keyId());
            byte[] plain = new byte[32];
            random.nextBytes(plain);
            return GenerateDataKeyResponse.builder().keyId(arn).plaintext(SdkBytes.fromByteArray(plain))
                    .ciphertextBlob(SdkBytes.fromByteArray(seal(arn, plain, r.encryptionContext()))).build();
        }

        @Override
        public DecryptResponse decrypt(DecryptRequest r) {
            calls.add("Decrypt");
            contexts.add(r.encryptionContext());
            byte[] blob = r.ciphertextBlob().asByteArray();
            if (r.keyId() != null && !r.keyId().equals(keyOf(blob))) {
                throw InvalidCiphertextException.builder().message("key id mismatch").build();
            }
            return DecryptResponse.builder().keyId(keyOf(blob))
                    .plaintext(SdkBytes.fromByteArray(open(blob, r.encryptionContext()))).build();
        }

        @Override
        public EncryptResponse encrypt(EncryptRequest r) {
            calls.add("Encrypt");
            String arn = resolve(r.keyId());
            return EncryptResponse.builder().keyId(arn).ciphertextBlob(SdkBytes.fromByteArray(
                    seal(arn, r.plaintext().asByteArray(), r.encryptionContext()))).build();
        }

        @Override
        public ReEncryptResponse reEncrypt(ReEncryptRequest r) {
            calls.add("ReEncrypt");
            byte[] plain = open(r.ciphertextBlob().asByteArray(), r.sourceEncryptionContext());
            String arn = resolve(r.destinationKeyId());
            return ReEncryptResponse.builder().keyId(arn).sourceKeyId(keyOf(r.ciphertextBlob().asByteArray()))
                    .ciphertextBlob(SdkBytes.fromByteArray(seal(arn, plain, r.destinationEncryptionContext()))).build();
        }

        @Override
        public DescribeKeyResponse describeKey(DescribeKeyRequest r) {
            calls.add("DescribeKey");
            return DescribeKeyResponse.builder().keyMetadata(KeyMetadata.builder().arn(resolve(r.keyId())).build()).build();
        }

        @Override
        public String serviceName() {
            return "kms";
        }

        @Override
        public void close() {
        }
    }

    static <T> T as(java.util.concurrent.Callable<T> c) throws Exception {
        return TenantContext.callAs(TENANT, "test", c);
    }

    @Test
    void envelopeEncryptionThroughKmsWithTenantAndPurposeContext() throws Exception {
        FakeKms kms = new FakeKms();
        EnvelopeHsm hsm = new EnvelopeHsm(new AwsKmsKeyManagement(kms, ALIAS), null, Duration.ofMinutes(5), 100);
        Hsm.KeyRef dek = new Hsm.KeyRef("PAN_DEK", "pan-dek@" + TENANT, 1, null, null);
        Hsm.KeyMaterial m = as(() -> hsm.createKey(dek));
        assertThat(m.masterRef()).isEqualTo(ARN_1);
        assertThat(kms.contexts.getFirst()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "tenant", TENANT.toString(), "purpose", "PAN_DEK", "key", "pan-dek@" + TENANT + ":v1"));
        Hsm.KeyRef stored = new Hsm.KeyRef("PAN_DEK", dek.label(), 1, m.wrapped(), m.masterRef());
        byte[] aad = "x".getBytes();
        byte[] blob = as(() -> hsm.encrypt(stored, "4111111111111111".getBytes(), aad));

        // another pod: one Decrypt, then the cache
        EnvelopeHsm pod2 = new EnvelopeHsm(new AwsKmsKeyManagement(kms, ALIAS), null, Duration.ofMinutes(5), 100);
        kms.calls.clear();
        for (int i = 0; i < 3; i++) {
            assertThat(new String(as(() -> pod2.decrypt(stored, blob, aad)))).isEqualTo("4111111111111111");
        }
        assertThat(kms.calls).containsExactly("Decrypt");
        // the wrapped key is useless under another purpose (KMS refuses the context)
        Hsm.KeyRef wrongPurpose = new Hsm.KeyRef("PIN_KEY", dek.label(), 1, m.wrapped(), m.masterRef());
        assertThatThrownBy(() -> as(() -> pod2.decrypt(wrongPurpose, blob, aad)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("KMS Decrypt failed");
    }

    @Test
    void manualRotationRePointsTheAliasAndTheRewrapUsesReEncrypt() throws Exception {
        FakeKms kms = new FakeKms();
        AwsKmsKeyManagement km = new AwsKmsKeyManagement(kms, ALIAS);
        EnvelopeHsm hsm = new EnvelopeHsm(km, null, Duration.ZERO, 100);
        Hsm.KeyRef cvk = new Hsm.KeyRef("CVK", "cvk", 1, null, null);
        Hsm.KeyMaterial m = as(() -> hsm.createKey(cvk));
        Hsm.KeyRef stored = new Hsm.KeyRef("CVK", "cvk", 1, m.wrapped(), m.masterRef());
        String cvv = as(() -> hsm.deriveCvv(stored, "4111111111111111", 3, 2031));
        assertThat(hsm.currentMasterRef()).isEqualTo(ARN_1);

        kms.aliasTarget = ARN_2;                  // the CDE key is replaced: alias -> new key
        AwsKmsKeyManagement afterRotation = new AwsKmsKeyManagement(kms, ALIAS);
        EnvelopeHsm hsm2 = new EnvelopeHsm(afterRotation, null, Duration.ZERO, 100);
        assertThat(hsm2.currentMasterRef()).isEqualTo(ARN_2);
        kms.calls.clear();
        Hsm.KeyMaterial r = as(() -> hsm2.rewrap(stored));
        assertThat(kms.calls).containsExactly("ReEncrypt", "Decrypt");   // ReEncrypt, then the KCV check
        assertThat(r.masterRef()).isEqualTo(ARN_2);
        assertThat(r.kcv()).isEqualTo(m.kcv());
        Hsm.KeyRef moved = new Hsm.KeyRef("CVK", "cvk", 1, r.wrapped(), r.masterRef());
        assertThat(as(() -> hsm2.deriveCvv(moved, "4111111111111111", 3, 2031))).isEqualTo(cvv);
        kms.keys.remove(ARN_1);                   // old key scheduled for deletion: data still readable
        assertThat(as(() -> hsm2.deriveCvv(moved, "4111111111111111", 3, 2031))).isEqualTo(cvv);
    }

    @Test
    void legacyKeysAreAdoptedWithEncryptAndAKeyIdIsRequired() throws Exception {
        FakeKms kms = new FakeKms();
        byte[] legacyMaster = "legacy-software-master-key-32-by".getBytes(StandardCharsets.US_ASCII);
        EnvelopeHsm hsm = new EnvelopeHsm(new AwsKmsKeyManagement(kms, ALIAS), legacyMaster, Duration.ZERO, 10);
        Hsm.KeyRef old = new Hsm.KeyRef("ZPK", "zpk-default", 1, null, null);
        Hsm.KeyMaterial adopted = as(() -> hsm.rewrap(old));
        assertThat(kms.calls).contains("Encrypt");
        assertThat(adopted.masterRef()).isEqualTo(ARN_1);
        assertThat(adopted.kcv()).isEqualTo(new SoftwareHsm(legacyMaster).kcv(old));
        assertThatThrownBy(() -> new AwsKmsKeyManagement(kms, " ")).isInstanceOf(IllegalStateException.class);
    }
}
