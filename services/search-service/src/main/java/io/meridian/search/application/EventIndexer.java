package io.meridian.search.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import io.meridian.search.domain.FeedMappings;
import io.meridian.search.infrastructure.DocumentStore;
import io.meridian.search.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The indexing feed: turns one domain event (binary CloudEvent from any {@code meridian.<domain>.v1} topic) into a
 * search document of the emitting tenant through the declarative {@link FeedMappings}.
 * <ul>
 *   <li>Tenant from {@code ce_tenantid}; everything runs under that tenant, so RLS scopes every row.</li>
 *   <li>Idempotent per (tenant, entity type, entity id) with event-time ordering: the document version is the
 *       payload's {@code updated_at} (microseconds, refined by {@code row_version}), falling back to {@code ce_time};
 *       an event older than the indexed version, or older than a recorded deletion, is ignored (STALE).</li>
 *   <li>The tenant's index for the entity type is provisioned on first use when the tenant has not defined one.</li>
 *   <li>Dead letters: unmapped events (UNMAPPED) and unusable ones (REJECTED: no tenant, bad JSON, no id, ...) are
 *       logged and counted and never block the partition; infrastructure failures are counted and rethrown so the
 *       listener container retries them.</li>
 * </ul>
 * Metrics: {@code meridian.search.indexer.events{outcome, doc_kind}}, {@code meridian.search.indexer.lag} (event
 * time to indexing) and the gauge {@code meridian.search.indexer.last_lag_ms}.
 */
@Service
public class EventIndexer {
    private static final Logger log = LoggerFactory.getLogger(EventIndexer.class);
    static final String PRINCIPAL = "search-indexer";
    static final String SELF = "search-service";

    public enum Outcome { INDEXED, CLOSED, DELETED, STALE, IGNORED, UNMAPPED, REJECTED, FAILED }

    /** One received event: CloudEvents attributes and the raw JSON payload. */
    public record Event(String type, String tenantId, String id, String source, String time, String payload) {}

    private final FeedMappings mappings;
    private final IndexService indexes;
    private final DocumentStore docs;
    private final Rows rows;
    private final CrudEngine crud;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final MeterRegistry meters;
    private final Timer lag;
    private final AtomicLong lastLagMs = new AtomicLong();
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public EventIndexer(FeedMappings mappings, IndexService indexes, DocumentStore docs, Rows rows, CrudEngine crud,
                        TransactionTemplate tx, JsonMapper json, MeterRegistry meters) {
        this.mappings = mappings;
        this.indexes = indexes;
        this.docs = docs;
        this.rows = rows;
        this.crud = crud;
        this.tx = tx;
        this.json = json;
        this.meters = meters;
        this.lag = Timer.builder("meridian.search.indexer.lag").description("Event time to indexing")
                .register(meters);
        meters.gauge("meridian.search.indexer.last_lag_ms", lastLagMs);
    }

    public Outcome index(Event e) {
        if (e.type() == null || e.tenantId() == null) {
            return reject(e, null, "not a CloudEvent (ce_type / ce_tenantid missing)");
        }
        if (SELF.equals(e.source())) {
            return count(Outcome.IGNORED, "none");
        }
        FeedMappings.Mapping m = mappings.resolve(e.type()).orElse(null);
        if (m == null) {
            log.debug("No search mapping for {} ({}); skipped", e.type(), e.id());
            return count(Outcome.UNMAPPED, "none");
        }
        UUID tenant;
        try {
            tenant = UUID.fromString(e.tenantId());
        } catch (IllegalArgumentException ex) {
            return reject(e, m, "invalid ce_tenantid");
        }
        JsonNode payload;
        try {
            payload = e.payload() == null ? null : json.readTree(e.payload());
        } catch (JacksonException ex) {
            return reject(e, m, "payload is not JSON");
        }
        if (payload == null || !payload.isObject()) {
            return reject(e, m, "payload is not a JSON object");
        }
        String entityId = m.entityId(payload);
        if (entityId == null || entityId.isBlank() || entityId.length() > 128) {
            return reject(e, m, "payload has no usable " + m.id());
        }
        FeedMappings.Operation op = m.operation(e.type(), payload);
        OffsetDateTime eventTime = time(e.time());
        Long version = version(payload, eventTime);
        ObjectNode doc = op == FeedMappings.Operation.DELETE ? null : m.document(e.type(), payload, op);
        try {
            Outcome outcome = TenantContext.callAs(tenant, PRINCIPAL, () -> tx.execute(s -> {
                ensureIndex(m.entityType());
                Long deletedAt = docs.lastDeletedVersion(m.entityType(), entityId);
                if (op != FeedMappings.Operation.DELETE && deletedAt != null && version != null && version <= deletedAt) {
                    return Outcome.STALE; // replay of a change made before the entity was deleted / erased
                }
                JsonNode r = indexes.ingest(m.entityType(), entityId, doc, op == FeedMappings.Operation.DELETE, e.id(),
                        version, eventTime);
                if (!r.path("applied").asBoolean(true)) {
                    return Outcome.STALE;
                }
                return switch (op) {
                    case DELETE -> Outcome.DELETED;
                    case CLOSE -> Outcome.CLOSED;
                    case UPSERT -> Outcome.INDEXED;
                };
            }));
            if (eventTime != null && outcome != Outcome.STALE) {
                Duration d = Duration.between(eventTime, Clocks.now());
                if (!d.isNegative()) {
                    lag.record(d);
                    lastLagMs.set(d.toMillis());
                }
            }
            return count(outcome, m.docKind());
        } catch (ApiException ex) {
            return reject(e, m, ex.getMessage());
        } catch (RuntimeException ex) {
            count(Outcome.FAILED, m.docKind());
            throw ex;
        } catch (Exception ex) {
            count(Outcome.FAILED, m.docKind());
            throw new IllegalStateException(ex);
        }
    }

    /** The tenant's index for the entity type, created from the feed's standard profile when missing. */
    void ensureIndex(String entityType) {
        if (rows.queryOne("select row_to_json(i)::text from search_index i where entity_type = ?", entityType).isPresent()) {
            return;
        }
        String alias = entityType.toLowerCase(Locale.ROOT);
        if (rows.queryOne("select row_to_json(i)::text from search_index i where alias_name = ?", alias).isPresent()) {
            alias = alias + "_feed";
        }
        ObjectNode v = json.createObjectNode();
        v.put("entity_type", entityType);
        v.put("alias_name", alias);
        v.set("mapping", FeedMappings.indexMapping());
        ArrayNode topics = v.putArray("source_topics");
        mappings.topics(entityType).forEach(topics::add);
        ArrayNode searchable = v.putArray("searchable_fields");
        mappings.searchableFields().forEach(searchable::add);
        ArrayNode masked = v.putArray("masked_fields");
        mappings.maskedFields().forEach(masked::add);
        // a concurrent provisioning by another replica fails on uq (tenant, entity_type): the transaction rolls back
        // and the listener container redelivers the event, which then finds the index
        crud.insert("search_index", indexes.prepareCreate(v));
        log.info("Provisioned search index {} for tenant {}", alias, TenantContext.require());
    }

    /**
     * Document version for event-time ordering: the payload's {@code updated_at} in microseconds, times 1000 plus
     * {@code row_version} (mod 1000) so two changes in one transaction still order; else {@code ce_time}.
     */
    static Long version(JsonNode payload, OffsetDateTime eventTime) {
        OffsetDateTime at = time(payload.hasNonNull("updated_at") ? payload.get("updated_at").asString() : null);
        long rowVersion = payload.path("row_version").asLong(0);
        if (at == null) {
            at = eventTime;
            rowVersion = 0;
        }
        if (at == null) {
            return null;
        }
        long micros = Math.addExact(Math.multiplyExact(at.toEpochSecond(), 1_000_000L), at.getNano() / 1_000L);
        return Math.addExact(Math.multiplyExact(micros, 1_000L), Math.floorMod(rowVersion, 1_000L));
    }

    static OffsetDateTime time(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(s.replace(' ', 'T'));
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private Outcome reject(Event e, FeedMappings.Mapping m, String reason) {
        log.warn("Search indexer dead-lettered event {} ({}, tenant {}): {}", e.id(), e.type(), e.tenantId(), reason);
        return count(Outcome.REJECTED, m == null ? "none" : m.docKind());
    }

    private Outcome count(Outcome o, String docKind) {
        String outcome = o.name().toLowerCase(Locale.ROOT);
        counters.computeIfAbsent(outcome + "|" + docKind, k -> Counter.builder("meridian.search.indexer.events")
                .description("Domain events seen by the search indexer").tag("outcome", outcome).tag("doc_kind", docKind)
                .register(meters)).increment();
        return o;
    }
}
