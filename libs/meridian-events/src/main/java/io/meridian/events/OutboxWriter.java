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
}
