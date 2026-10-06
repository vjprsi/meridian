package io.meridian.audit.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One audit event as covered by the hash chain. {@code beforeState}/{@code afterState} are parsed JSON object graphs
 * (Map/List/String/Number/Boolean) or null. {@code hash} is null until computed.
 */
public record AuditRecord(
        UUID id,
        UUID tenantId,
        long sequenceNo,
        Instant occurredAt,
        String actorType,
        String actorId,
        String actorDisplay,
        String impersonatorId,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        String reason,
        Object beforeState,
        Object afterState,
        UUID legalEntityId,
        UUID approvalRequestId,
        String ipAddress,
        String userAgent,
        String sessionId,
        String requestId,
        String traceId,
        String serviceName,
        String sourceEventId,
        String prevHash,
        String hash) {

    public AuditRecord {
        // PostgreSQL timestamptz keeps microseconds: normalise so hashes survive a database round trip.
        occurredAt = occurredAt == null ? null : occurredAt.truncatedTo(ChronoUnit.MICROS);
    }

    public AuditRecord withChain(long seq, String prev, String h) {
        return new AuditRecord(id, tenantId, seq, occurredAt, actorType, actorId, actorDisplay, impersonatorId,
                action, resourceType, resourceId, outcome, reason, beforeState, afterState, legalEntityId,
                approvalRequestId, ipAddress, userAgent, sessionId, requestId, traceId, serviceName, sourceEventId, prev, h);
    }
}
