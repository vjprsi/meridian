package io.meridian.tenant.application;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Publishes tenant-service domain events through the transactional outbox. Events about a tenant aggregate
 * itself (provisioned, activated, suspended, migrated) are stamped with the subject tenant's id, so downstream
 * services receive them in that tenant's context; everything else uses the caller's tenant.
 */
@Component
public class Events {
    private final OutboxWriter outbox;

    public Events(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void publish(String type, String aggregate, UUID id, JsonNode payload) {
        outbox.publish(type, aggregate, id, payload);
    }

    public void publishForTenant(UUID subjectTenant, String type, String aggregate, UUID id, JsonNode payload) {
        try {
            TenantContext.callAs(subjectTenant, TenantContext.principal(), () -> outbox.publish(type, aggregate, id, payload));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
