package io.meridian.audit.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tamper-evident hash chain over a tenant's audit events.
 *
 * <p>Canonical form (version 1): a JSON object with the fields below in this fixed order, serialised by
 * {@link CanonicalJson} (nested state documents have sorted keys); {@code occurred_at} is UTC ISO-8601 with exactly
 * six fractional digits; uuids are lower-case; absent values are JSON null. The event hash is
 * {@code hex(SHA-256(UTF-8(canonical)))} and {@code prev_hash} (part of the canonical form) links it to the previous
 * event; the first event of a tenant links to {@link #GENESIS}. {@code source_event_id} (the consumed CloudEvents id)
 * follows {@code service_name} only when present, so events recorded without it keep their original hashes.
 */
public final class HashChain {
    public static final String GENESIS = "0".repeat(64);
    public static final int VERSION = 1;
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private HashChain() {}

    public static String canonical(AuditRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", VERSION);
        m.put("id", str(r.id()));
        m.put("tenant_id", str(r.tenantId()));
        m.put("sequence_no", r.sequenceNo());
        m.put("occurred_at", r.occurredAt() == null ? null : TS.format(r.occurredAt()));
        m.put("actor_type", r.actorType());
        m.put("actor_id", r.actorId());
        m.put("actor_display", r.actorDisplay());
        m.put("impersonator_id", r.impersonatorId());
        m.put("action", r.action());
        m.put("resource_type", r.resourceType());
        m.put("resource_id", r.resourceId());
        m.put("outcome", r.outcome());
        m.put("reason", r.reason());
        m.put("before_state", r.beforeState());
        m.put("after_state", r.afterState());
        m.put("legal_entity_id", str(r.legalEntityId()));
        m.put("approval_request_id", str(r.approvalRequestId()));
        m.put("ip_address", r.ipAddress());
        m.put("user_agent", r.userAgent());
        m.put("session_id", r.sessionId());
        m.put("request_id", r.requestId());
        m.put("trace_id", r.traceId());
        m.put("service_name", r.serviceName());
        if (r.sourceEventId() != null) {
            m.put("source_event_id", r.sourceEventId());
        }
        m.put("prev_hash", r.prevHash());
        // Fixed order: serialise the top level by hand (CanonicalJson would sort the keys).
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(CanonicalJson.write(e.getKey())).append(':').append(CanonicalJson.write(e.getValue()));
        }
        return sb.append('}').toString();
    }

    public static String hash(AuditRecord r) {
        return sha256(canonical(r));
    }

    /** Assigns sequence and links the record to the previous event's hash. */
    public static AuditRecord link(AuditRecord r, long sequenceNo, String prevHash) {
        AuditRecord withPrev = r.withChain(sequenceNo, prevHash, null);
        return withPrev.withChain(sequenceNo, prevHash, hash(withPrev));
    }

    public enum BreakReason { HASH_MISMATCH, PREV_HASH_MISMATCH, SEQUENCE_GAP }

    public record Verification(boolean valid, long fromSequence, long toSequence, int checked,
                               Long firstBrokenSequence, BreakReason reason, java.util.UUID brokenEventId) {}

    /**
     * Verifies stored events ordered by sequence. {@code anchorHash} is the stored hash of event {@code from-1}
     * ({@link #GENESIS} when {@code from == 1}; null when that event is missing, which is itself a gap).
     * Sequences after the last stored event are not a break (the chain simply ends there).
     */
    public static Verification verify(List<AuditRecord> ordered, long from, long to, String anchorHash) {
        String expectedPrev = anchorHash;
        long expectedSeq = from;
        int checked = 0;
        if (anchorHash == null && !ordered.isEmpty()) {
            return new Verification(false, from, to, 0, from - 1, BreakReason.SEQUENCE_GAP, null);
        }
        for (AuditRecord r : ordered) {
            if (r.sequenceNo() != expectedSeq) {
                return new Verification(false, from, to, checked, expectedSeq, BreakReason.SEQUENCE_GAP, r.id());
            }
            checked++;
            if (!expectedPrev.equals(r.prevHash())) {
                return new Verification(false, from, to, checked, r.sequenceNo(), BreakReason.PREV_HASH_MISMATCH, r.id());
            }
            if (!hash(r).equals(r.hash())) {
                return new Verification(false, from, to, checked, r.sequenceNo(), BreakReason.HASH_MISMATCH, r.id());
            }
            expectedPrev = r.hash();
            expectedSeq++;
        }
        return new Verification(true, from, to, checked, null, null, null);
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString().toLowerCase(java.util.Locale.ROOT);
    }
}
