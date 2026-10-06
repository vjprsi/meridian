package io.meridian.search.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.search.application.IndexService;
import io.meridian.web.idempotency.Idempotency;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Index API (not in the generated contract, which has no way to feed documents): owning services or the CDC
 * consumer push entity documents here. Body: {@code {"entity_type","entity_id","document":{...},"event_id",
 * "version","occurred_at"}} or {@code {"documents":[...]}} for a batch.
 */
@RestController
public class SearchDocumentsController {
    private static final int MAX_BATCH = 500;

    private final IndexService indexes;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public SearchDocumentsController(IndexService indexes, Idempotency idempotency, JsonMapper json) {
        this.indexes = indexes;
        this.idempotency = idempotency;
        this.json = json;
    }

    @PostMapping(value = "/v1/search/documents", consumes = "application/json", produces = "application/json")
    public ResponseEntity<JsonNode> index(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                          @RequestBody JsonNode body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, JsonNode.class, () -> {
            JsonNode docs = body.has("documents") ? body.get("documents") : json.createArrayNode().add(body);
            if (!docs.isArray() || docs.isEmpty() || docs.size() > MAX_BATCH) {
                throw ApiException.unprocessable("invalid_batch", "documents must hold 1.." + MAX_BATCH + " entries");
            }
            ObjectNode out = json.createObjectNode();
            ArrayNode results = out.putArray("data");
            for (JsonNode d : docs) {
                results.add(indexes.ingest(Http.text(d, "entity_type"), Http.text(d, "entity_id"), d.get("document"),
                        d.path("deleted").asBoolean(false), Http.text(d, "event_id"),
                        d.hasNonNull("version") ? d.get("version").asLong() : null,
                        d.hasNonNull("occurred_at") ? OffsetDateTime.parse(d.get("occurred_at").asString()) : null));
            }
            out.put("indexed", results.size());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(out);
        });
    }

    @DeleteMapping("/v1/search/documents/{entityType}/{entityId}")
    public ResponseEntity<JsonNode> remove(@PathVariable String entityType, @PathVariable String entityId) {
        TenantContext.require();
        return ResponseEntity.ok(indexes.ingest(entityType, entityId, null, true, null, null, null));
    }
}
