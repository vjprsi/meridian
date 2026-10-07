package io.meridian.cardvault.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.cardvault.application.Hsm;
import io.meridian.cardvault.application.KeyManagement;
import io.meridian.common.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** GAP-01: envelope encryption, encryption context binding, data key cache, master key rotation and adoption. */
class EnvelopeHsmTest {
    static final UUID TENANT = UUID.fromString("0199a000-0000-7000-8000-0000000e0001");
    static final UUID OTHER = UUID.fromString("0199a000-0000-7000-8000-0000000e0002");
    static final byte[] A = Base64.getDecoder().decode("bWVyaWRpYW4tZGV2LXZhdWx0LW1hc3Rlci1rZXktMDE=");
    static final byte[] B = "another-32-byte-master-key-0002!".getBytes(StandardCharsets.US_ASCII);
    static final byte[] AAD = "t:tok_1:pan".getBytes(StandardCharsets.UTF_8);

    static <T> T as(UUID tenant, Callable<T> c) throws Exception {
        return TenantContext.callAs(tenant, "test", c);
    }

    static SoftwareKeyManagement software(byte[]... masters) {
        return new SoftwareKeyManagement(java.util.Arrays.stream(masters)
                .map(m -> MasterKeyProviders.fromBase64(Base64.getEncoder().encodeToString(m), "test")).toList());
    }

    static Hsm.KeyRef with(Hsm.KeyRef k, Hsm.KeyMaterial m) {
        return new Hsm.KeyRef(k.type(), k.label(), k.version(), m.wrapped(), m.masterRef());
    }

    @Test
    void dataKeysAreStoredWrappedAndBoundToTenantAndPurpose() throws Exception {
        EnvelopeHsm hsm = new EnvelopeHsm(software(A), null, Duration.ofMinutes(5), 100);
        Hsm.KeyRef dek = new Hsm.KeyRef("PAN_DEK", "pan-dek@" + TENANT, 1, null, null);
        Hsm.KeyMaterial m = as(TENANT, () -> hsm.createKey(dek));
        assertThat(m.wrapped()).isNotNull().hasSizeGreaterThan(32);
        assertThat(m.masterRef()).isEqualTo(SoftwareKeyManagement.ref(A)).startsWith("software:");
        assertThat(m.kcv()).hasSize(6);
        Hsm.KeyRef stored = with(dek, m);
        byte[] blob = as(TENANT, () -> hsm.encrypt(stored, "4111111111111111".getBytes(), AAD));

        // a fresh instance (no cache) unwraps the data key under the same context
        EnvelopeHsm fresh = new EnvelopeHsm(software(A), null, Duration.ofMinutes(5), 100);
        assertThat(new String(as(TENANT, () -> fresh.decrypt(stored, blob, AAD)))).isEqualTo("4111111111111111");
        // the same wrapped key under another tenant or purpose does not unwrap (encryption context)
        assertThatThrownBy(() -> as(OTHER, () -> fresh.decrypt(stored, blob, AAD)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("encryption context");
        Hsm.KeyRef asCvk = new Hsm.KeyRef("CVK", stored.label(), 1, stored.wrapped(), stored.masterRef());
        assertThatThrownBy(() -> as(TENANT, () -> fresh.decrypt(asCvk, blob, AAD)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(hsm.describe()).doesNotContain(Base64.getEncoder().encodeToString(A));
    }

    @Test
    void unwrappedDataKeysAreCachedForTheTtl() throws Exception {
        AtomicInteger decrypts = new AtomicInteger();
        KeyManagement counting = counting(software(A), decrypts);
        Hsm.KeyRef dek = new Hsm.KeyRef("PAN_DEK", "pan-dek@" + TENANT, 1, null, null);
        Hsm.KeyRef stored = with(dek, as(TENANT, () -> new EnvelopeHsm(counting, null, Duration.ZERO, 10).createKey(dek)));

        EnvelopeHsm cached = new EnvelopeHsm(counting, null, Duration.ofMinutes(5), 10);
        for (int i = 0; i < 5; i++) {
            as(TENANT, () -> cached.encrypt(stored, new byte[]{1}, AAD));
        }
        assertThat(decrypts.get()).isEqualTo(1);
        cached.flush();
        as(TENANT, () -> cached.encrypt(stored, new byte[]{1}, AAD));
        assertThat(decrypts.get()).isEqualTo(2);

        EnvelopeHsm uncached = new EnvelopeHsm(counting, null, Duration.ZERO, 10);
        as(TENANT, () -> uncached.encrypt(stored, new byte[]{1}, AAD));
        as(TENANT, () -> uncached.encrypt(stored, new byte[]{1}, AAD));
        assertThat(decrypts.get()).isEqualTo(4);
    }

    @Test
    void masterKeyRotationRewrapsDataKeysWithoutReEncryptingData() throws Exception {
        Hsm.KeyRef dek = new Hsm.KeyRef("PAN_DEK", "pan-dek@" + TENANT, 1, null, null);
        EnvelopeHsm underA = new EnvelopeHsm(software(A), null, Duration.ZERO, 10);
        Hsm.KeyRef stored = with(dek, as(TENANT, () -> underA.createKey(dek)));
        byte[] blob = as(TENANT, () -> underA.encrypt(stored, "4111111111111111".getBytes(), AAD));

        // rotation: B is the new master, A is still accepted for unwrapping until the re-wrap has run
        EnvelopeHsm rotating = new EnvelopeHsm(software(B, A), null, Duration.ZERO, 10);
        assertThat(rotating.currentMasterRef()).isEqualTo(SoftwareKeyManagement.ref(B));
        Hsm.KeyMaterial rewrapped = as(TENANT, () -> rotating.rewrap(stored));
        assertThat(rewrapped.masterRef()).isEqualTo(SoftwareKeyManagement.ref(B));
        assertThat(rewrapped.wrapped()).isNotEqualTo(stored.wrapped());
        assertThat(rewrapped.kcv()).isEqualTo(as(TENANT, () -> underA.kcv(stored)));   // same data key

        // after the re-wrap A can be retired: the unchanged ciphertext decrypts under B alone
        EnvelopeHsm onlyB = new EnvelopeHsm(software(B), null, Duration.ZERO, 10);
        assertThat(new String(as(TENANT, () -> onlyB.decrypt(with(dek, rewrapped), blob, AAD))))
                .isEqualTo("4111111111111111");
        assertThatThrownBy(() -> as(TENANT, () -> onlyB.decrypt(stored, blob, AAD)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unknown master key");
    }

    @Test
    void legacyDerivedKeysStayReadableAndAreAdoptedByTheRewrap() throws Exception {
        SoftwareHsm legacy = new SoftwareHsm(A);
        Hsm.KeyRef old = new Hsm.KeyRef("PAN_DEK", "pan-dek", 1, null, null);
        byte[] blob = legacy.encrypt(old, "4111111111111111".getBytes(), AAD);

        EnvelopeHsm hsm = new EnvelopeHsm(software(B), A, Duration.ZERO, 10);
        assertThat(new String(as(TENANT, () -> hsm.decrypt(old, blob, AAD)))).isEqualTo("4111111111111111");
        Hsm.KeyMaterial adopted = as(TENANT, () -> hsm.rewrap(old));
        assertThat(adopted.wrapped()).isNotNull();
        assertThat(adopted.masterRef()).isEqualTo(SoftwareKeyManagement.ref(B));
        assertThat(adopted.kcv()).isEqualTo(legacy.kcv(old));

        // once adopted, the legacy master key is no longer needed
        EnvelopeHsm withoutLegacy = new EnvelopeHsm(software(B), null, Duration.ZERO, 10);
        assertThat(new String(as(TENANT, () -> withoutLegacy.decrypt(with(old, adopted), blob, AAD))))
                .isEqualTo("4111111111111111");
        assertThatThrownBy(() -> as(TENANT, () -> withoutLegacy.decrypt(old, blob, AAD)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("legacy master key");
    }

    @Test
    void softwareDataKeysMatchTheDocumentedDerivationSoDevDataKeepsWorking() throws Exception {
        EnvelopeHsm hsm = new EnvelopeHsm(software(A), null, Duration.ZERO, 10);
        Hsm.KeyRef zpk = new Hsm.KeyRef("ZPK", "zpk-default", 1, null, null);
        Hsm.KeyMaterial m = as(TENANT, () -> hsm.createKey(zpk));
        assertThat(m.kcv()).isEqualTo(new SoftwareHsm(A).kcv(zpk));
        assertThat(List.of(hsm.pinInsideHsm())).containsExactly(false);
    }

    static KeyManagement counting(KeyManagement d, AtomicInteger decrypts) {
        return new KeyManagement() {
            public DataKey generateDataKey(Map<String, String> c) {
                return d.generateDataKey(c);
            }

            public byte[] decrypt(WrappedKey w, Map<String, String> c) {
                decrypts.incrementAndGet();
                return d.decrypt(w, c);
            }

            public WrappedKey wrap(byte[] p, Map<String, String> c) {
                return d.wrap(p, c);
            }

            public WrappedKey reWrap(WrappedKey w, Map<String, String> c) {
                return d.reWrap(w, c);
            }

            public String currentMasterRef() {
                return d.currentMasterRef();
            }

            public String describe() {
                return d.describe();
            }
        };
    }
}
