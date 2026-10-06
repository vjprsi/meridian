package io.meridian.notification.infrastructure;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.notification.application.RuleService;
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
 * Consumes binary-mode CloudEvents from every domain topic ({@code meridian.<domain>.v1}) and hands them to the
 * notification rules (and maker-checker outcomes for templates). Deduplication is by CloudEvent id
 * ({@code source_event_id}), so at-least-once delivery is safe.
 */
@Component
@ConditionalOnProperty(name = "meridian.notifications.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class DomainEventListener {
    private static final Logger log = LoggerFactory.getLogger(DomainEventListener.class);

    private final RuleService rules;
    private final JsonMapper json;

    public DomainEventListener(RuleService rules, JsonMapper json) {
        this.rules = rules;
        this.json = json;
    }

    @KafkaListener(topicPattern = "meridian\\..*\\.v1", groupId = "${spring.application.name}")
    public void onEvent(ConsumerRecord<String, String> record) {
        String tenant = header(record, "ce_tenantid");
        String type = header(record, "ce_type");
        String id = header(record, "ce_id");
        if (tenant == null || type == null || id == null || record.value() == null) {
            return;
        }
        try {
            TenantContext.callAs(UUID.fromString(tenant), "notification-service",
                    () -> rules.onEvent(id, type, json.readTree(record.value())));
        } catch (Exception e) {
            log.warn("Event {} ({}) not processed: {}", id, type, e.toString());
        }
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
