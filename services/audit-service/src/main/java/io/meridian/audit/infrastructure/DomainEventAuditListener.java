package io.meridian.audit.infrastructure;

import io.meridian.audit.application.AuditRecorder;
import io.meridian.common.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records every domain event published on the platform's Kafka topics (binary-mode CloudEvents from the outbox
 * relays) as an audit event of the emitting tenant. Delivery is at-least-once, so events are de-duplicated on
 * {@code ce_id} (stored as request_id {@code ce:<id>}). The audit service's own events are skipped.
 */
@Component
public class DomainEventAuditListener {
    private static final Logger log = LoggerFactory.getLogger(DomainEventAuditListener.class);

    private final AuditRecorder recorder;
    private final JsonMapper json;

    public DomainEventAuditListener(AuditRecorder recorder, JsonMapper json) {
        this.recorder = recorder;
        this.json = json;
    }

    @KafkaListener(topicPattern = "meridian\\..*\\.v1", groupId = "audit-service",
            autoStartup = "${meridian.audit.listener-enabled:true}")
    public void onEvent(ConsumerRecord<String, String> rec) throws Exception {
        String type = header(rec, "ce_type");
        String tenant = header(rec, "ce_tenantid");
        String id = header(rec, "ce_id");
        String source = header(rec, "ce_source");
        if (type == null || tenant == null || id == null) {
            log.debug("Skipping non-CloudEvents record on {}", rec.topic());
            return;
        }
        if (type.startsWith("platform.audit_") || "audit-service".equals(source)) {
            return;
        }
        JsonNode payload = rec.value() == null ? null : json.readTree(rec.value());
        String actor = text(payload, "updated_by");
        if (actor == null) {
            actor = text(payload, "created_by");
        }
        boolean system = actor == null || "system".equals(actor) || "anonymous".equals(actor);
        String[] parts = type.split("\\.");
        String resourceType = parts.length >= 3 ? parts[1] : type;
        Instant at = parseTime(header(rec, "ce_time"));
        UUID legalEntity = uuid(text(payload, "legal_entity_id"));
        AuditRecorder.NewAuditEvent event = new AuditRecorder.NewAuditEvent(at,
                system ? "SYSTEM" : "STAFF_USER", truncate(system ? "system" : actor, 64), null, null,
                truncate(type, 128), truncate(resourceType, 64), truncate(rec.key(), 64), "SUCCESS", null,
                null, payload != null && payload.isObject() ? payload : null, legalEntity,
                uuid(text(payload, "approval_request_id")), null, null, null, "ce:" + id, null,
                truncate(source == null ? rec.topic() : source, 64));
        TenantContext.callAs(UUID.fromString(tenant), "audit-service", () -> recorder.recordOnce(event));
    }

    private static String header(ConsumerRecord<String, String> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static String text(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) ? n.get(f).asString() : null;
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant parseTime(String s) {
        try {
            return s == null ? Instant.now() : OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException e) {
            return Instant.now();
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
