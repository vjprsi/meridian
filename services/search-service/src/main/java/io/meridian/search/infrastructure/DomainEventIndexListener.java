package io.meridian.search.infrastructure;

import io.meridian.search.application.EventIndexer;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Feeds the search indexes from every domain topic ({@code meridian.<domain>.v1}, pattern subscription, so new
 * domains are picked up on the next metadata refresh). Records are binary-mode CloudEvents from the outbox relays.
 * A new consumer group starts from the earliest retained offset, which backfills the indexes from the topics'
 * retention window. Delivery is at-least-once; {@link EventIndexer} is idempotent and order-tolerant. Disable with
 * {@code meridian.search.indexer.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "meridian.search.indexer.enabled", havingValue = "true", matchIfMissing = true)
public class DomainEventIndexListener {
    private final EventIndexer indexer;

    public DomainEventIndexListener(EventIndexer indexer) {
        this.indexer = indexer;
    }

    @KafkaListener(id = "search-service-indexer", topicPattern = "meridian\\..*\\.v1",
            groupId = "${meridian.search.indexer.group-id:search-service-indexer}",
            properties = {"auto.offset.reset=earliest",
                    "metadata.max.age.ms=${meridian.search.indexer.metadata-max-age-ms:60000}"})
    public void onEvent(ConsumerRecord<String, String> rec) {
        indexer.index(new EventIndexer.Event(header(rec, "ce_type"), header(rec, "ce_tenantid"), header(rec, "ce_id"),
                header(rec, "ce_source"), header(rec, "ce_time"), rec.value()));
    }

    private static String header(ConsumerRecord<String, String> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null || h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
