package io.meridian.fraud.infrastructure;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import io.meridian.fraud.application.Pseudonymizer;
import io.meridian.fraud.domain.features.FeatureEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feeds the real-time feature store from domain events (binary CloudEvents: ce_id, ce_type, ce_time, ce_tenantid):
 * outbound payments, card authorisations, wallet transactions and fraud-service's own evaluations (devices, logins).
 * Each event is applied under its tenant, at most once per ce_id (the store de-duplicates in the same statement).
 * Malformed events are skipped; database errors propagate so the container retries the record.
 * Expired rows of a tenant are pruned at most every {@code prune-interval-ms} from this consumer.
 */
@Component
@ConditionalOnProperty(name = "meridian.fraud.ml.feature-consumer.enabled", havingValue = "true", matchIfMissing = true)
public class FeatureEventsListener {
    private static final Logger log = LoggerFactory.getLogger(FeatureEventsListener.class);
    static final String PRINCIPAL = "svc:fraud-feature-store";
    static final long PRUNE_INTERVAL_MS = 600_000;
    static final int MAX_TRACKED_TENANTS = 10_000;

    private final FeatureStore store;
    private final JsonMapper json;
    private final Pseudonymizer pseudonyms;
    private final Map<UUID, Long> lastPrune = new ConcurrentHashMap<>();

    public FeatureEventsListener(FeatureStore store, JsonMapper json, Pseudonymizer pseudonyms) {
        this.store = store;
        this.json = json;
        this.pseudonyms = pseudonyms;
    }

    @KafkaListener(id = "fraud-feature-store",
            topics = "#{'${meridian.fraud.ml.feature-topics:meridian.payments.v1,meridian.cards.v1,meridian.wallets.v1,meridian.risk.v1}'.split(',')}",
            groupId = "${meridian.fraud.ml.feature-group:fraud-service-feature-store}",
            properties = {"auto.offset.reset=earliest"})
    public void onEvent(ConsumerRecord<String, String> record) throws Exception {
        String type = header(record, "ce_type");
        String tenant = header(record, "ce_tenantid");
        String id = header(record, "ce_id");
        if (type == null || tenant == null || id == null || record.value() == null || !FeatureEvent.TYPES.contains(type)) {
            return;
        }
        Optional<FeatureEvent> event;
        UUID tenantId;
        try {
            tenantId = UUID.fromString(tenant);
            JsonNode payload = json.readTree(record.value());
            event = FeatureEvent.from(id, type, time(header(record, "ce_time")), payload, pseudonyms.forTenant(tenantId));
        } catch (IllegalArgumentException | JacksonException e) {
            log.warn("Feature store skips malformed event {} {}: {}", type, id, e.getMessage());
            return;
        }
        if (event.isEmpty()) {
            return;
        }
        TenantContext.callAs(tenantId, PRINCIPAL, () -> apply(tenantId, event.get()));
    }

    /** Applies one event for the bound tenant (also used by tests); returns false for a duplicate. */
    public boolean apply(UUID tenant, FeatureEvent e) {
        boolean applied = store.apply(e, PRINCIPAL);
        long now = System.currentTimeMillis();
        Long last = lastPrune.get(tenant);
        if (last == null || now - last > PRUNE_INTERVAL_MS) {
            if (lastPrune.size() > MAX_TRACKED_TENANTS) {
                lastPrune.clear();
            }
            lastPrune.put(tenant, now);
            int pruned = store.prune(Clocks.now().toInstant());
            if (pruned > 0) {
                log.info("Feature store pruned {} expired rows", pruned);
            }
        }
        return applied;
    }

    private static Instant time(String ceTime) {
        if (ceTime == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(ceTime).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
