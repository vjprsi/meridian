package io.meridian.webhook.infrastructure;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.webhook.application.DeliveryService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes every domain topic ({@code meridian.<domain>.v1}, binary-mode CloudEvents) and fans events out to
 * subscribed endpoints. Ingestion is idempotent per (endpoint, ce_id), so Kafka redelivery is harmless.
 */
@Component
@ConditionalOnProperty(name = "meridian.webhooks.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class DomainEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(DomainEventConsumer.class);

    private final DeliveryService deliveries;
    private final JsonMapper json;

    public DomainEventConsumer(DeliveryService deliveries, JsonMapper json) {
        this.deliveries = deliveries;
        this.json = json;
    }

    @KafkaListener(topicPattern = "meridian\\..*\\.v1", groupId = "webhook-service",
            properties = {"auto.offset.reset=earliest"})
    public void onEvent(ConsumerRecord<String, String> record) throws Exception {
        String tenant = header(record, "ce_tenantid");
        String id = header(record, "ce_id");
        String type = header(record, "ce_type");
        if (tenant == null || id == null || type == null || record.value() == null) {
            log.warn("Skipping record without CloudEvents headers on {}@{}", record.topic(), record.offset());
            return;
        }
        TenantContext.callAs(UUID.fromString(tenant), "webhook-service",
                () -> deliveries.ingest(id, type, record.key(), json.readTree(record.value())));
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
