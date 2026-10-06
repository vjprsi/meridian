package io.meridian.search.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.search.api.generated.SearchApi;
import io.meridian.search.api.generated.model.SearchDocumentsRequest;
import io.meridian.search.api.generated.model.SearchQueryRequest;
import io.meridian.search.application.IndexService;
import io.meridian.search.application.SearchService;
import io.meridian.search.infrastructure.JsonValues;
import io.meridian.web.idempotency.Idempotency;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Federated query, type-ahead and the index feed. Query results: {@code {data:[{entity_type, entity_id, score,
 * title, document}], has_more, next_cursor, entity_types, masked, took_ms}}. Queries are read-only, so no
 * Idempotency-Key replay is applied (a replayed search must reflect the current index and the caller's PII
 * permission). Owning services and the CDC consumer push documents through {@code POST /v1/search/documents}.
 */
@RestController
public class SearchController implements SearchApi {
    private static final int MAX_BATCH = 500;

    private final SearchService search;
    private final IndexService indexes;
    private final Permissions permissions;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public SearchController(SearchService search, IndexService indexes, Permissions permissions, Idempotency idempotency,
                            JsonMapper json) {
        this.search = search;
        this.indexes = indexes;
        this.permissions = permissions;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<Map<String, Object>> searchQuery(String idempotencyKey, SearchQueryRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return ResponseEntity.ok(Http.object(json, search.query(new SearchService.Query(Http.text(req, "query_text"),
                JsonValues.strings(req.get("entity_types")), req.get("filters"),
                req.hasNonNull("limit") ? req.get("limit").asInt() : null, Http.text(req, "cursor")), permissions.piiAllowed())));
    }

    @Override
    public ResponseEntity<Map<String, Object>> searchSuggest(String q, String entityType, Integer limit) {
        TenantContext.require();
        return ResponseEntity.ok(Http.object(json, search.suggest(q, entityType, limit, permissions.piiAllowed())));
    }

    /**
     * Indexes a batch of 1..500 documents, each {@code {entity_type, entity_id, document | deleted, event_id,
     * version, occurred_at}}; out-of-order versions are ignored per document.
     */
    @Override
    public ResponseEntity<Map<String, Object>> searchDocuments(String idempotencyKey, SearchDocumentsRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return Http.object(json, idempotency.execute(idempotencyKey, req, JsonNode.class, () -> {
            JsonNode docs = req.get("documents");
            if (docs == null || !docs.isArray() || docs.isEmpty() || docs.size() > MAX_BATCH) {
                throw ApiException.unprocessable("invalid_batch", "documents must hold 1.." + MAX_BATCH + " entries");
            }
            ObjectNode out = json.createObjectNode();
            ArrayNode results = out.putArray("data");
            for (JsonNode d : docs) {
                if (!d.isObject()) {
                    throw ApiException.unprocessable("invalid_document", "Each entry of documents must be an object");
                }
                results.add(indexes.ingest(Http.text(d, "entity_type"), Http.text(d, "entity_id"), d.get("document"),
                        d.path("deleted").asBoolean(false), Http.text(d, "event_id"),
                        d.hasNonNull("version") ? d.get("version").asLong() : null, timestamp(d)));
            }
            out.put("indexed", results.size());
            return ResponseEntity.ok(out);
        }));
    }

    @Override
    public ResponseEntity<Map<String, Object>> searchRemoveDocuments(String entityType, String entityRef,
                                                                     String idempotencyKey) {
        TenantContext.require();
        return Http.object(json, idempotency.execute(idempotencyKey,
                json.createObjectNode().put("remove", entityType + "/" + entityRef), JsonNode.class,
                () -> ResponseEntity.ok(indexes.ingest(entityType, entityRef, null, true, null, null, null))));
    }

    private static OffsetDateTime timestamp(JsonNode d) {
        String v = Http.text(d, "occurred_at");
        try {
            return v == null ? null : OffsetDateTime.parse(v);
        } catch (DateTimeParseException e) {
            throw ApiException.unprocessable("invalid_occurred_at", "occurred_at must be an ISO-8601 timestamp");
        }
    }
}
