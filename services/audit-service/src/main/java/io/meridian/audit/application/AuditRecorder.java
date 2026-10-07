package io.meridian.audit.application;

import io.meridian.audit.domain.AuditRecord;
import io.meridian.audit.domain.HashChain;
import io.meridian.audit.domain.PiiMasker;
import io.meridian.audit.infrastructure.AuditEventStore;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import io.meridian.common.time.Clocks;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Appends audit events to the tenant's hash chain: per-tenant advisory lock, gapless sequence, prev_hash link,
 * SHA-256 over the canonical form ({@link HashChain}). The table itself is append-only (database trigger).
 * Before / after states are stored with personal data masked ({@link PiiMasker}, GAP-07): the hash covers the
 * masked form, so the chain never depends on clear personal data.
 */
@Service
public class AuditRecorder {
    static final Set<String> ACTOR_TYPES =
            Set.of("STAFF_USER", "CUSTOMER", "API_CLIENT", "SERVICE", "SYSTEM", "PLATFORM_OPERATOR", "AGENT");
    static final Set<String> OUTCOMES = Set.of("SUCCESS", "FAILURE", "DENIED");

    private final AuditEventStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final PiiMasker masker;

    public AuditRecorder(AuditEventStore store, CrudEngine crud, OutboxWriter outbox, JsonMapper json, PiiMasker masker) {
        this.store = store;
        this.masker = masker;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
    }

    /** An event to record; occurredAt defaults to now. */
    public record NewAuditEvent(Instant occurredAt, String actorType, String actorId, String actorDisplay,
                                String impersonatorId, String action, String resourceType, String resourceId,
                                String outcome, String reason, JsonNode beforeState, JsonNode afterState,
                                UUID legalEntityId, UUID approvalRequestId, String ipAddress, String userAgent,
                                String sessionId, String requestId, String traceId, String serviceName,
                                String sourceEventId) {}

    /** Records the event and returns the stored row (JSON). */
    @Transactional
    public String record(NewAuditEvent e) {
        return append(e, false).orElseThrow();
    }

    /**
     * Records the event unless one with the same source event id (consumed CloudEvents id) already exists
     * (at-least-once consumers). Empty when it was a duplicate.
     */
    @Transactional
    public Optional<String> recordOnce(NewAuditEvent e) {
        return append(e, true);
    }

    /**
     * An event validated and masked outside any transaction (CPU work that batch ingestion runs in parallel):
     * {@code before} / {@code after} are the masked states as JSON text (not yet normalised by jsonb).
     */
    public record Prepared(NewAuditEvent event, String before, String after) {}

    /** Validates and masks an event of {@code tenant}; thread-safe, no database access. */
    public Prepared prepare(UUID tenant, NewAuditEvent e) {
        validate(e);
        JsonNode beforeState = masker.mask(tenant, e.resourceType(), e.beforeState());
        JsonNode afterState = masker.mask(tenant, e.resourceType(), e.afterState());
        return new Prepared(e, beforeState == null || beforeState.isNull() ? null : json.writeValueAsString(beforeState),
                afterState == null || afterState.isNull() ? null : json.writeValueAsString(afterState));
    }

    private Optional<String> append(NewAuditEvent e, boolean dedupe) {
        UUID tenant = TenantContext.require();
        Prepared p = prepare(tenant, e);
        store.lockTenantChain(tenant);
        if (dedupe && e.sourceEventId() != null && store.existsBySourceEventId(e.sourceEventId())) {
            return Optional.empty();
        }
        String before = store.normaliseJson(p.before());
        String after = store.normaliseJson(p.after());
        String ip = store.normaliseInet(blankToNull(e.ipAddress()));
        Optional<AuditEventStore.Head> head = store.head();
        AuditRecord linked = HashChain.link(record(tenant, e, before, after, ip),
                head.map(h -> h.sequenceNo() + 1).orElse(1L),
                head.map(AuditEventStore.Head::hash).orElse(HashChain.GENESIS));
        String row = crud.insert("audit_event", row(linked, before, after));
        outbox.publish("platform.audit_event.recorded", "audit_event", linked.id(), json.readTree(row));
        return Optional.of(row);
    }

    /**
     * Appends a batch of consumed events of the bound tenant in one transaction (batch ingestion): one advisory lock
     * (the same per-tenant lock as {@link #record}, so API writes and other consumers still serialise), one
     * de-duplication query on the source event ids (duplicates inside the batch keep their first occurrence), one
     * jsonb / inet normalisation round trip, the chain head read once and the hashes linked in memory in batch order,
     * then a multi-row insert of the events and one of their outbox records. Returns how many events were stored.
     */
    @Transactional
    public int appendBatch(List<Prepared> batch) {
        UUID tenant = TenantContext.require();
        if (batch.isEmpty()) {
            return 0;
        }
        store.lockTenantChain(tenant);
        Set<String> seen = new HashSet<>(store.existingSourceEventIds(batch.stream()
                .map(p -> p.event().sourceEventId()).filter(Objects::nonNull).distinct().toList()));
        List<Prepared> fresh = new ArrayList<>(batch.size());
        for (Prepared p : batch) {
            String src = p.event().sourceEventId();
            if (src == null || seen.add(src)) {
                fresh.add(p);
            }
        }
        if (fresh.isEmpty()) {
            return 0;
        }
        List<String> docs = new ArrayList<>(fresh.size() * 2);
        List<String> ips = new ArrayList<>(fresh.size());
        for (Prepared p : fresh) {
            docs.add(p.before());
            docs.add(p.after());
            ips.add(blankToNull(p.event().ipAddress()));
        }
        List<String> normalised = store.normaliseJsonAll(docs);
        List<String> inets = store.normaliseInetAll(ips);
        Optional<AuditEventStore.Head> head = store.head();
        long seq = head.map(AuditEventStore.Head::sequenceNo).orElse(0L);
        String prev = head.map(AuditEventStore.Head::hash).orElse(HashChain.GENESIS);
        List<ObjectNode> rows = new ArrayList<>(fresh.size());
        for (int i = 0; i < fresh.size(); i++) {
            String before = normalised.get(2 * i);
            String after = normalised.get(2 * i + 1);
            AuditRecord linked = HashChain.link(record(tenant, fresh.get(i).event(), before, after, inets.get(i)),
                    ++seq, prev);
            prev = linked.hash();
            rows.add(row(linked, before, after));
        }
        List<String> stored = crud.insertMany("audit_event", rows);
        List<OutboxWriter.Event> events = new ArrayList<>(stored.size());
        for (String r : stored) {
            JsonNode n = json.readTree(r);
            events.add(new OutboxWriter.Event("platform.audit_event.recorded", "audit_event",
                    UUID.fromString(n.get("id").asString()), n));
        }
        outbox.publishMany(events);
        return stored.size();
    }

    private AuditRecord record(UUID tenant, NewAuditEvent e, String before, String after, String ip) {
        return new AuditRecord(Ids.newId(), tenant, 0,
                e.occurredAt() == null ? Clocks.now().toInstant() : e.occurredAt(), e.actorType(), e.actorId(),
                e.actorDisplay(), e.impersonatorId(), e.action(), e.resourceType(), e.resourceId(), e.outcome(),
                e.reason(), store.parse(before), store.parse(after), e.legalEntityId(), e.approvalRequestId(), ip,
                e.userAgent(), e.sessionId(), e.requestId(), e.traceId(), e.serviceName(), e.sourceEventId(), null, null);
    }

    /** The stored columns of a linked event (every key present, so a batch shares one column set). */
    private ObjectNode row(AuditRecord linked, String before, String after) {
        ObjectNode v = json.createObjectNode();
        v.put("id", linked.id().toString());
        v.put("occurred_at", linked.occurredAt().toString());
        v.put("sequence_no", linked.sequenceNo());
        v.put("actor_type", linked.actorType());
        v.put("actor_id", linked.actorId());
        v.put("actor_display", linked.actorDisplay());
        v.put("impersonator_id", linked.impersonatorId());
        v.put("action", linked.action());
        v.put("resource_type", linked.resourceType());
        v.put("resource_id", linked.resourceId());
        v.put("outcome", linked.outcome());
        v.put("reason", linked.reason());
        v.set("before_state", before == null ? json.nullNode() : json.readTree(before));
        v.set("after_state", after == null ? json.nullNode() : json.readTree(after));
        v.put("legal_entity_id", str(linked.legalEntityId()));
        v.put("approval_request_id", str(linked.approvalRequestId()));
        v.put("ip_address", linked.ipAddress());
        v.put("user_agent", linked.userAgent());
        v.put("session_id", linked.sessionId());
        v.put("request_id", linked.requestId());
        v.put("trace_id", linked.traceId());
        v.put("service_name", linked.serviceName());
        v.put("source_event_id", linked.sourceEventId());
        v.put("prev_hash", linked.prevHash());
        v.put("hash", linked.hash());
        return v;
    }

    private static void validate(NewAuditEvent e) {
        require(e.actorType() != null && ACTOR_TYPES.contains(e.actorType()), "invalid_actor_type",
                "actor_type must be one of " + ACTOR_TYPES);
        require(e.outcome() != null && OUTCOMES.contains(e.outcome()), "invalid_outcome",
                "outcome must be one of " + OUTCOMES);
        require(notBlank(e.actorId()), "actor_required", "actor_id is required");
        require(notBlank(e.action()), "action_required", "action is required");
        require(notBlank(e.resourceType()), "resource_type_required", "resource_type is required");
        require(notBlank(e.serviceName()), "service_name_required", "service_name is required");
    }

    private static void require(boolean ok, String code, String msg) {
        if (!ok) {
            throw ApiException.unprocessable(code, msg);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
