package io.meridian.mover;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes tenant-service's move events: {@code platform.tenant_move.copy_requested} registers a run (idempotent by
 * move id, so a redelivered or replayed event is harmless) and starts it; {@code platform.tenant_move.cancelled}
 * stops it. One consumer group for all mover instances (each request is handled once; the lease covers the rest).
 */
public class CopyRequestListener {
    private static final Logger log = LoggerFactory.getLogger(CopyRequestListener.class);

    private final TenantMover mover;
    private final JsonMapper json;
    private final MoveScheduler scheduler;

    public CopyRequestListener(TenantMover mover, JsonMapper json, MoveScheduler scheduler) {
        this.mover = mover;
        this.json = json;
        this.scheduler = scheduler;
    }

    @KafkaListener(id = "data-mover-requests", topics = "${meridian.mover.events.topic:meridian.platform.v1}",
            groupId = "data-mover")
    public void onRecord(ConsumerRecord<String, String> rec) {
        apply(header(rec, "ce_type"), rec.value());
    }

    /** Applies one event ({@code type} = ce_type, {@code payload} = the tenant_move row / hand-off). */
    public void apply(String type, String payload) {
        if (type == null || !type.startsWith("platform.tenant_move.")) {
            return;
        }
        JsonNode p;
        try {
            p = json.readTree(payload);
        } catch (RuntimeException e) {
            log.warn("Ignoring unreadable {} event: {}", type, e.getMessage());
            return;
        }
        switch (type) {
            case "platform.tenant_move.copy_requested" -> {
                if (!p.hasNonNull("id") || !p.hasNonNull("subject_tenant_id")) {
                    log.warn("Ignoring copy request without move / tenant id");
                    return;
                }
                mover.register(p);
                scheduler.submit(UUID.fromString(p.get("id").asString()));
            }
            case "platform.tenant_move.cancelled", "platform.tenant_move.rejected" -> {
                if (p.hasNonNull("id")) {
                    mover.cancel(UUID.fromString(p.get("id").asString()));
                }
            }
            default -> { }
        }
    }

    private static String header(ConsumerRecord<String, String> rec, String key) {
        Header h = rec.headers().lastHeader(key);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
