package io.meridian.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HashChainTest {
    static final UUID TENANT = UUID.fromString("0199a000-0000-7000-8000-0000000000aa");

    private static AuditRecord event(String action, Object after) {
        return new AuditRecord(UUID.nameUUIDFromBytes(action.getBytes(java.nio.charset.StandardCharsets.UTF_8)), TENANT, 0, Instant.parse("2026-10-06T10:15:30.123456789Z"),
                "STAFF_USER", "alice", "Alice", null, action, "payment", "p-1", "SUCCESS", null, null, after,
                null, null, "10.0.0.1/32", null, null, null, null, "payment-service", null, null);
    }

    private static List<AuditRecord> chain(int n) {
        List<AuditRecord> out = new ArrayList<>();
        String prev = HashChain.GENESIS;
        for (int i = 1; i <= n; i++) {
            AuditRecord r = HashChain.link(event("payments.payment.approve", Map.of("n", i)), i, prev);
            out.add(r);
            prev = r.hash();
        }
        return out;
    }

    @Test
    void canonicalFormIsStableAcrossKeyOrderAndNumberScale() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", new BigDecimal("1.50"));
        a.put("a", List.of(1, "x"));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", List.of(1L, "x"));
        b.put("b", new BigDecimal("1.5"));
        assertThat(CanonicalJson.write(a)).isEqualTo(CanonicalJson.write(b)).isEqualTo("{\"a\":[1,\"x\"],\"b\":1.5}");
        assertThat(HashChain.hash(event("x", a).withChain(1, HashChain.GENESIS, null)))
                .isEqualTo(HashChain.hash(event("x", b).withChain(1, HashChain.GENESIS, null)))
                .hasSize(64);
    }

    @Test
    void timestampsAreTruncatedToMicroseconds() {
        assertThat(HashChain.canonical(event("x", null))).contains("\"occurred_at\":\"2026-10-06T10:15:30.123456Z\"");
    }

    @Test
    void intactChainVerifies() {
        HashChain.Verification v = HashChain.verify(chain(5), 1, 5, HashChain.GENESIS);
        assertThat(v.valid()).isTrue();
        assertThat(v.checked()).isEqualTo(5);
    }

    @Test
    void detectsTamperedContentLinkAndGap() {
        List<AuditRecord> c = chain(5);
        AuditRecord third = c.get(2);
        c.set(2, new AuditRecord(third.id(), third.tenantId(), 3, third.occurredAt(), third.actorType(), "mallory",
                third.actorDisplay(), null, third.action(), third.resourceType(), third.resourceId(), third.outcome(),
                null, null, third.afterState(), null, null, third.ipAddress(), null, null, null, null,
                third.serviceName(), third.prevHash(), third.hash()));
        HashChain.Verification v = HashChain.verify(c, 1, 5, HashChain.GENESIS);
        assertThat(v.valid()).isFalse();
        assertThat(v.firstBrokenSequence()).isEqualTo(3L);
        assertThat(v.reason()).isEqualTo(HashChain.BreakReason.HASH_MISMATCH);

        List<AuditRecord> relinked = chain(5);
        relinked.set(3, relinked.get(3).withChain(4, "f".repeat(64), null));
        relinked.set(3, relinked.get(3).withChain(4, "f".repeat(64), HashChain.hash(relinked.get(3))));
        assertThat(HashChain.verify(relinked, 1, 5, HashChain.GENESIS).reason())
                .isEqualTo(HashChain.BreakReason.PREV_HASH_MISMATCH);

        List<AuditRecord> gap = chain(5);
        gap.remove(1);
        HashChain.Verification g = HashChain.verify(gap, 1, 5, HashChain.GENESIS);
        assertThat(g.reason()).isEqualTo(HashChain.BreakReason.SEQUENCE_GAP);
        assertThat(g.firstBrokenSequence()).isEqualTo(2L);
    }

    @Test
    void partialRangeUsesAnchorHash() {
        List<AuditRecord> c = chain(5);
        assertThat(HashChain.verify(c.subList(2, 5), 3, 5, c.get(1).hash()).valid()).isTrue();
        assertThat(HashChain.verify(c.subList(2, 5), 3, 5, HashChain.GENESIS).valid()).isFalse();
    }

    @Test
    void exportTransitionsAndRetentionRules() {
        assertThat(ExportRules.canTransition("QUEUED", "CANCELLED")).isTrue();
        assertThat(ExportRules.canTransition("SUCCEEDED", "CANCELLED")).isFalse();
        assertThat(ExportRules.validateRetention("*", 10, 365)).isNull();
        assertThat(ExportRules.validateRetention("payment", 1, 365)).isEqualTo("invalid_archive_after_days");
        assertThat(ExportRules.validateRetention("Bad Type", 10, 1)).isEqualTo("invalid_resource_type");
        assertThat(ExportRules.validateRetention("payment", 0, 0)).isEqualTo("invalid_retention_years");
    }
}
