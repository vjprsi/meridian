package io.meridian.events;

import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records a domain event in the service's {@code outbox} table inside the caller's transaction, so the event
 * is published if and only if the state change commits (transactional outbox pattern).
 */
public class OutboxWriter {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * @param eventType CloudEvents type, e.g. {@code ledger.journal_entry.posted}
     * @param aggregateType entity name, e.g. {@code journal_entry}
     * @param aggregateId entity id (also the Kafka key, preserving per-entity ordering)
     * @param payload entity representation; serialised as JSON
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String eventType, String aggregateType, UUID aggregateId, Object payload) {
        UUID id = Ids.newId();
        String headers = json.writeValueAsString(java.util.Map.of(
                "ce_principal", TenantContext.principal()));
        jdbc.sql("""
                insert into outbox (id, tenant_id, aggregate_type, aggregate_id, event_type, payload, headers)
                values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)""")
                .params(id, TenantContext.require(), aggregateType, aggregateId, eventType,
                        json.writeValueAsString(payload), headers)
                .update();
        return id;
    }

    /** One outbox row per event in a single statement. Each event: {type, aggregate_type, aggregate_id, payload}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishMany(java.util.List<Event> events) {
        if (events.isEmpty()) {
            return;
        }
        UUID tenant = TenantContext.require();
        String headers = json.writeValueAsString(java.util.Map.of("ce_principal", TenantContext.principal()));
        var arr = json.createArrayNode();
        for (Event e : events) {
            var n = arr.addObject();
            n.put("id", Ids.newId().toString());
            n.put("tenant_id", tenant.toString());
            n.put("aggregate_type", e.aggregateType());
            n.put("aggregate_id", e.aggregateId().toString());
            n.put("event_type", e.type());
            n.set("payload", json.valueToTree(e.payload()));
            n.set("headers", json.readTree(headers));
        }
        jdbc.sql("""
                insert into outbox (id, tenant_id, aggregate_type, aggregate_id, event_type, payload, headers)
                select id, tenant_id, aggregate_type, aggregate_id, event_type, payload, headers
                from json_populate_recordset(null::outbox, ?::json)""")
                .param(json.writeValueAsString(arr)).update();
    }

    public record Event(String type, String aggregateType, UUID aggregateId, Object payload) {}
}
