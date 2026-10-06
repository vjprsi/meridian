package io.meridian.crm.infrastructure;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.crm.application.TimelineService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Projects other domains' events (binary CloudEvents: ce_type, ce_id, ce_tenantid headers; API payload) into the
 * customer timeline. Idempotent by ce_id; events without a customer reference are ignored.
 */
@Component
@ConditionalOnProperty(name = "meridian.crm.timeline.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class TimelineProjector {
    private static final Logger log = LoggerFactory.getLogger(TimelineProjector.class);

    private final TimelineService timeline;
    private final JsonMapper json;

    public TimelineProjector(TimelineService timeline, JsonMapper json) {
        this.timeline = timeline;
        this.json = json;
    }

    @KafkaListener(topics = "#{'${meridian.crm.timeline.topics:meridian.party.v1,meridian.accounts.v1,meridian.payments.v1,meridian.cards.v1,meridian.lending.v1,meridian.wallets.v1}'.split(',')}",
            groupId = "crm-service-timeline")
    public void onEvent(ConsumerRecord<String, String> record) throws Exception {
        String type = header(record, "ce_type");
        String tenant = header(record, "ce_tenantid");
        if (type == null || tenant == null || record.value() == null) {
            return;
        }
        JsonNode payload = json.readTree(record.value());
        boolean written = TenantContext.callAs(UUID.fromString(tenant), "crm-service",
                () -> timeline.project(header(record, "ce_id"), type, payload));
        if (written) {
            log.debug("Projected {} into the customer timeline", type);
        }
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
