package io.meridian.router;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies tenant-service events to the router's caches. Every router instance reads every event (its own consumer
 * group, latest offset): a tenant event ({@code platform.tenant.*}, {@code platform.tenant_move.*}) drops that tenant's
 * cached placement; a cell event ({@code platform.cell.*}) carries the cell row and updates the cell directory at once
 * (an operator marking a cell OFFLINE fails its tenants over without waiting for the TTL). After a (re)assignment the
 * whole placement cache is dropped, since events may have been missed meanwhile.
 */
public class RegistryEvents implements ConsumerSeekAware {
    private static final Logger log = LoggerFactory.getLogger(RegistryEvents.class);

    private final PlacementCache placements;
    private final CellDirectory cells;
    private final JsonMapper json;

    public RegistryEvents(PlacementCache placements, CellDirectory cells, JsonMapper json) {
        this.placements = placements;
        this.cells = cells;
        this.json = json;
    }

    @KafkaListener(id = "cell-router-registry", topics = "${meridian.router.events.topic:meridian.platform.v1}",
            groupId = "cell-router-${random.uuid}", autoStartup = "${meridian.router.events.enabled:true}",
            properties = {"auto.offset.reset=latest", "enable.auto.commit=true"})
    public void onRecord(ConsumerRecord<String, String> rec) {
        apply(header(rec, "ce_type"), header(rec, "ce_tenantid"), rec.value());
    }

    /** Applies one event: {@code type} = ce_type, {@code tenantHeader} = ce_tenantid, {@code payload} = the row. */
    public void apply(String type, String tenantHeader, String payload) {
        if (type == null) {
            return;
        }
        try {
            if (type.startsWith("platform.cell.")) {
                JsonNode cell = json.readTree(payload);
                if (cell.hasNonNull("id") && cell.hasNonNull("status")) {
                    cells.put(Registry.Cell.of(cell));
                }
            } else if (type.startsWith("platform.tenant.") || type.startsWith("platform.tenant_move.")) {
                UUID tenant = subject(type, tenantHeader, payload);
                if (tenant != null) {
                    placements.invalidate(tenant);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Ignoring unreadable registry event {}: {}", type, e.getMessage());
        }
    }

    private UUID subject(String type, String tenantHeader, String payload) {
        JsonNode p = payload == null ? null : json.readTree(payload);
        if (p != null && p.hasNonNull("subject_tenant_id")) {
            return TenantResolver.uuid(p.get("subject_tenant_id").asString());       // placements, moves
        }
        if (type.startsWith("platform.tenant.") && p != null && p.hasNonNull("id") && p.hasNonNull("code")) {
            return TenantResolver.uuid(p.get("id").asString());                       // tenant rows
        }
        return TenantResolver.uuid(tenantHeader);       // tenant events are stamped with the subject tenant
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekAware.ConsumerSeekCallback callback) {
        placements.clear();
    }

    private static String header(ConsumerRecord<String, String> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
