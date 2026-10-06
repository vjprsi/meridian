package io.meridian.audit.application;

import io.meridian.audit.domain.AuditRecord;
import io.meridian.audit.domain.HashChain;
import io.meridian.audit.infrastructure.AuditEventStore;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.time.Instant;
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

    public AuditRecorder(AuditEventStore store, CrudEngine crud, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
    }

    /** An event to record; occurredAt defaults to now. */
    public record NewAuditEvent(Instant occurredAt, String actorType, String actorId, String actorDisplay,
                                String impersonatorId, String action, String resourceType, String resourceId,
                                String outcome, String reason, JsonNode beforeState, JsonNode afterState,
                                UUID legalEntityId, UUID approvalRequestId, String ipAddress, String userAgent,
                                String sessionId, String requestId, String traceId, String serviceName) {}

    /** Records the event and returns the stored row (JSON). */
    @Transactional
    public String record(NewAuditEvent e) {
        return append(e, false).orElseThrow();
    }

    /**
     * Records the event unless one with the same request id already exists (at-least-once consumers).
     * Empty when it was a duplicate.
     */
    @Transactional
    public Optional<String> recordOnce(NewAuditEvent e) {
        return append(e, true);
    }

    private Optional<String> append(NewAuditEvent e, boolean dedupe) {
        UUID tenant = TenantContext.require();
        validate(e);
        store.lockTenantChain(tenant);
        if (dedupe && e.requestId() != null && store.existsByRequestId(e.requestId())) {
            return Optional.empty();
        }
        String before = store.normaliseJson(e.beforeState() == null || e.beforeState().isNull()
                ? null : json.writeValueAsString(e.beforeState()));
        String after = store.normaliseJson(e.afterState() == null || e.afterState().isNull()
                ? null : json.writeValueAsString(e.afterState()));
        String ip = store.normaliseInet(blankToNull(e.ipAddress()));
        AuditRecord rec = new AuditRecord(Ids.newId(), tenant, 0,
                e.occurredAt() == null ? Instant.now() : e.occurredAt(), e.actorType(), e.actorId(),
                e.actorDisplay(), e.impersonatorId(), e.action(), e.resourceType(), e.resourceId(), e.outcome(),
                e.reason(), store.parse(before), store.parse(after), e.legalEntityId(), e.approvalRequestId(), ip,
                e.userAgent(), e.sessionId(), e.requestId(), e.traceId(), e.serviceName(), null, null);
        Optional<AuditEventStore.Head> head = store.head();
        AuditRecord linked = HashChain.link(rec, head.map(h -> h.sequenceNo() + 1).orElse(1L),
                head.map(AuditEventStore.Head::hash).orElse(HashChain.GENESIS));

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
        if (before != null) {
            v.set("before_state", json.readTree(before));
        }
        if (after != null) {
            v.set("after_state", json.readTree(after));
        }
        v.put("legal_entity_id", str(linked.legalEntityId()));
        v.put("approval_request_id", str(linked.approvalRequestId()));
        v.put("ip_address", linked.ipAddress());
        v.put("user_agent", linked.userAgent());
        v.put("session_id", linked.sessionId());
        v.put("request_id", linked.requestId());
        v.put("trace_id", linked.traceId());
        v.put("service_name", linked.serviceName());
        v.put("prev_hash", linked.prevHash());
        v.put("hash", linked.hash());
        String row = crud.insert("audit_event", v);
        outbox.publish("platform.audit_event.recorded", "audit_event", linked.id(), json.readTree(row));
        return Optional.of(row);
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
