package io.meridian.cardvault.infrastructure;

import io.meridian.cardvault.application.KeyManagement;
import java.util.Map;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.EncryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;
import software.amazon.awssdk.services.kms.model.KmsException;
import software.amazon.awssdk.services.kms.model.ReEncryptResponse;

/**
 * AWS KMS {@link KeyManagement} (GAP-01): the master key is a customer-managed KMS key of the cell's CDE (infra/tofu
 * cell module {@code cde_kms_key_arn}); it never leaves KMS. Data keys come from {@code GenerateDataKey} (AES-256),
 * are unwrapped with {@code Decrypt}, legacy keys are adopted with {@code Encrypt} and master key rotation uses
 * {@code ReEncrypt} (KMS decrypts and re-encrypts server side; the clear data key never reaches the vault). Every
 * call carries the encryption context (tenant, purpose, key), which KMS binds to the ciphertext and writes to
 * CloudTrail, so every data key use is attributable to a tenant and purpose.
 *
 * <p>{@code keyId} may be a key ARN or an alias ({@code alias/meridian-<cell>-cde}). The master reference stored with
 * each data key is the ARN of the key that wrapped it. With KMS automatic (yearly) rotation the ARN stays the same
 * and old ciphertexts stay decryptable; a manual rotation (the alias re-pointed to a new key) changes
 * {@link #currentMasterRef()} and the re-wrap job then moves every data key to the new key.
 *
 * <p>DR: a multi-region key (primary cell) and its replica (standby cell) share key material and the {@code mrk-} key
 * id, so the reference of such a key is the region-independent key id: data keys replicated with the database unwrap
 * in either region and a failover needs no re-wrap.
 */
public class AwsKmsKeyManagement implements KeyManagement {
    private final KmsClient kms;
    private final String keyId;
    private volatile String currentArn;
    private volatile java.time.OffsetDateTime resolvedAt;
    /** How long the alias -> key ARN resolution is trusted (a re-pointed alias is picked up after this). */
    static final java.time.Duration RESOLVE_TTL = java.time.Duration.ofMinutes(10);

    public AwsKmsKeyManagement(KmsClient kms, String keyId) {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalStateException("meridian.cards.vault.kms.key-id is required for key-management=aws-kms");
        }
        this.kms = kms;
        this.keyId = keyId.trim();
    }

    private static final java.util.regex.Pattern MRK =
            java.util.regex.Pattern.compile("arn:aws[\\w-]*:kms:[^:]+:\\d+:key/(mrk-[0-9a-f]+)");

    /** Master key reference of a KMS key: its ARN, or the region-independent key id of a multi-region key. */
    static String ref(String arnOrId) {
        if (arnOrId == null) {
            return null;
        }
        java.util.regex.Matcher m = MRK.matcher(arnOrId);
        return m.matches() ? m.group(1) : arnOrId;
    }

    @Override
    public DataKey generateDataKey(Map<String, String> context) {
        try {
            GenerateDataKeyResponse r = kms.generateDataKey(b -> b.keyId(keyId).keySpec(DataKeySpec.AES_256)
                    .encryptionContext(context));
            return new DataKey(r.plaintext().asByteArray(), new WrappedKey(ref(r.keyId()), r.ciphertextBlob().asByteArray()));
        } catch (KmsException e) {
            throw new IllegalStateException("KMS GenerateDataKey failed: " + code(e), e);
        }
    }

    @Override
    public byte[] decrypt(WrappedKey wrapped, Map<String, String> context) {
        try {
            DecryptResponse r = kms.decrypt(b -> {
                b.ciphertextBlob(SdkBytes.fromByteArray(wrapped.ciphertext())).encryptionContext(context);
                if (wrapped.masterRef() != null && wrapped.masterRef().startsWith("arn:")) {
                    b.keyId(wrapped.masterRef());
                }
            });
            return r.plaintext().asByteArray();
        } catch (KmsException e) {
            throw new IllegalStateException("KMS Decrypt failed: " + code(e), e);
        }
    }

    @Override
    public WrappedKey wrap(byte[] plaintext, Map<String, String> context) {
        try {
            EncryptResponse r = kms.encrypt(b -> b.keyId(keyId).plaintext(SdkBytes.fromByteArray(plaintext))
                    .encryptionContext(context));
            return new WrappedKey(ref(r.keyId()), r.ciphertextBlob().asByteArray());
        } catch (KmsException e) {
            throw new IllegalStateException("KMS Encrypt failed: " + code(e), e);
        }
    }

    @Override
    public WrappedKey reWrap(WrappedKey wrapped, Map<String, String> context) {
        try {
            ReEncryptResponse r = kms.reEncrypt(b -> {
                b.ciphertextBlob(SdkBytes.fromByteArray(wrapped.ciphertext()))
                        .sourceEncryptionContext(context)
                        .destinationKeyId(keyId)
                        .destinationEncryptionContext(context);
                if (wrapped.masterRef() != null && wrapped.masterRef().startsWith("arn:")) {
                    b.sourceKeyId(wrapped.masterRef());
                }
            });
            return new WrappedKey(ref(r.keyId()), r.ciphertextBlob().asByteArray());
        } catch (KmsException e) {
            throw new IllegalStateException("KMS ReEncrypt failed: " + code(e), e);
        }
    }

    /** The ARN the configured key id / alias resolves to now (DescribeKey, cached for {@link #RESOLVE_TTL}). */
    @Override
    public String currentMasterRef() {
        String arn = currentArn;
        java.time.OffsetDateTime now = io.meridian.common.time.Clocks.now();
        if (arn == null || resolvedAt == null || resolvedAt.plus(RESOLVE_TTL).isBefore(now)) {
            try {
                arn = ref(kms.describeKey(b -> b.keyId(keyId)).keyMetadata().arn());
            } catch (KmsException e) {
                throw new IllegalStateException("KMS DescribeKey failed: " + code(e), e);
            }
            currentArn = arn;
            resolvedAt = now;
        }
        return arn;
    }

    /** Forgets the resolved key ARN (the re-wrap job calls it so an alias re-pointed to a new key is picked up). */
    public void refresh() {
        currentArn = null;
    }

    @Override
    public String describe() {
        return "AWS KMS key " + keyId;
    }

    private static String code(KmsException e) {
        return e.awsErrorDetails() != null && e.awsErrorDetails().errorCode() != null
                ? e.awsErrorDetails().errorCode() : String.valueOf(e.statusCode());
    }
}
