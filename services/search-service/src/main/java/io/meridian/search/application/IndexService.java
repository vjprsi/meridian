package io.meridian.search.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.search.domain.IndexRules;
import io.meridian.search.domain.SearchText;
import io.meridian.search.infrastructure.DocumentStore;
import io.meridian.search.infrastructure.JsonValues;
import io.meridian.search.infrastructure.Rows;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Search index definitions and document ingestion. Each index serves reads through its alias, which points at
 * one physical index; during a reindex every change is dual-written to the physical index being built so the
 * alias swap loses nothing.
 */
@Service
public class IndexService {
    private final Rows rows;
    private final DocumentStore docs;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final int degradedLagSeconds;

    public IndexService(Rows rows, DocumentStore docs, OutboxWriter outbox, TransactionTemplate tx, JsonMapper json,
                        @Value("${meridian.search.degraded-lag-seconds:300}") int degradedLagSeconds) {
        this.rows = rows;
        this.docs = docs;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
        this.degradedLagSeconds = degradedLagSeconds;
    }

    /** Validates a new index definition and assigns its first physical index. */
    public ObjectNode prepareCreate(ObjectNode v) {
        validate(v);
        String alias = v.get("alias_name").asString();
        if (rows.queryOne("select row_to_json(i)::text from search_index i where alias_name = ?", alias).isPresent()) {
            throw ApiException.conflict("alias_in_use", "Alias " + alias + " is already used by another index");
        }
        int version = v.path("mapping_version").asInt(1);
        v.put("mapping_version", version);
        v.put("physical_index", IndexRules.physicalName(alias, version));
        v.put("status", "ACTIVE");
        v.put("document_count", 0);
        return v;
    }

    /** Rules on merged values for updates; bumps the mapping version when mapping or fields change. */
    public void prepareUpdate(JsonNode current, ObjectNode merged, ObjectNode changes) {
        if (changes.has("entity_type") && !changes.get("entity_type").asString().equals(current.get("entity_type").asString())) {
            throw ApiException.unprocessable("entity_type_immutable", "The entity type of an index cannot change");
        }
        if (changes.has("alias_name") && !changes.get("alias_name").asString().equals(current.get("alias_name").asString())) {
            throw ApiException.unprocessable("alias_immutable", "The alias of an index cannot change; create a new index");
        }
        validate(merged);
        boolean mappingChanged = (changes.has("mapping") && !changes.get("mapping").equals(current.get("mapping")))
                || (changes.has("searchable_fields") && !changes.get("searchable_fields").equals(current.get("searchable_fields")));
        if (mappingChanged && !changes.has("mapping_version")) {
            changes.put("mapping_version", current.get("mapping_version").asInt() + 1);
        } else if (changes.has("mapping_version") && changes.get("mapping_version").asInt() < current.get("mapping_version").asInt()) {
            throw ApiException.unprocessable("mapping_version_regression", "mapping_version can only increase");
        }
    }

    private void validate(JsonNode v) {
        IndexRules.entityType(JsonValues.text(v, "entity_type"));
        IndexRules.requireAlias(JsonValues.text(v, "alias_name"));
        JsonNode mapping = v.get("mapping");
        if (mapping == null || !mapping.isObject() || !mapping.path("properties").isObject()) {
            throw ApiException.unprocessable("invalid_mapping", "mapping must be an object with a properties section");
        }
        List<SearchText.Field> fields;
        try {
            fields = SearchText.fields(v.get("searchable_fields"));
        } catch (NumberFormatException e) {
            throw ApiException.unprocessable("invalid_searchable_fields", "Boosts must be numbers, e.g. full_name^3");
        }
        if (fields.isEmpty()) {
            throw ApiException.unprocessable("invalid_searchable_fields", "searchable_fields must list at least one field");
        }
        for (SearchText.Field f : fields) {
            String root = f.path().split("\\.")[0];
            if (!mapping.get("properties").has(root)) {
                throw ApiException.unprocessable("unmapped_field", "Searchable field " + f.path() + " is not in the mapping");
            }
            if (f.boost() <= 0) {
                throw ApiException.unprocessable("invalid_searchable_fields", "Boost of " + f.path() + " must be positive");
            }
        }
        List<String> topics = JsonValues.strings(v.get("source_topics"));
        if (topics.isEmpty() || topics.stream().anyMatch(t -> !t.matches("meridian\\.[a-z_-]+\\.v\\d+"))) {
            throw ApiException.unprocessable("invalid_source_topics", "source_topics must list topics like meridian.party.v1");
        }
        for (String m : JsonValues.strings(v.get("masked_fields"))) {
            if (!mapping.get("properties").has(m.split("\\.")[0])) {
                throw ApiException.unprocessable("unmapped_field", "Masked field " + m + " is not in the mapping");
            }
        }
    }

    public JsonNode indexFor(String entityType) {
        return rows.queryOne("select row_to_json(i)::text from search_index i where entity_type = ?", entityType)
                .orElseThrow(() -> ApiException.unprocessable("index_not_found", "No search index is defined for " + entityType));
    }

    /**
     * Ingests one change (from the index API or a domain-event consumer): journals it, then applies it to the
     * alias's physical index and, during a reindex, to the index being built.
     */
    public JsonNode ingest(String entityType, String entityId, JsonNode document, boolean delete, String eventId,
                           Long version, OffsetDateTime occurredAt) {
        String type = IndexRules.entityType(entityType);
        if (entityId == null || entityId.isBlank() || entityId.length() > 128) {
            throw ApiException.unprocessable("invalid_entity_id", "entity_id is required (max 128 characters)");
        }
        if (!delete && (document == null || !document.isObject())) {
            throw ApiException.unprocessable("document_required", "document must be an object");
        }
        return tx.execute(s -> {
            JsonNode index = indexFor(type);
            if ("RETIRED".equals(index.get("status").asString())) {
                throw ApiException.conflict("index_retired", "The " + type + " index is retired");
            }
            docs.journal(type, entityId, delete ? "DELETE" : "UPSERT", document, eventId, version, occurredAt);
            boolean applied = apply(index, index.get("physical_index").asString(), type, entityId, document, delete, eventId,
                    version, occurredAt);
            buildingTarget(UUID.fromString(index.get("id").asString())).ifPresent(target ->
                    apply(index, target, type, entityId, document, delete, eventId, version, occurredAt));
            rows.update("update search_index set last_indexed_at = now() where id = ?", UUID.fromString(index.get("id").asString()));
            ObjectNode out = json.createObjectNode();
            out.put("entity_type", type);
            out.put("entity_id", entityId);
            out.put("operation", delete ? "DELETE" : "UPSERT");
            out.put("physical_index", index.get("physical_index").asString());
            out.put("applied", applied);
            return out;
        });
    }

    /** Applies one change to a physical index; false when it was stale (an equal or newer version is indexed). */
    boolean apply(JsonNode index, String physical, String type, String entityId, JsonNode document, boolean delete,
                  String eventId, Long version, OffsetDateTime occurredAt) {
        if (delete) {
            docs.delete(physical, entityId, version);
            return true;
        }
        List<SearchText.Field> fields = SearchText.fields(index.get("searchable_fields"));
        return docs.upsert(physical, type, entityId, SearchText.title(document, fields), document,
                SearchText.weightedText(document, fields), eventId, version, occurredAt) > 0;
    }

    /** The physical index a running full reindex is building, if any. */
    Optional<String> buildingTarget(UUID indexId) {
        return rows.queryOne("""
                select row_to_json(j)::text from search_index_job j
                where search_index_id = ? and job_type = 'FULL_REINDEX' and status in ('QUEUED', 'RUNNING')
                order by created_at desc limit 1""", indexId)
                .map(j -> j.path("target_index").asString(null));
    }

    /** Refreshes document count, lag and health; flags DEGRADED (event) when lag exceeds the threshold. */
    public String stats(UUID id) {
        return tx.execute(s -> {
            JsonNode index = rows.require("search_index", id, true);
            String status = index.get("status").asString();
            long count = docs.count(index.get("physical_index").asString());
            DocumentStore.Lag lag = docs.lag(index.get("entity_type").asString());
            String newStatus = status;
            if ("ACTIVE".equals(status) && lag.seconds() != null && lag.seconds() > degradedLagSeconds) {
                newStatus = "DEGRADED";
            } else if ("DEGRADED".equals(status) && (lag.seconds() == null || lag.seconds() <= degradedLagSeconds)) {
                newStatus = "ACTIVE";
            }
            String row = rows.updateReturning("""
                    update search_index set document_count = ?, lag_seconds = ?, status = ?,
                           last_indexed_at = coalesce(?, last_indexed_at)
                    where id = ? returning row_to_json(search_index)::text""",
                    count, lag.seconds(), newStatus, lag.lastIndexedAt(), id);
            if ("DEGRADED".equals(newStatus) && !"DEGRADED".equals(status)) {
                outbox.publish("operations.search_index.degraded", "search_index", id, json.readTree(row));
            }
            return row;
        });
    }

    static String principal() {
        return TenantContext.principal();
    }
}
